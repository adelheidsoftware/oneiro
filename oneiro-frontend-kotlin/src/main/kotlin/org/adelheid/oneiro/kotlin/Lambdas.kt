package org.adelheid.oneiro.kotlin

import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.FunctionBody
import org.adelheid.oneiro.lua.Statement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtThisExpression

/**
 * Lambdas and callable references as Lua functions. A lambda's parameters keep their names, `it` among them, and its
 * last expression is its value; a reference to a function of an object is that function, and one to a method of an
 * object is a small function that calls the method on it.
 */
internal class Lambdas(private val body: BodyLowering) {

	private val unit = body.unit

	/** `{ x -> x * 2 }` as `function(x) return x * 2 end`. */
	fun lambda(lambda: KtLambdaExpression): Expression = with(unit.session) {
		val literal = lambda.functionLiteral
		val symbol = literal.symbol
		for (parameter in literal.valueParameters) {
			if (parameter.destructuringDeclaration != null) {
				throw Rejection.notYet(parameter, "A lambda parameter that destructures")
			}
		}
		// The implicit it is a parameter too
		val names = symbol.valueParameters.map { unit.names.luaName(it.name.asString()) }.toMutableList()
		// Lua drops the arguments a function does not name, so unnamed parameters at the end are left out
		while (names.lastOrNull() == "_") {
			names.removeLast()
		}
		val code = literal.bodyBlockExpression ?: throw Rejection(lambda, "This lambda has no body.")
		val inner = body.nested()
		val block = when {
			code.statements.isEmpty() -> Block.of()
			symbol.returnType.isUnitType -> inner.block(code)
			else -> inner.block(code) { last -> inner.returned(last) }
		}
		Expression.FunctionExpression(FunctionBody.of(names, block))
	}

	/**
	 * A lambda's code taken in place, as the body of the loop a `forEach` or `removeAll` becomes.
	 *
	 * @property parameters the Lua names of the lambda's parameters, those it destructures among them
	 * @property value its one expression, which a predicate's loop tests; null when it has more
	 */
	class Inlined(val parameters: List<String>, val value: KtExpression?, lowered: () -> Block) {

		/** The lambda's statements, as the loop's body. */
		val code: Block by lazy(lowered)
	}

	/**
	 * A lambda's code to run in place, in the function it is written in, where a `return@label` would leave that
	 * function rather than the lambda, so the lambda must have none.
	 */
	fun inline(lambda: KtLambdaExpression, label: String): Inlined = with(unit.session) {
		val literal = lambda.functionLiteral
		val code = literal.bodyBlockExpression ?: throw Rejection(lambda, "This lambda has no body.")
		unit.inlinedLambdas += literal
		for (returned in PsiTreeUtil.collectElementsOfType(code, KtReturnExpression::class.java)) {
			val function = PsiTreeUtil.getParentOfType(returned, KtFunctionLiteral::class.java,
				KtNamedFunction::class.java)
			if (returned.getTargetLabel() != null && function == literal) {
				throw Rejection(returned, "Lua runs this lambda's code as the body of a loop, which return@$label"
					+ " cannot leave; write the loop as a for loop, and use continue.")
			}
		}
		val names = mutableListOf<String>()
		for (parameter in literal.valueParameters) {
			val destructured = parameter.destructuringDeclaration
			if (destructured != null) {
				destructured.entries.forEach { names += unit.names.luaName(it.name ?: "_") }
			} else {
				names += unit.names.luaName(parameter.name ?: "_")
			}
		}
		if (literal.valueParameters.isEmpty()) {
			// The implicit it
			literal.symbol.valueParameters.forEach { names += unit.names.luaName(it.name.asString()) }
		}
		Inlined(names, code.statements.singleOrNull()) { body.block(code) }
	}

	/**
	 * `::f`, `this::f`, `Type::f`, `::Type` and `Type::property` as Lua functions: a function of an object is the
	 * function itself, a method is a function that calls it on the object, and a constructor is the class's `new`.
	 */
	fun reference(reference: KtCallableReferenceExpression): Expression = with(unit.session) {
		val target = reference.callableReference.resolveSymbol()
			?: throw Rejection(reference, "This reference does not resolve to one function.")
		val receiver = reference.receiverExpression
		when (target) {
			is KaConstructorSymbol -> constructorReference(reference, target)
			is KaNamedFunctionSymbol -> functionReference(reference, target, receiver)
			is KaPropertySymbol -> propertyReference(reference, target, receiver)
			else -> throw Rejection.notYet(reference, "A reference to this")
		}
	}

	private fun constructorReference(reference: KtCallableReferenceExpression, constructor: KaConstructorSymbol):
		Expression = with(unit.session) {
		val type = constructor.containingClassId?.let { findClass(it) }
		if (type == null || !unit.isPluginType(type) || (type.psi as? KtClass)?.isPlainData() == true ||
			(type.psi as? KtClass)?.isInner() == true) {
			throw Rejection.notYet(reference, "A reference to this constructor")
		}
		Expression.field(unit.moduleReference(type), "new")
	}

	/**
	 * A function of an object, `::isEven` or `Functions::isEven`, is the function itself. A method bound to an object,
	 * `::tick`, `this::tick` or `ticker::tick`, is a function that calls it on that object, which must be a name, as
	 * Kotlin reads the object once, where the reference is. A method of no object, `Tile::describe`, takes the object
	 * first.
	 */
	private fun functionReference(reference: KtCallableReferenceExpression, function: KaNamedFunctionSymbol,
		receiver: KtExpression?): Expression = with(unit.session) {
		val owner = body.api.owner(function)
		if (owner == null || !unit.isPluginType(owner)) {
			throw Rejection.notYet(reference, "A reference to a function that is not the plugin's own")
		}
		val declaration = function.psi as? KtNamedFunction
		val isPrivate = declaration?.hasModifier(KtTokens.PRIVATE_KEYWORD) == true
		val parameters = function.valueParameters.map { unit.names.luaName(it.name.asString()) }
		val arguments = parameters.map(Expression::name)
		if (owner.classKind == KaClassKind.OBJECT || owner.classKind == KaClassKind.COMPANION_OBJECT) {
			return if (isPrivate) Expression.name(unit.names.luaName(function.name.asString()))
			else Expression.field(body.staticTable(owner), unit.memberName(function))
		}
		// A method of no object, Type::method, takes the object as its first parameter
		val unbound = receiver != null && receiver !is KtThisExpression && isTypeName(receiver)
		val objectName = if (unbound) decapitalized(owner) else null
		val target = when {
			unbound -> Expression.name(objectName!!)
			receiver == null || receiver is KtThisExpression -> body.receiverOrSelf(null, reference, owner)
			body.isStable(receiver) -> body.expression(receiver)
			else -> throw Rejection(receiver, "Kotlin reads this value once, where the reference is, but Lua's function"
				+ " would read it each time it runs; store it in a local first.")
		}
		val call = if (isPrivate) {
			Expression.Call(Expression.name(unit.names.luaName(function.name.asString())), listOf(target) + arguments)
		} else {
			Expression.MethodCall(target, unit.memberName(function), arguments)
		}
		function(listOfNotNull(objectName) + parameters, call, function)
	}

	/** `Tile::x` as `function(tile) return tile.x end`. */
	private fun propertyReference(reference: KtCallableReferenceExpression, property: KaPropertySymbol,
		receiver: KtExpression?): Expression = with(unit.session) {
		val owner = property.callableId?.classId?.let { findClass(it) }
		if (owner == null || !unit.isPluginType(owner) || receiver == null || !isTypeName(receiver)) {
			throw Rejection.notYet(reference, "A reference to this property")
		}
		val name = decapitalized(owner)
		val read = Expression.field(Expression.name(name), unit.memberName(property))
		Expression.FunctionExpression(FunctionBody.of(listOf(name), Block.of(Statement.Return(listOf(read)))))
	}

	/** A function that calls, and returns what the call gives unless the function returns nothing. */
	private fun function(parameters: List<String>, call: Expression, called: KaFunctionSymbol): Expression =
		with(unit.session) {
			val statement = if (called.returnType.isUnitType) Statement.CallStatement(call)
			else Statement.Return(listOf(call))
			Expression.FunctionExpression(FunctionBody.of(parameters, Block.of(statement)))
		}

	/** Whether the receiver of a reference names a type rather than a value. */
	private fun isTypeName(receiver: KtExpression): Boolean = with(unit.session) {
		val name = (receiver as? KtNameReferenceExpression) ?: return false
		name.resolveSymbol() is KaClassSymbol
	}

	/** The name of the object a function of no object takes first: `tile` for a `Tile`. */
	private fun decapitalized(type: KaClassSymbol): String {
		val name = type.classId?.shortClassName?.asString() ?: "value"
		return unit.names.luaName(name.replaceFirstChar { it.lowercaseChar() })
	}
}
