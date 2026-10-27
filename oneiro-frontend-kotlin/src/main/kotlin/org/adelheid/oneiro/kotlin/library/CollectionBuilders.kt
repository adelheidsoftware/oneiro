package org.adelheid.oneiro.kotlin.library

import org.adelheid.oneiro.kotlin.core.BodyLowering
import org.adelheid.oneiro.kotlin.core.Rejection
import org.adelheid.oneiro.lowering.translation.Helper
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.Statement
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtPsiUtil

/**
 * Kotlin's `buildList { ... }`, `buildSet { ... }` and `buildMap { ... }` as the statements that build the collection:
 * an empty table in a local, which the lambda's code fills, its `add`s and `put`s made on that local, as
 * `buildString`'s appends are made on its pieces.
 *
 * ```kotlin
 * val names = buildList {
 *     add("Guthix")
 *     addAll(others)
 * }
 * ```
 *
 * ```lua
 * local names = {}
 * names[#names + 1] = "Guthix"
 * table.move(others, 1, #others, #names + 1, names)
 * ```
 */
internal class CollectionBuilders(private val body: BodyLowering) {

	private val unit = body.unit

	/** The builder an expression calls, `buildList`, `buildSet` or `buildMap`; null for any other expression. */
	fun builder(expression: KtExpression): String? = with(unit.session) {
		val call = KtPsiUtil.safeDeparenthesize(expression) as? KtCallExpression ?: return null
		val id = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol?.callableId ?: return null
		val name = id.callableName.asString()
		if (id.packageName.asString() != "kotlin.collections" || id.classId != null || name !in BUILDERS) {
			return null
		}
		if (call.valueArguments.size != 1 || call.lambdaArguments.size != 1) {
			throw Rejection.notYet(call, "$name with a capacity")
		}
		name
	}

	/**
	 * The statements that build a builder's collection in a local of this name: `local names = {}`, or for a set or a
	 * map a helper `orderedTable()`, which keeps the order their elements come in, as Kotlin's do; then the lambda's
	 * code, whose `this` is the local.
	 */
	fun built(expression: KtExpression, name: String): List<Statement> {
		val builder = builder(expression) ?: throw IllegalStateException("no builder: ${expression.text}")
		val call = KtPsiUtil.safeDeparenthesize(expression) as KtCallExpression
		val lambda = call.lambdaArguments.single().getLambdaExpression() ?: throw Rejection(call, EMPTY)
		val literal = lambda.functionLiteral
		unit.inlinedLambdas += literal
		unit.aliasReceiver(literal, Expression.name(name))
		val empty = if (builder == "buildList") {
			Expression.Table(listOf())
		} else {
			unit.use(Helper.ORDERED_TABLE)
			Helper.ORDERED_TABLE.invoke()
		}
		val code = body.block(literal.bodyBlockExpression ?: throw Rejection(lambda, EMPTY))
		return listOf(Statement.Local(listOf(name), listOf(empty))) + code.entries().map { it.statement() }
	}

	/** The name of a local that holds a builder's collection, where nothing else names it: `list`, `set` or `map`. */
	fun holderName(builder: String): String = builder.removePrefix("build").lowercase()

	private companion object {

		const val EMPTY = "This expression is incomplete and does not compile."

		val BUILDERS = setOf("buildList", "buildSet", "buildMap")
	}
}
