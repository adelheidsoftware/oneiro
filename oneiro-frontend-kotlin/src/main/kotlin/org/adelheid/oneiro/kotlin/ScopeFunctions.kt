package org.adelheid.oneiro.kotlin

import com.intellij.psi.util.PsiTreeUtil
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.Statement
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtReturnExpression
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
	 * A scope function as a statement of its own, or as a local's value, as block entries that keep the comments in
	 * its lambda; null for any other statement.
	 */
	fun entries(statement: KtExpression): List<Block.Entry>? {
		if (statement is KtProperty) {
			val scope = statement.initializer?.let(::scope) ?: return null
			return local(unit.localName(statement, statement.name!!), scope)
		}
		return scope(statement)?.let(::effect)
	}

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
	 * A scope function inside an expression: a lambda of one value, on a receiver the code names, read in place,
	 * `log(name.let { it.uppercase() })`. Anything else runs as statements, which an expression cannot hold.
	 */
	fun value(scope: Scope): Expression {
		val receiverName = stableName(scope.receiver)
		val single = scope.lambda.bodyExpression?.statements?.singleOrNull()
		if (!scope.givesLambdaValue || receiverName == null || single == null || single is KtProperty) {
			throw Rejection(scope.call, "In Lua the code of ${scope.function} runs as statements; give its value to a"
				+ " local, val x = ..., a return or an assignment of its own.")
		}
		bind(scope, receiverName)
		return body.expression(single)
	}

	/** A scope function whose value goes nowhere: the lambda's code on the receiver, in a block with its local. */
	private fun effect(scope: Scope): List<Block.Entry> {
		val receiverName = stableName(scope.receiver)
		if (receiverName != null) {
			bind(scope, receiverName)
			return placed(code(scope, null))
		}
		val name = holderName(scope)
		val holder = Block.Entry.of(local(name, body.expression(scope.receiver)))
		bind(scope, name)
		val code = code(scope, null)
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
	private fun bind(scope: Scope, name: String) {
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
			unit.alias(parameter ?: literal, name)
		} else {
			unit.aliasReceiver(literal, name)
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
		val type = with(unit.session) { scope.receiver.expressionType }
		val typeName = with(unit.session) { type?.expandedSymbol?.classId?.shortClassName?.asString() }
		val preferred = when {
			type != null && body.types.isString(type) -> "text"
			typeName != null -> typeName.replaceFirstChar { it.lowercaseChar() }
			else -> "value"
		}
		return unit.names.luaName(body.invent(preferred, scope.call))
	}

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
	}
}
