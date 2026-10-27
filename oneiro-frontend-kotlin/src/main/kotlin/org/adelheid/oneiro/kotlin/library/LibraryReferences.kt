package org.adelheid.oneiro.kotlin.library

import org.adelheid.oneiro.kotlin.core.BodyLowering
import org.adelheid.oneiro.kotlin.core.Rejection
import org.adelheid.oneiro.lowering.translation.Helper
import org.adelheid.oneiro.lua.BinaryOperator
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.FunctionBody
import org.adelheid.oneiro.lua.Statement
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * References to the Kotlin library as Lua functions, where Oneiro knows a Lua form, as the Java front end does for
 * Java's library: a function Lua has stands as it is, `::println` as `print`; anything else is a small function of the
 * member's Lua form, `String::uppercase` as `function(text) return text:upper() end` and `list::add` as
 * `function(value) list[#list + 1] = value end`. For the rest, a lambda says the same.
 */
internal class LibraryReferences(private val body: BodyLowering) {

	private val unit = body.unit

	/** A reference to a function of the library: one outside any class, a string's, or a collection's. */
	fun function(reference: KtCallableReferenceExpression, function: KaNamedFunctionSymbol, receiver: KtExpression?):
		Expression {
		val id = function.callableId ?: throw unsupported(reference)
		val name = id.callableName.asString()
		if (receiver == null) {
			return when ("${id.packageName.asString()}.$name") {
				"kotlin.io.println" -> global("print")
				"kotlin.math.abs", "kotlin.math.max", "kotlin.math.min", "kotlin.math.sqrt", "kotlin.math.floor",
				"kotlin.math.ceil" -> Expression.field(global("math"), name)
				"kotlin.comparisons.maxOf", "kotlin.comparisons.minOf" ->
					Expression.field(global("math"), name.removeSuffix("Of"))
				else -> throw unsupported(reference)
			}
		}
		val type = typeNamed(receiver)
		if (type != null) {
			// A function of no object, String::uppercase, takes the object
			if (name == "toString") {
				return global("tostring")
			}
			val text = unit.localName(reference, "text")
			val value = if (type.classId == StandardClassIds.String) {
				body.library.strings.ofText(name, Expression.name(text))
			} else {
				null
			}
			return value?.let { function(text, it) } ?: throw unsupported(reference)
		}
		return collectionFunction(reference, name, receiver, function.valueParameters.size)
	}

	/** A reference to a property of the library: a string's `length`. */
	fun property(reference: KtCallableReferenceExpression, property: KaPropertySymbol, receiver: KtExpression?):
		Expression {
		val type = receiver?.let(::typeNamed)
		if (type?.classId == StandardClassIds.String && property.callableId?.callableName?.asString() == "length") {
			val text = unit.localName(reference, "text")
			return function(text, LibraryCalls.length(Expression.name(text)))
		}
		throw unsupported(reference)
	}

	/**
	 * `list::add` and its kin, on a collection that is a name, which the function reads when it runs, as Kotlin
	 * reads the collection where the reference is.
	 */
	private fun collectionFunction(reference: KtCallableReferenceExpression, name: String, receiver: KtExpression,
		parameters: Int): Expression {
		if (!body.isStable(receiver)) {
			throw Rejection(receiver, "Kotlin reads this value once, where the reference is, but Lua's function would"
				+ " read it each time it runs; store it in a local first.")
		}
		val collection = body.expression(receiver)
		val value = unit.localName(reference, if (name == "get" || name == "containsKey") "key" else "value")
		val element = Expression.name(value)
		val member = Expression.Index(collection, element)
		val present = Expression.binary(member, BinaryOperator.NOT_EQUAL, Expression.Nil())
		val kind = body.library.kind(with(unit.session) { receiver.expressionType })
		return when {
			kind == LibraryCalls.Kind.SEQUENCE && name == "add" && parameters == 1 -> action(reference, name, value,
				Statement.assignment(Expression.Index(collection, LibraryCalls.nextIndex(collection)), element))
			kind == LibraryCalls.Kind.SEQUENCE && name == "contains" -> {
				unit.use(Helper.LIST_CONTAINS)
				function(value, Helper.LIST_CONTAINS.invoke(collection, element))
			}
			kind == LibraryCalls.Kind.SET && name == "add" ->
				action(reference, name, value, Statement.assignment(member, Expression.BooleanLiteral(true)))
			kind == LibraryCalls.Kind.SET && name == "remove" ->
				action(reference, name, value, Statement.assignment(member, Expression.Nil()))
			kind == LibraryCalls.Kind.SET && name == "contains" -> function(value, present)
			kind == LibraryCalls.Kind.MAP && name == "get" -> function(value, member)
			kind == LibraryCalls.Kind.MAP && name == "containsKey" -> function(value, present)
			else -> throw unsupported(reference)
		}
	}

	/** The class a reference's receiver names, `String` in `String::uppercase`; null for a receiver that is a value. */
	private fun typeNamed(receiver: KtExpression): KaClassSymbol? = with(unit.session) {
		(receiver as? KtNameReferenceExpression)?.resolveSymbol() as? KaClassSymbol
	}

	private fun global(name: String): Expression {
		unit.useGlobal(name)
		return Expression.name(name)
	}

	/** `function(parameter) return value end`. */
	private fun function(parameter: String, value: Expression): Expression =
		Expression.FunctionExpression(FunctionBody.of(listOf(parameter), Block.of(Statement.Return(listOf(value)))))

	/**
	 * `function(parameter) statement end`, for a reference whose value nothing reads: one passed where a function that
	 * returns nothing is expected, as `forEach`'s action is. Anywhere else the code would read a value that Lua's
	 * assignment does not give.
	 */
	private fun action(reference: KtCallableReferenceExpression, name: String, parameter: String,
		statement: Statement): Expression {
		if (!returnsNothing(reference)) {
			throw Rejection(reference, "In Lua, ${reference.text} is an assignment, which gives no value, where"
				+ " Kotlin's $name tells whether the collection changed; so it works only where nothing reads that, as"
				+ " in forEach(${reference.text}). Write a lambda here, which can test the collection first.")
		}
		return Expression.FunctionExpression(FunctionBody.of(listOf(parameter), Block.of(statement)))
	}

	/**
	 * Whether the function a reference is expected to be returns nothing: a function type's, or the one abstract
	 * function of a fun interface, Kotlin's or Java's.
	 */
	private fun returnsNothing(reference: KtCallableReferenceExpression): Boolean = with(unit.session) {
		val expected = reference.expectedType ?: return false
		if (expected is KaFunctionType) {
			return expected.returnType.isUnitType
		}
		val function = expected.expandedSymbol?.memberScope?.callables?.filterIsInstance<KaNamedFunctionSymbol>()
			?.singleOrNull { it.modality == KaSymbolModality.ABSTRACT }
		function?.returnType?.isUnitType == true
	}

	private fun unsupported(reference: KtCallableReferenceExpression): Rejection =
		Rejection(reference, "Oneiro has no Lua function for this reference; write it as a lambda, { ... }, which it"
			+ " lowers in place.")
}
