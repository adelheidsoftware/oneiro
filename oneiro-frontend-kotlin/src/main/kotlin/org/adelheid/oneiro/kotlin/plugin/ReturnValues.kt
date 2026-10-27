package org.adelheid.oneiro.kotlin.plugin

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.adelheid.oneiro.kotlin.core.Annotations
import org.adelheid.oneiro.kotlin.core.BodyLowering
import org.adelheid.oneiro.kotlin.core.Rejection
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.Statement
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReturnExpression

/**
 * The several values a Lua function returns, which a type annotated `@LuaReturnValues` stands for, as in the Java front
 * end: a class of the plugin's, or a record of the RS3 bindings, whose components are the values in order. A call
 * whose type it is initialises a local, or a destructuring declaration, and each component becomes a Lua local of its
 * own; a function returns them as several values.
 */
internal class ReturnValues(private val body: BodyLowering) {

	private val unit = body.unit

	/** The locals each local of such a type was split into: its components' names and their locals, in order. */
	private val locals = mutableMapOf<PsiElement, Map<String, String>>()

	/** The several values a type stands for, by their names in order; null for a type that is one value. */
	fun components(type: KaType?): List<String>? = with(unit.session) {
		val symbol = type?.expandedSymbol ?: return null
		if (!unit.annotations.has(symbol, Annotations.LUA_RETURN_VALUES)) {
			return null
		}
		componentsOf(symbol)
	}

	private fun componentsOf(symbol: KaClassSymbol): List<String> = with(unit.session) {
		val constructor = symbol.declaredMemberScope.constructors.firstOrNull()
			?: throw IllegalStateException("values without a constructor: ${symbol.classId}")
		constructor.valueParameters.map { it.name.asString() }
	}

	/**
	 * `val ground = World.GetGroundHeight(0, x, z)` as `local height, available = World.GetGroundHeight(0, x, z)`:
	 * one local for each component the code after it reads, `_` for one before the last it reads, every one when the
	 * local is returned whole.
	 */
	fun local(property: KtProperty, initializer: KtExpression, components: List<String>): List<Statement> {
		if (!isCall(initializer) || isConstructor(initializer)) {
			throw Rejection(initializer, "The values a Lua function returns exist only at its call; initialise "
				+ "${property.name} with the call itself.")
		}
		val read = readComponents(property)
		val last = components.indexOfLast { it in read }
		val names = mutableMapOf<String, String>()
		val declared = mutableListOf<String>()
		for (index in 0..last) {
			val component = components[index]
			if (component !in read) {
				declared += "_"
				continue
			}
			val local = componentLocal(property, component, names.values.toSet())
			names[component] = local
			declared += local
		}
		locals[property] = names
		val call = body.expression(initializer)
		if (declared.isEmpty()) {
			return listOf(Statement.CallStatement(call))
		}
		return listOf(Statement.Local(declared, listOf(call)))
	}

	/** `val (low, high) = span(values)` as `local low, high = Heights.span(values)`. */
	fun destructured(declaration: KtDestructuringDeclaration): Statement {
		val initializer = declaration.initializer ?: throw Rejection(declaration, "This declaration has no value.")
		if (components(with(unit.session) { initializer.expressionType }) == null) {
			throw Rejection.notYet(declaration, "Destructuring a value that is not a function's several values")
		}
		if (!isCall(initializer) || isConstructor(initializer)) {
			throw Rejection(initializer, "The values a Lua function returns exist only at its call; destructure the"
				+ " call itself.")
		}
		val names = declaration.entries.map { unit.localName(it, it.name ?: "_") }
		return Statement.Local(names, listOf(body.expression(initializer)))
	}

	/** The local a component of a split local reads, `ground.height()` or `span.low`; null for any other read. */
	fun component(receiver: KtExpression, name: String, where: PsiElement): Expression? {
		val reference = receiver as? KtNameReferenceExpression ?: return null
		val declaration = with(unit.session) { reference.resolveSymbol()?.psi } ?: return null
		val names = locals[declaration] ?: return null
		val local = names[name] ?: throw Rejection(where, "The values a Lua function returns are only read, one by"
			+ " one; read one of ${names.keys.joinToString(", ")} instead.")
		return Expression.name(local)
	}

	/** Whether a name refers to a split local, which exists only as its components' locals. */
	fun isSplit(reference: KtNameReferenceExpression): Boolean =
		with(unit.session) { reference.resolveSymbol()?.psi } in locals

	/**
	 * The values a `return` of such a type gives: the arguments of `Span(low, high)`, the locals a split local was read
	 * into, or a call, whose values pass through.
	 */
	fun returned(value: KtExpression): List<Expression> {
		if (value is KtCallExpression && isConstructor(value)) {
			return value.valueArguments.map { body.expression(it.getArgumentExpression()!!) }
		}
		val reference = value as? KtNameReferenceExpression
		val names = reference?.let { with(unit.session) { it.resolveSymbol()?.psi } }?.let(locals::get)
		if (names != null) {
			return names.values.map(Expression::name)
		}
		if (isCall(value)) {
			return listOf(body.expression(value))
		}
		val type = with(unit.session) { value.expressionType?.expandedSymbol?.classId?.shortClassName }
		throw Rejection(value, "Several Lua values are returned as they are made: return $type(...), or the call that"
			+ " returns them.")
	}

	/** Whether a value is a constructor's call, `Span(low, high)`, which makes the values rather than a call's. */
	private fun isConstructor(value: KtExpression): Boolean = with(unit.session) {
		val call = value as? KtCallExpression ?: (value as? KtDotQualifiedExpression)?.selectorExpression
			as? KtCallExpression ?: return false
		call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol is KaConstructorSymbol
	}

	/**
	 * How the first component of a split local is read, `ground.height()` for the RS3 bindings' values or `span.low`
	 * for the plugin's, which an error names as an example.
	 */
	fun componentExample(reference: KtNameReferenceExpression): String? = with(unit.session) {
		val type = reference.expressionType
		val first = components(type)?.firstOrNull() ?: return null
		val name = reference.getReferencedName()
		if (type?.expandedSymbol?.let(unit::isPluginType) == true) "$name.$first" else "$name.$first()"
	}

	private fun isCall(value: KtExpression): Boolean =
		value is KtCallExpression || (value as? KtDotQualifiedExpression)?.selectorExpression is KtCallExpression

	/**
	 * The components the code after a local's declaration reads, in the block that holds it: `ground.height()` and
	 * `span.low`; every one when the local is returned whole.
	 */
	private fun readComponents(property: KtProperty): Set<String> {
		val read = mutableSetOf<String>()
		val scope = property.parent ?: return read
		for (reference in PsiTreeUtil.collectElementsOfType(scope, KtNameReferenceExpression::class.java)) {
			if (with(unit.session) { reference.resolveSymbol()?.psi } != property) {
				continue
			}
			val parent = reference.parent
			if (parent is KtReturnExpression) {
				return components(with(unit.session) { property.symbol.returnType })!!.toSet()
			}
			val selector = (parent as? KtDotQualifiedExpression)?.takeIf { it.receiverExpression == reference }
				?.selectorExpression
			when (selector) {
				is KtNameReferenceExpression -> read += selector.getReferencedName()
				is KtCallExpression -> (selector.calleeExpression as? KtNameReferenceExpression)
					?.getReferencedName()?.let(read::add)
				else -> {}
			}
		}
		return read
	}

	/** A component's local: its own name, or the local's name and its name where the function declares it already. */
	private fun componentLocal(property: KtProperty, component: String, taken: Set<String>): String {
		val scope = PsiTreeUtil.getParentOfType(property, KtDeclarationWithBody::class.java) ?: property.containingFile
		val declared = PsiTreeUtil.collectElementsOfType(scope, KtNamedDeclaration::class.java)
			.mapNotNull { it.name }.toSet() + locals.values.flatMap { it.values } + taken
		val preferred = unit.localName(property, component)
		if (preferred !in declared) {
			return preferred
		}
		val prefixed = property.name + component.replaceFirstChar { it.uppercaseChar() }
		var candidate = unit.names.luaName(prefixed)
		var number = 2
		while (candidate in declared) {
			candidate = unit.names.luaName(prefixed + number++)
		}
		return candidate
	}
}
