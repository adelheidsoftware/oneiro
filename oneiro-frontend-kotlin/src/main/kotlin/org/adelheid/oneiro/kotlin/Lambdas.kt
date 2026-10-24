package org.adelheid.oneiro.kotlin

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.FunctionBody
import org.adelheid.oneiro.lua.Statement
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtExpressionWithLabel
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLoopExpression
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
	private val library = LibraryReferences(body)

	/** `{ x -> x * 2 }` as `function(x) return x * 2 end`. */
	fun lambda(lambda: KtLambdaExpression): Expression = with(unit.session) {
		val literal = lambda.functionLiteral
		val symbol = literal.symbol
		for (parameter in literal.valueParameters) {
			if (parameter.destructuringDeclaration != null) {
				throw Rejection.notYet(parameter, "A lambda parameter that destructures")
			}
		}
		// The implicit it is a parameter too, unless the code never reads it
		val names = when {
			literal.valueParameters.isNotEmpty() ->
				literal.valueParameters.map { unit.localName(it, it.name ?: "_") }.toMutableList()
			readsIt(literal) -> mutableListOf(unit.localName(literal, "it"))
			else -> mutableListOf()
		}
		// An event that passes nothing calls its callbacks without a value, so the payload parameter has no Lua form
		val event = symbol.valueParameters.singleOrNull()?.let { unit.eventWithoutPayload(it.returnType) }
		if (event != null) {
			val payload = literal.valueParameters.singleOrNull()
			if (payload?.let { body.uses(literal.bodyExpression, it) } ?: readsIt(literal)) {
				throw Rejection(payload ?: lambda, "The $event event passes nothing, so its payload has nothing to"
					+ " read; leave ${payload?.name ?: "it"} unused.")
			}
			names.clear()
		}
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

	/** Whether a lambda's code reads the implicit `it` it declares. */
	private fun readsIt(literal: KtFunctionLiteral): Boolean = with(unit.session) {
		PsiTreeUtil.collectElementsOfType(literal, KtNameReferenceExpression::class.java).any { reference ->
			val symbol = reference.takeIf { it.getReferencedName() == "it" }?.resolveSymbol()
			symbol != null && body.declarationOf(symbol, reference) == literal
		}
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
	 * function rather than the lambda, so the lambda must have none. Nor may it `break` or `continue` a loop around
	 * it, which Lua would take for the loop its code runs in.
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
		for (jump in PsiTreeUtil.collectElementsOfType(code, KtExpressionWithLabel::class.java)) {
			val outside = jump is KtBreakExpression || jump is KtContinueExpression
			if (outside && PsiTreeUtil.getParentOfType(jump, KtLoopExpression::class.java, KtFunction::class.java) ==
				literal) {
				throw Rejection(jump, "Lua runs this lambda's code as the body of a loop of its own, which this"
					+ " ${jump.text} would leave instead of the loop around the lambda; write the lambda's loop as a"
					+ " for loop.")
			}
		}
		val names = mutableListOf<String>()
		for (parameter in literal.valueParameters) {
			val destructured = parameter.destructuringDeclaration
			if (destructured != null) {
				destructured.entries.forEach { names += unit.localName(it, it.name ?: "_") }
			} else {
				names += unit.localName(parameter, parameter.name ?: "_")
			}
		}
		if (literal.valueParameters.isEmpty()) {
			// The implicit it
			literal.symbol.valueParameters.forEach { names += unit.localName(it.psi ?: literal, it.name.asString()) }
		}
		Inlined(names, code.statements.singleOrNull()) { body.block(code) }
	}

	/** A lambda's parameter: the declaration its name belongs to, and the name. */
	class Parameter(val declaration: PsiElement, val name: String)

	/** The one parameter of a lambda that takes one value: its own, or the implicit `it`, which the lambda declares. */
	fun parameter(lambda: KtLambdaExpression): Parameter {
		val literal = lambda.functionLiteral
		val parameter = literal.valueParameters.singleOrNull()
		if (parameter?.destructuringDeclaration != null) {
			throw Rejection.notYet(parameter, "A lambda parameter that destructures")
		}
		if (parameter == null && literal.valueParameters.isNotEmpty()) {
			throw Rejection.notYet(lambda, "A lambda of more than one parameter here")
		}
		return Parameter(parameter ?: literal, parameter?.name ?: "it")
	}

	/**
	 * A lambda's one expression, which Lua reads in place.
	 *
	 * @param reason why it must be one expression, and how to write it as one
	 */
	fun value(lambda: KtLambdaExpression, reason: String): KtExpression {
		val value = lambda.bodyExpression?.statements?.singleOrNull()
		if (value == null || value is KtDeclaration) {
			throw Rejection(lambda, reason)
		}
		return value
	}

	/**
	 * `::f`, `this::f`, `Type::f`, `::Type` and `Type::property` as Lua functions: a function of an object is the
	 * function itself, a method is a function that calls it on the object, and a constructor is the class's `new`. A
	 * reference to the library is a function of its Lua form; see [LibraryReferences].
	 */
	fun reference(reference: KtCallableReferenceExpression): Expression = with(unit.session) {
		val target = reference.callableReference.resolveSymbol()
			?: throw Rejection(reference, "This reference does not resolve to one function.")
		val receiver = reference.receiverExpression
		when (target) {
			is KaConstructorSymbol -> constructorReference(reference, target)
			// ::Seed names the class, whose constructor it calls
			is KaClassSymbol -> constructorReference(reference, target.declaredMemberScope.constructors
				.firstOrNull { it.isPrimary } ?: throw Rejection.notYet(reference, "A reference to this constructor"))
			is KaNamedFunctionSymbol -> functionReference(reference, target, receiver)
			is KaPropertySymbol -> propertyReference(reference, target, receiver)
			else -> throw Rejection.notYet(reference, "A reference to this")
		}
	}

	private fun constructorReference(reference: KtCallableReferenceExpression, constructor: KaConstructorSymbol):
		Expression = with(unit.session) {
		val type = constructor.containingClassId?.let { findClass(it) }
		val declaration = type?.psi as? KtClass
		if (type == null || !unit.isPluginType(type) || declaration?.isInner() == true) {
			throw Rejection.notYet(reference, "A reference to this constructor")
		}
		if (declaration?.isPlainData() == true) {
			// A data class of properties alone is a table of them
			val parameters = constructor.valueParameters.map { unit.localName(reference, it.name.asString()) }
			val fields = constructor.valueParameters.zip(parameters).map { (parameter, name) ->
				val property = parameter.generatedPrimaryConstructorProperty
				Expression.TableField.Named(property?.let(unit::memberName) ?: parameter.name.asString(),
					Expression.name(name))
			}
			return Expression.FunctionExpression(FunctionBody.of(parameters,
				Block.of(Statement.Return(listOf(Expression.Table(fields))))))
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
		if (owner != null && body.api.isApi(owner)) {
			throw Rejection.notYet(reference, "A reference to a function of the client")
		}
		if (owner == null || !unit.isPluginType(owner)) {
			return library.function(reference, function, receiver)
		}
		val declaration = function.psi as? KtNamedFunction
		val isPrivate = declaration?.hasModifier(KtTokens.PRIVATE_KEYWORD) == true
		// The function's parameters, named where the reference is, and apart from the object it calls the function on
		val receiverName = (receiver as? KtNameReferenceExpression)?.getReferencedName()?.let(unit.names::luaName)
		val parameters = function.valueParameters.map {
			val own = unit.localName(reference, it.name.asString())
			if (own == receiverName) body.invent(own + "Value", reference) else own
		}
		val arguments = parameters.map(Expression::name)
		if (owner.classKind == KaClassKind.OBJECT || owner.classKind == KaClassKind.COMPANION_OBJECT) {
			return if (isPrivate) Expression.name(unit.names.luaName(function.name.asString()))
			else Expression.field(body.staticTable(owner), unit.memberName(function))
		}
		// A method of no object, Type::method, takes the object as its first parameter
		val unbound = receiver != null && receiver !is KtThisExpression && isTypeName(receiver)
		val objectName = if (unbound) decapitalized(owner, reference) else null
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
		if (owner == null || !unit.isPluginType(owner) && !body.api.isApi(owner)) {
			return library.property(reference, property, receiver)
		}
		if (!unit.isPluginType(owner) || receiver == null || !isTypeName(receiver)) {
			throw Rejection.notYet(reference, "A reference to this property")
		}
		val name = decapitalized(owner, reference)
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
	private fun decapitalized(type: KaClassSymbol, reference: KtCallableReferenceExpression): String {
		val name = type.classId?.shortClassName?.asString() ?: "value"
		return unit.localName(reference, name.replaceFirstChar { it.lowercaseChar() })
	}
}
