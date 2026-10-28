package org.adelheid.oneiro.kotlin.library

import com.intellij.psi.util.PsiTreeUtil
import org.adelheid.oneiro.kotlin.core.BodyLowering
import org.adelheid.oneiro.kotlin.core.Rejection
import org.adelheid.oneiro.lua.BinaryOperator
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.Statement
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtThisExpression

/**
 * Kotlin's scope functions, opened where they are called, as a person writes the same code in Lua: `let` and `also`
 * run their lambda's code with the receiver as its parameter, and `apply`, `run` and `with` with the receiver as
 * `this`, which members named without a receiver are read on.
 *
 * ```kotlin
 * val label = ui.Text(parent).apply {
 *     SetPos(10, 20)
 *     font = id.Font.RS3_P12_FULL
 * }
 * ```
 *
 * ```lua
 * local label = ui.Text.new(parent)
 * label:SetPos(10, 20)
 * label.font = id.Font.P12_FULL
 * ```
 *
 * The receiver keeps its own name when it is a parameter, a `val` or `this`, which the code cannot change; otherwise a
 * local takes it, the one the call's value goes to where there is one. The call's value is the lambda's last
 * expression, or for `apply` and `also`, the receiver. Code that declares locals of its own runs in a `do` block, so
 * that they end with it.
 *
 * `takeIf { ... }` and `takeUnless` give their receiver where the test passes, and fails, and nil otherwise:
 * `level > 0 and level or nil` on a receiver the code names, and a local that the test empties otherwise.
 *
 * On `receiver?.`, the lambda's code runs when the receiver is not nil:
 *
 * ```kotlin
 * ShapeList.CreateEntity("herbs.patch")?.apply { coordGrid = tile }
 * ```
 *
 * ```lua
 * local shapeEntity = ShapeList.CreateEntity("herbs.patch")
 * if shapeEntity ~= nil then
 *     shapeEntity.coordGrid = tile
 * end
 * ```
 */
internal class ScopeFunctions(private val body: BodyLowering) {

	private val unit = body.unit

	/** A call of a scope function: its name, its receiver, its lambda, and the whole call. */
	class Scope(
		val function: String,
		val receiver: KtExpression,
		val lambda: KtLambdaExpression,
		val call: KtExpression,
	) {

		/** Whether the call's value is the lambda's, as for `let`, `run` and `with`, rather than the receiver. */
		val givesLambdaValue: Boolean get() = function in LAMBDA_VALUE

		/** Whether the lambda takes the receiver as its parameter, as `let`'s and `also`'s do, not as `this`. */
		val passesReceiver: Boolean get() = function in PASSES_RECEIVER
	}

	/** The scope function an expression calls, or null for any other expression. */
	fun scope(expression: KtExpression): Scope? = when (val plain = KtPsiUtil.safeDeparenthesize(expression)) {
		is KtDotQualifiedExpression ->
			(plain.selectorExpression as? KtCallExpression)?.let { scope(it, plain.receiverExpression, plain) }
		is KtCallExpression -> scope(plain, null, plain)
		else -> null
	}

	/**
	 * The scope function a call is, on the receiver before its dot, or null for any other call.
	 *
	 * @param whole the whole expression, `receiver.let { ... }`, where a problem with it is reported
	 */
	fun scope(call: KtCallExpression, receiver: KtExpression?, whole: KtExpression): Scope? = with(unit.session) {
		val id = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol?.callableId ?: return null
		val name = id.callableName.asString()
		if (id.packageName.asString() != "kotlin" || id.classId != null || name !in FUNCTIONS) {
			return null
		}
		val arguments = call.valueArguments.mapNotNull { it.getArgumentExpression() }
		val lambda = arguments.lastOrNull() as? KtLambdaExpression ?: return null
		val scoped = when {
			receiver != null && name != "with" -> receiver
			// run { ... } without a receiver is a block of its own, which BodyLowering makes a do block
			receiver == null && name == "with" && arguments.size == 2 -> arguments[0]
			else -> return null
		}
		Scope(name, scoped, lambda, whole)
	}

	/**
	 * A call of `takeIf` or `takeUnless`: its name, its receiver, its lambda, and the whole call.
	 */
	class Test(val function: String, val receiver: KtExpression, val lambda: KtLambdaExpression, val call: KtExpression)

	/** The `takeIf` or `takeUnless` an expression calls, or null for any other expression. */
	fun test(expression: KtExpression): Test? {
		val plain = KtPsiUtil.safeDeparenthesize(expression) as? KtDotQualifiedExpression ?: return null
		val call = plain.selectorExpression as? KtCallExpression ?: return null
		return test(call, plain.receiverExpression, plain)
	}

	/** The `takeIf` or `takeUnless` a call is, on the receiver before its dot, or null for any other call. */
	fun test(call: KtCallExpression, receiver: KtExpression?, whole: KtExpression): Test? = with(unit.session) {
		val id = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol?.callableId ?: return null
		val name = id.callableName.asString()
		if (id.packageName.asString() != "kotlin" || id.classId != null || name !in TESTS || receiver == null) {
			return null
		}
		val predicate = call.valueArguments.singleOrNull()?.getArgumentExpression()?.let(KtPsiUtil::safeDeparenthesize)
		val lambda = predicate as? KtLambdaExpression
			?: throw Rejection.notYet(whole, "$name with a function reference")
		Test(name, receiver, lambda, whole)
	}

	/**
	 * `takeIf { ... }` or `takeUnless` inside an expression, on a receiver the code names: the test, and the receiver,
	 * `level > 0 and level or nil`. Null for a receiver that is no name, which Lua would read twice, and for a
	 * `Boolean`, whose false `and` would turn into nil; those go into a local first, as [testLocal] makes.
	 */
	fun testValue(test: Test): Expression? {
		val name = stableName(test.receiver) ?: return null
		if (with(unit.session) { test.receiver.expressionType }?.let(body.types::isBoolean) != false) {
			return null
		}
		return Expression.binary(Expression.binary(kept(test, name), BinaryOperator.AND, Expression.name(name)),
			BinaryOperator.OR, Expression.Nil())
	}

	/**
	 * `val x = receiver.takeIf { ... }`: the value [testValue] gives, where it can; otherwise the local takes the
	 * receiver, and then nil where the test fails, `if not (x > 0) then x = nil end`, or for `takeUnless`, passes.
	 */
	fun testLocal(name: String, test: Test): List<Statement> {
		testValue(test)?.let { return listOf(local(name, it)) }
		val holder = local(name, body.expression(test.receiver))
		val emptied = Block.of(Statement.assignment(Expression.name(name), Expression.Nil()))
		return listOf(holder, Statement.ifThen(body.operators.negation(kept(test, name)), emptied))
	}

	/**
	 * The test that keeps the receiver, under the name given, which the lambda's parameter stands for: the lambda's
	 * for `takeIf`, its negation for `takeUnless`.
	 */
	private fun kept(test: Test, name: String): Expression {
		val condition = body.lambdas.value(test.lambda, "In Lua the test of ${test.function} becomes a condition;"
			+ " write it as one expression, { it > 0 }.")
		unit.alias(body.lambdas.parameter(test.lambda).declaration, name)
		val passed = body.expression(condition)
		return if (test.function == "takeIf") passed else body.operators.negation(passed)
	}

	/** The scope function a call on `receiver?.` is, as [scope] finds one on `receiver.`; null for any other. */
	fun safeScope(safe: KtSafeQualifiedExpression): Scope? =
		(safe.selectorExpression as? KtCallExpression)?.let { scope(it, safe.receiverExpression, safe) }

	/**
	 * A scope function as a statement of its own, or as a local's value, as block entries that keep the comments in
	 * its lambda; null for any other statement.
	 *
	 * @param endsFunction whether the statement is the last of a function's body, whose locals end with it anyway
	 */
	fun entries(statement: KtExpression, endsFunction: Boolean = false): List<Block.Entry>? {
		if (statement is KtProperty) {
			val initializer = statement.initializer
			val safe = (initializer as? KtSafeQualifiedExpression)?.let(::safeScope)
			if (safe != null && !safe.givesLambdaValue) {
				return safeLocal(unit.localName(statement, statement.name!!), safe).map(Block.Entry::of)
			}
			val scope = initializer?.let(::scope) ?: return null
			return local(unit.localName(statement, statement.name!!), scope)
		}
		return scope(statement)?.let { effect(it, endsFunction) }
	}

	/**
	 * A scope function on `receiver?.` whose value goes nowhere: the lambda's code when the receiver is not nil. A
	 * receiver the code does not name is read once, into a local; `let`'s and `also`'s always goes into a local of
	 * their parameter's name, as a person names the value they test.
	 */
	fun safeEffect(scope: Scope): List<Statement> {
		val receiverName = if (scope.passesReceiver) null else stableName(scope.receiver)
		val name = receiverName ?: holderName(scope)
		val holder = if (receiverName == null) listOf(local(name, body.expression(scope.receiver))) else listOf()
		bind(scope, name)
		return holder + Statement.ifThen(present(name), code(scope, null))
	}

	/**
	 * `val x = receiver?.apply { ... }` and `also`: the local takes the receiver, nil or not, and the lambda's code
	 * works on it when it is not nil.
	 */
	private fun safeLocal(name: String, scope: Scope): List<Statement> {
		val holder = local(name, body.expression(scope.receiver))
		bind(scope, name)
		return listOf(holder, Statement.ifThen(present(name), code(scope, null)))
	}

	private fun present(name: String): Expression =
		Expression.binary(Expression.name(name), BinaryOperator.NOT_EQUAL, Expression.Nil())

	/**
	 * A scope function whose value goes to the sink, a return or an assignment, as statements; for one whose value
	 * goes nowhere, a null sink. The local that keeps a receiver goes in a block of its own, unless the sink returns,
	 * after which nothing could see it.
	 */
	fun statements(scope: Scope, sink: ((Expression) -> Statement)?, returns: Boolean = false): List<Statement> {
		if (sink == null) {
			val entries = effect(scope)
			return if (entries.any(::isCommented)) listOf(Statement.Do(Block(entries, listOf())))
			else entries.map { it.statement() }
		}
		val receiverName = stableName(scope.receiver)
		val name = receiverName ?: holderName(scope)
		val holder = if (receiverName == null) listOf(Block.Entry.of(local(name, body.expression(scope.receiver))))
		else listOf()
		bind(scope, name)
		val code = if (scope.givesLambdaValue) {
			code(scope, sink)
		} else {
			val code = code(scope, null)
			Block(code.entries() + Block.Entry.of(sink(Expression.name(name))), code.closingComments())
		}
		val block = Block(holder + code.entries(), code.closingComments())
		val ownBlock = !returns && (holder.isNotEmpty() || declaresLocals(block)) || isCommented(block)
		return if (ownBlock) listOf(Statement.Do(block)) else block.entries().map { it.statement() }
	}

	/**
	 * A scope function inside an expression. A lambda of one value is read in place,
	 * `log(name.let { it.uppercase() })`, on a receiver the code names, or on a local that takes the receiver before
	 * the statement, `local text = "herbs"` for `"herbs".let { it.length }`. The code of any other runs before the
	 * statement, with the local the value goes in, unless it is `apply` or `also` on a receiver the code names, whose
	 * value is that name; see [BodyLowering.hoisted].
	 */
	fun value(scope: Scope): Expression {
		val what = BodyLowering.STATEMENTS_FIRST.format(scope.function)
		val receiverName = stableName(scope.receiver)
		val single = singleValue(scope)
		if (scope.givesLambdaValue && single != null) {
			val name = receiverName ?: body.hoistedInto(scope.receiver, receiverHolderName(scope), what) { holder ->
				listOf(Block.Entry.of(local(holder, body.expression(scope.receiver))))
			}.name()
			bind(scope, name)
			return body.expression(single)
		}
		if (!scope.givesLambdaValue && receiverName != null) {
			body.hoisted(scope.call, what) {
				bind(scope, receiverName)
				placed(code(scope, null))
			}
			return Expression.name(receiverName)
		}
		val preferred = if (scope.givesLambdaValue) body.hoistedName(scope.call) else receiverHolderName(scope)
		return body.hoistedInto(scope.call, preferred, what) { name -> local(name, scope) }
	}

	/**
	 * Whether a scope function inside an expression is read in place, with no code before the statement: a lambda of
	 * one value, on a receiver the code names.
	 */
	fun readsInPlace(scope: Scope): Boolean =
		scope.givesLambdaValue && singleValue(scope) != null && stableName(scope.receiver) != null

	/** The one expression of a scope function's lambda, which gives its value; null for a lambda of more. */
	private fun singleValue(scope: Scope): KtExpression? =
		scope.lambda.bodyExpression?.statements?.singleOrNull()?.takeIf { it !is KtDeclaration }

	/**
	 * `target = receiver.apply { ... }`, and a class's property of that value: the target, a name or a property, takes
	 * the receiver, and the lambda's code works on it there, `self.box:SetSize(110, 32)`. Null for a scope function
	 * that gives its lambda's value, and for `also` on a target that is no name, which its parameter could stand for.
	 */
	fun assigned(scope: Scope, target: Expression): List<Block.Entry>? {
		if (scope.givesLambdaValue || scope.passesReceiver && target !is Expression.Name) {
			return null
		}
		val first = Block.Entry.of(Statement.assignment(target, body.expression(scope.receiver)))
		bind(scope, target)
		return listOf(first) + placed(code(scope, null))
	}

	/**
	 * A scope function whose value goes nowhere: the lambda's code on the receiver, in a block with its local, unless
	 * it ends a function's body, after which nothing could see the local.
	 */
	private fun effect(scope: Scope, endsFunction: Boolean = false): List<Block.Entry> {
		val receiverName = stableName(scope.receiver)
		if (receiverName != null) {
			bind(scope, receiverName)
			return placed(code(scope, null))
		}
		val name = holderName(scope)
		val holder = Block.Entry.of(local(name, body.expression(scope.receiver)))
		bind(scope, name)
		val code = code(scope, null)
		if (endsFunction && code.closingComments().isEmpty()) {
			return listOf(holder) + code.entries()
		}
		return listOf(Block.Entry.of(Statement.Do(Block(listOf(holder) + code.entries(), code.closingComments()))))
	}

	/**
	 * `val x = receiver.apply { ... }` and its kin: the local takes the receiver, which the lambda's code works on, and
	 * then, for `let`, `run` and `with`, the lambda's value. A lambda of one value on a receiver the code names is the
	 * local's value itself: `val loud = name.let { it.uppercase() }` is `local loud = name:upper()`.
	 */
	private fun local(name: String, scope: Scope): List<Block.Entry> {
		val receiverName = stableName(scope.receiver)
		if (!scope.givesLambdaValue) {
			// apply and also give the receiver, which the local takes first
			val holder = Block.Entry.of(local(name, body.expression(scope.receiver)))
			bind(scope, name)
			return listOf(holder) + placed(code(scope, null))
		}
		val assign = { value: Expression -> Statement.assignment(Expression.name(name), value) }
		val declared = Block.Entry.of(Statement.Local(listOf(name), listOf()))
		if (receiverName == null) {
			// The receiver in a local of its own, in a block that hands the lambda's value to the local
			val holderName = holderName(scope)
			val holder = Block.Entry.of(local(holderName, body.expression(scope.receiver)))
			bind(scope, holderName)
			val code = code(scope, assign)
			return listOf(declared, Block.Entry.of(Statement.Do(Block(listOf(holder) + code.entries(),
				code.closingComments()))))
		}
		bind(scope, receiverName)
		val code = code(scope, assign)
		val only = code.entries().singleOrNull()
		val value = (only?.statement() as? Statement.Assignment)?.values()?.singleOrNull()
		if (only != null && value != null && code.closingComments().isEmpty()) {
			return listOf(Block.Entry(local(name, value), only.trivia(), only.origin()))
		}
		return listOf(declared) + placed(code)
	}

	/** The lambda's code, its last expression handed to the sink when there is one. */
	private fun code(scope: Scope, sink: ((Expression) -> Statement)?): Block {
		val code = scope.lambda.bodyExpression ?: throw Rejection(scope.lambda, "This lambda has no body.")
		return if (sink == null) body.block(code) else body.block(code) { last -> body.handedOn(last, sink) }
	}

	/**
	 * Makes the lambda's code work on the receiver under the name given: the parameter of `let`'s and `also`'s lambda
	 * stands for it, and so does the `this` of the others'. The code runs in place, which a `return@let` cannot leave
	 * early.
	 */
	private fun bind(scope: Scope, name: String) = bind(scope, Expression.name(name))

	/** As [bind] with a name, for a receiver that stands for a property, which only `this` can: `self.box`. */
	private fun bind(scope: Scope, receiver: Expression) {
		val literal = scope.lambda.functionLiteral
		unit.inlinedLambdas += literal
		for (returned in PsiTreeUtil.collectElementsOfType(literal, KtReturnExpression::class.java)) {
			val function = PsiTreeUtil.getParentOfType(returned, KtFunctionLiteral::class.java,
				KtNamedFunction::class.java)
			if (returned.getTargetLabel() != null && function == literal) {
				throw Rejection(returned, "Lua runs the code of ${scope.function} in place, where a return@label would"
					+ " leave the function around it; give the lambda its value at its end, using if where it differs.")
			}
		}
		if (scope.passesReceiver) {
			val parameter = literal.valueParameters.singleOrNull()
			if (parameter?.destructuringDeclaration != null) {
				throw Rejection.notYet(parameter, "A lambda parameter that destructures")
			}
			unit.alias(parameter ?: literal, (receiver as Expression.Name).name())
		} else {
			unit.aliasReceiver(literal, receiver)
		}
	}

	/**
	 * The Lua name of a receiver the lambda can use as it is: a parameter, a `val` local or `this`, none of which the
	 * code can change; null for anything else.
	 */
	private fun stableName(receiver: KtExpression): String? = with(unit.session) {
		val plain = KtPsiUtil.safeDeparenthesize(receiver)
		val unchanging = when (plain) {
			is KtThisExpression -> true
			is KtNameReferenceExpression -> when (val symbol = plain.resolveSymbol()) {
				is KaValueParameterSymbol -> true
				is KaLocalVariableSymbol -> symbol.isVal
				else -> false
			}
			else -> false
		}
		if (unchanging) (body.expression(plain) as? Expression.Name)?.name() else null
	}

	/**
	 * The name of a local that keeps the receiver: the lambda's parameter's, or for `this`, one made from the
	 * receiver's type, `text` for a `ui.Text`.
	 */
	private fun holderName(scope: Scope): String {
		if (scope.passesReceiver) {
			val parameter = body.lambdas.parameter(scope.lambda)
			return unit.localName(parameter.declaration, parameter.name)
		}
		return unit.names.luaName(body.invent(body.typeName(type(scope.receiver)), scope.call))
	}

	/**
	 * The name of a local that keeps the receiver before the statement, which the code around reads too: the name
	 * the lambda gives its parameter, otherwise one made from the receiver's type, `text` for a string.
	 */
	private fun receiverHolderName(scope: Scope): String {
		val parameter = if (scope.passesReceiver) scope.lambda.functionLiteral.valueParameters.singleOrNull() else null
		return parameter?.name?.takeIf { it != "_" }?.let { unit.localName(parameter, it) }
			?: body.typeName(type(scope.receiver))
	}

	private fun type(expression: KtExpression): KaType? = with(unit.session) { expression.expressionType }

	/** The lambda's code among the statements around it, or in a `do` block where its locals or comments need one. */
	private fun placed(code: Block): List<Block.Entry> =
		if (declaresLocals(code) || code.closingComments().isNotEmpty()) listOf(Block.Entry.of(Statement.Do(code)))
		else code.entries()

	private fun declaresLocals(block: Block): Boolean =
		block.entries().any { it.statement() is Statement.Local || it.statement() is Statement.LocalFunction }

	private fun isCommented(block: Block): Boolean =
		block.closingComments().isNotEmpty() || block.entries().any(::isCommented)

	private fun isCommented(entry: Block.Entry): Boolean =
		entry.trivia().leading().isNotEmpty() || entry.trivia().trailing() != null

	private fun local(name: String, value: Expression): Statement = Statement.Local(listOf(name), listOf(value))

	private companion object {

		val FUNCTIONS = setOf("let", "also", "apply", "run", "with")
		val LAMBDA_VALUE = setOf("let", "run", "with")
		val PASSES_RECEIVER = setOf("let", "also")
		val TESTS = setOf("takeIf", "takeUnless")
	}
}
