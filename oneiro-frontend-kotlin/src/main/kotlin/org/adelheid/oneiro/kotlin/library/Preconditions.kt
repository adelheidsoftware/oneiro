package org.adelheid.oneiro.kotlin.library

import org.adelheid.oneiro.kotlin.core.BodyLowering
import org.adelheid.oneiro.kotlin.core.Rejection
import org.adelheid.oneiro.lua.BinaryOperator
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.Statement
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression

/**
 * Kotlin's preconditions, each of which raises Lua's `error` when it fails: `require(condition)` and
 * `check(condition)`, and `requireNotNull(value)` and `checkNotNull(value)`, which fail on a null value and give the
 * value otherwise. The error's message is the one the call's lambda gives, or without one, Kotlin's own.
 */
internal class Preconditions(private val body: BodyLowering) {

	private val unit = body.unit

	/** Whether an expression is `require(condition)` or `check(condition)`. */
	fun isCondition(expression: KtExpression): Boolean = kind(expression) in CONDITIONS

	/** Whether an expression is `requireNotNull(value)` or `checkNotNull(value)`. */
	fun isNotNull(expression: KtExpression): Boolean = kind(expression) in NOT_NULL

	/** `require(condition) { message }` and `check(condition)` as `if not condition then error(message) end`. */
	fun condition(call: KtCallExpression): Statement =
		Statement.ifThen(body.operators.negation(body.expression(argument(call))), Block.of(raise(call)))

	/** `requireNotNull(value)` as a statement of its own: `if value == nil then error(message) end`. */
	fun notNullStatement(call: KtCallExpression): Statement =
		Statement.ifThen(absent(body.expression(argument(call))), Block.of(raise(call)))

	/** `val x = requireNotNull(value)` as `local x = value`, then `if x == nil then error(message) end`. */
	fun notNullLocal(name: String, call: KtCallExpression): List<Statement> = listOf(
		Statement.Local(listOf(name), listOf(body.expression(argument(call)))),
		Statement.ifThen(absent(Expression.name(name)), Block.of(raise(call))))

	/**
	 * `requireNotNull(value)` anywhere else, as `value or error(message)`. Lua's `or` would fail a false too, so a
	 * `Boolean` takes a local of its own.
	 */
	fun notNullValue(call: KtCallExpression): Expression {
		val value = argument(call)
		val type = with(unit.session) { value.expressionType }
		if (type == null || body.types.isBoolean(type)) {
			throw Rejection(call, FALSE_IS_NOT_NULL)
		}
		return Expression.binary(body.expression(value), BinaryOperator.OR, error(call))
	}

	/** The name of the precondition an expression calls, or null when it calls none. */
	private fun kind(expression: KtExpression): String? = with(unit.session) {
		val call = expression as? KtCallExpression ?: return null
		val id = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol?.callableId ?: return null
		val name = id.callableName.asString()
		if (id.packageName.asString() == "kotlin" && id.classId == null && name in MESSAGES) name else null
	}

	/** The condition or the value a precondition tests, its first argument. */
	private fun argument(call: KtCallExpression): KtExpression =
		call.valueArguments.firstOrNull()?.getArgumentExpression() ?: throw Rejection(call, EMPTY)

	private fun absent(value: Expression): Expression = Expression.binary(value, BinaryOperator.EQUAL, Expression.Nil())

	private fun raise(call: KtCallExpression): Statement = Statement.CallStatement(error(call))

	/** `error(message)`, with the message the lambda gives, or Kotlin's own for the precondition. */
	private fun error(call: KtCallExpression): Expression {
		val lambda = call.valueArguments.getOrNull(1)?.getArgumentExpression() as? KtLambdaExpression
		val message = if (lambda != null) {
			body.strings.asText(body.lambdas.value(lambda, "A message becomes the value of Lua's error; write it as one"
				+ " expression, { \"...\" }."))
		} else {
			Expression.string(MESSAGES.getValue(kind(call)!!))
		}
		unit.useGlobal("error")
		return Expression.call(Expression.name("error"), message)
	}

	companion object {

		/** Kotlin's preconditions, with the message each throws when the call gives none. */
		private val MESSAGES = mapOf("require" to "Failed requirement.", "check" to "Check failed.",
			"requireNotNull" to "Required value was null.", "checkNotNull" to "Required value was null.")
		private val CONDITIONS = setOf("require", "check")
		private val NOT_NULL = setOf("requireNotNull", "checkNotNull")

		/** Why a `Boolean` that must not be null needs a local of its own to be tested for nil. */
		const val FALSE_IS_NOT_NULL = "Lua's or would fail a false here as well as a nil; give the value to a local" +
			" of its own, val x = ..., which Oneiro tests for nil alone."
		private const val EMPTY = "This expression is incomplete and does not compile."
	}
}
