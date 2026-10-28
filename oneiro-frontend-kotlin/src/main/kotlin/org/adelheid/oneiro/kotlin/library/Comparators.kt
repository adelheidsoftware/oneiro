package org.adelheid.oneiro.kotlin.library

import com.intellij.psi.PsiElement
import org.adelheid.oneiro.kotlin.core.BodyLowering
import org.adelheid.oneiro.kotlin.core.Rejection
import org.adelheid.oneiro.lowering.translation.Inlining
import org.adelheid.oneiro.lua.BinaryOperator
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.FunctionBody
import org.adelheid.oneiro.lua.Statement
import org.adelheid.oneiro.lua.UnaryOperator
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtReturnExpression

/**
 * Kotlin's comparators as the Lua test of whether one value comes before another, which `table.sort` takes and a
 * loop that finds a minimum compares with, as in the Java front end: `compareBy { it.level }` is `a.level < b.level`,
 * `{ a, b -> b.length - a.length }` is `#b - #a < 0`, and `x.compareTo(y)` of numbers or strings is `x < y`. Any other
 * comparator is called on the two.
 */
internal class Comparators(private val body: BodyLowering) {

	private val unit = body.unit

	/** The comparator as `table.sort`'s function; a comparator lambda's own parameters name the function's. */
	fun lessThan(comparator: KtExpression): Expression {
		val own = opened(KtPsiUtil.safeDeparenthesize(comparator))?.first
		val names = own?.map { unit.localName(it, it.name ?: "_") } ?: compared(comparator)
		return function(names, comesBefore(comparator, names[0], names[1]))
	}

	/** `table.sort`'s function that orders by a key, as `sortBy { it.level }` does, or the other way round. */
	fun byKey(selector: KtExpression, descending: Boolean): Expression {
		val (a, b) = compared(selector)
		return function(listOf(a, b), if (descending) keyBefore(selector, b, a) else keyBefore(selector, a, b))
	}

	/** `table.sort`'s function for the reverse of natural order: `function(a, b) return b < a end`. */
	fun reversed(where: PsiElement): Expression {
		val (a, b) = compared(where)
		return function(listOf(a, b), less(b, a))
	}

	/** Whether the value named [a] comes before the one named [b]; a null comparator is natural order, `a < b`. */
	fun comesBefore(comparator: KtExpression?, a: String, b: String): Expression = with(unit.session) {
		val inner = comparator?.let(KtPsiUtil::safeDeparenthesize) ?: return less(a, b)
		val call = inner as? KtCallExpression ?: (inner as? KtDotQualifiedExpression)?.selectorExpression
			as? KtCallExpression
		val function = call?.resolveToCall()?.successfulFunctionCallOrNull()?.symbol
		if (call != null && function != null && isComparatorFunction(function)) {
			val arguments = call.valueArguments.mapNotNull { it.getArgumentExpression() }
			val receiver = (inner as? KtDotQualifiedExpression)?.receiverExpression
			when (val name = function.callableId?.callableName?.asString()) {
				"naturalOrder" -> return less(a, b)
				"reverseOrder" -> return if (arguments.isEmpty()) less(b, a) else comesBefore(arguments[0], b, a)
				"reversed" -> if (receiver != null) return comesBefore(receiver, b, a)
				"compareBy" -> return byKeys(call, arguments, a, b)
				"compareByDescending" -> return keyBefore(arguments.single(), b, a)
				"thenBy", "thenByDescending" -> if (receiver != null) {
					val next = arguments.single()
					val second = if (name == "thenBy") keyBefore(next, a, b) else keyBefore(next, b, a)
					return thenComparing(receiver, second, a, b)
				}
				else -> throw Rejection.notYet(inner, "The comparator $name")
			}
		}
		opened(inner)?.let { (parameters, comparison) ->
			unit.alias(parameters[0], a)
			unit.alias(parameters[1], b)
			return before(comparison)
		}
		// Any other comparator is a function, called on the two, or opened where it is a small one
		Expression.binary(applied(body.expression(inner), a, b), BinaryOperator.LESS, Expression.integer(0))
	}

	/** Kotlin's functions that make or combine comparators, and Java's `Comparator` and `Collections`. */
	private fun isComparatorFunction(function: KaCallableSymbol): Boolean {
		val id = function.callableId ?: return false
		return id.packageName.asString() == "kotlin.comparisons" ||
			id.classId?.asFqNameString() in setOf("java.util.Comparator", "java.util.Collections")
	}

	/**
	 * `compareBy({ it.herb }, { it.count })`: the first key decides, and each next one where the keys before it are
	 * equal.
	 */
	private fun byKeys(call: KtCallExpression, selectors: List<KtExpression>, a: String, b: String): Expression {
		if (selectors.isEmpty() || selectors.any(::isComparator)) {
			throw Rejection.notYet(call, "compareBy with a comparator for its key")
		}
		var test = keyBefore(selectors.last(), a, b)
		for (selector in selectors.dropLast(1).asReversed()) {
			test = tie(selector, false, test, a, b)
		}
		return test
	}

	private fun isComparator(value: KtExpression): Boolean = with(unit.session) {
		value.expressionType?.expandedSymbol?.classId?.asFqNameString() == "java.util.Comparator"
	}

	/**
	 * `first.thenBy { key }`: the first comparator decides, and the next where it finds the two equal. A first
	 * comparator by key reads `a.level < b.level or (a.level == b.level and ...)`; any other tests both ways round.
	 */
	private fun thenComparing(first: KtExpression, second: Expression, a: String, b: String): Expression {
		keyOf(first)?.let { (selector, descending) -> return tie(selector, descending, second, a, b) }
		val notAfter = Expression.Unary(UnaryOperator.NOT, comesBefore(first, b, a))
		return Expression.binary(comesBefore(first, a, b), BinaryOperator.OR,
			Expression.Parenthesized(Expression.binary(notAfter, BinaryOperator.AND, second)))
	}

	/** `ka < kb or (ka == kb and next)`, or with `kb < ka` first for a descending key. */
	private fun tie(selector: KtExpression, descending: Boolean, next: Expression, a: String, b: String): Expression {
		val keyA = key(selector, a)
		val keyB = key(selector, b)
		val before = if (descending) Expression.binary(keyB, BinaryOperator.LESS, keyA)
		else Expression.binary(keyA, BinaryOperator.LESS, keyB)
		val tie = Expression.binary(Expression.binary(keyA, BinaryOperator.EQUAL, keyB), BinaryOperator.AND, next)
		return Expression.binary(before, BinaryOperator.OR, Expression.Parenthesized(tie))
	}

	/** The key of `compareBy { key }` or `compareByDescending { key }`, and whether it descends; null for others. */
	private fun keyOf(comparator: KtExpression): Pair<KtExpression, Boolean>? = with(unit.session) {
		val call = (KtPsiUtil.safeDeparenthesize(comparator) as? KtDotQualifiedExpression)?.selectorExpression
			as? KtCallExpression ?: KtPsiUtil.safeDeparenthesize(comparator) as? KtCallExpression ?: return null
		val function = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol ?: return null
		val selector = call.valueArguments.singleOrNull()?.getArgumentExpression() ?: return null
		if (!isComparatorFunction(function) || isComparator(selector)) {
			return null
		}
		when (function.callableId?.callableName?.asString()) {
			"compareBy" -> selector to false
			"compareByDescending" -> selector to true
			else -> null
		}
	}

	/** Whether the value named [a] has the smaller key. */
	fun keyBefore(selector: KtExpression, a: String, b: String): Expression =
		Expression.binary(key(selector, a), BinaryOperator.LESS, key(selector, b))

	/** The key a selector gives for the value named: the lambda's expression, or a call of the function. */
	private fun key(selector: KtExpression, value: String): Expression {
		val inner = KtPsiUtil.safeDeparenthesize(selector)
		if (inner is KtLambdaExpression) {
			val key = body.lambdas.value(inner, "A comparator's key is compared inside one expression; write it as one,"
				+ " { key }.")
			unit.alias(body.lambdas.parameter(inner).declaration, value)
			return body.expression(key)
		}
		return applied(body.expression(inner), value)
	}

	/** A function called on the values named, opened where it is a reference's small function. */
	private fun applied(function: Expression, vararg values: String): Expression {
		val arguments = values.map(Expression::name)
		return Inlining.value(function, arguments) ?: Expression.Call(function, arguments)
	}

	/** A comparator's two parameters and its one comparison, or null when it is not that small. */
	private fun opened(comparator: KtExpression): Pair<List<KtParameter>, KtExpression>? {
		if (comparator is KtLambdaExpression && comparator.valueParameters.size == 2) {
			val comparison = comparator.bodyExpression?.statements?.singleOrNull() ?: return null
			return comparator.valueParameters to comparison
		}
		if (comparator is KtObjectLiteralExpression) {
			val compare = comparator.objectDeclaration.declarations.singleOrNull() as? KtNamedFunction ?: return null
			if (compare.name != "compare" || compare.valueParameters.size != 2) {
				return null
			}
			val comparison = if (compare.hasBlockBody()) {
				(compare.bodyBlockExpression?.statements?.singleOrNull() as? KtReturnExpression)?.returnedExpression
			} else {
				compare.bodyExpression
			} ?: return null
			return compare.valueParameters to comparison
		}
		return null
	}

	/**
	 * A comparison's sign as a test: `x.compareTo(y)` and `compareValues(x, y)` of numbers, strings, chars or an enum's
	 * constants are `x < y`, and any other is `c < 0`.
	 */
	private fun before(comparison: KtExpression): Expression = with(unit.session) {
		val qualified = comparison as? KtDotQualifiedExpression
		val call = (qualified?.selectorExpression ?: comparison) as? KtCallExpression
		val function = call?.resolveToCall()?.successfulFunctionCallOrNull()?.symbol
		val arguments = call?.valueArguments?.mapNotNull { it.getArgumentExpression() }.orEmpty()
		val name = function?.callableId?.callableName?.asString()
		val ordered = when {
			qualified != null && name == "compareTo" && arguments.size == 1 ->
				ordered(qualified.receiverExpression, arguments[0])
			qualified == null && name == "compareValues" && function.callableId?.packageName?.asString() ==
				"kotlin.comparisons" && arguments.size == 2 -> ordered(arguments[0], arguments[1])
			else -> null
		}
		ordered ?: Expression.binary(body.expression(comparison), BinaryOperator.LESS, Expression.integer(0))
	}

	/** `x < y` of numbers, strings and chars, and of an enum's constants by their order; null for other values. */
	private fun ordered(left: KtExpression, right: KtExpression): Expression? = with(unit.session) {
		val type = left.expressionType ?: return null
		if (body.pluginEnum(type) != null) {
			return Expression.binary(Expression.field(body.expression(left), "ordinal"), BinaryOperator.LESS,
				Expression.field(body.expression(right), "ordinal"))
		}
		val types = unit.types
		if (types.isString(type) || types.isIntegral(type) || types.isFloating(type) || body.chars.isChar(left)) {
			return Expression.binary(body.expression(left), BinaryOperator.LESS, body.expression(right))
		}
		null
	}

	/** The names of the two values a comparator's function compares, `a` and `b` unless the code has those. */
	private fun compared(where: PsiElement): List<String> =
		listOf(body.inventInner("a", where), body.inventInner("b", where))

	private fun less(a: String, b: String): Expression =
		Expression.binary(Expression.name(a), BinaryOperator.LESS, Expression.name(b))

	private fun function(parameters: List<String>, test: Expression): Expression =
		Expression.FunctionExpression(FunctionBody.of(parameters, Block.of(Statement.Return(listOf(test)))))
}
