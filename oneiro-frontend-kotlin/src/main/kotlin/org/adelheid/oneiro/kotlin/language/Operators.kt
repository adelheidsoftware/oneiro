package org.adelheid.oneiro.kotlin.language

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.adelheid.oneiro.kotlin.core.BodyLowering
import org.adelheid.oneiro.kotlin.core.Receiver
import org.adelheid.oneiro.kotlin.core.Rejection
import org.adelheid.oneiro.kotlin.library.Preconditions
import org.adelheid.oneiro.lowering.translation.Helper
import org.adelheid.oneiro.lowering.translation.IndexArithmetic
import org.adelheid.oneiro.lua.BinaryOperator
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.UnaryOperator
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtThrowExpression

/**
 * Kotlin's operators in Lua: arithmetic, comparison, logic and bits, ranges and `?:`. Integer division and remainder
 * keep Kotlin's rounding toward zero, and `==` refuses to compare objects whose `equals` compares values, which Lua's
 * `==` cannot.
 */
internal class Operators(private val body: BodyLowering) {

	private val unit = body.unit
	private val types = body.types

	fun prefix(prefix: KtPrefixExpression): Expression {
		val operand = body.expression(prefix.baseExpression ?: throw Rejection(prefix, EMPTY))
		return when (prefix.operationToken) {
			KtTokens.EXCL -> negation(operand)
			KtTokens.MINUS -> Expression.Unary(UnaryOperator.NEGATE, operand)
			else -> throw Rejection.notYet(prefix, "The prefix operator ${prefix.operationReference.text}")
		}
	}

	fun binary(binary: KtBinaryExpression): Expression {
		val left = binary.left ?: throw Rejection(binary, EMPTY)
		val right = binary.right ?: throw Rejection(binary, EMPTY)
		val operator = when (binary.operationToken) {
			KtTokens.EQEQ -> {
				checkEquality(binary, left)
				BinaryOperator.EQUAL
			}
			KtTokens.EXCLEQ -> {
				checkEquality(binary, left)
				BinaryOperator.NOT_EQUAL
			}
			KtTokens.EQEQEQ -> BinaryOperator.EQUAL
			KtTokens.EXCLEQEQEQ -> BinaryOperator.NOT_EQUAL
			KtTokens.LT, KtTokens.GT, KtTokens.LTEQ, KtTokens.GTEQ -> return comparison(binary, left, right)
			KtTokens.ANDAND -> BinaryOperator.AND
			KtTokens.OROR -> BinaryOperator.OR
			KtTokens.PLUS -> {
				val type = with(unit.session) { binary.expressionType }
				if (type != null && types.isString(type)) {
					return body.strings.concatenation(binary)
				}
				body.chars.arithmetic(binary, left, right)?.let { return it }
				// In a loop that counts from 1, Lua's variable is already Kotlin's plus one
				oneBasedPlusOne(left, right)?.let { return it }
				BinaryOperator.ADD
			}
			KtTokens.MINUS -> {
				body.chars.arithmetic(binary, left, right)?.let { return it }
				BinaryOperator.SUBTRACT
			}
			KtTokens.MUL -> BinaryOperator.MULTIPLY
			KtTokens.DIV -> return division(left, right)
			KtTokens.PERC -> return remainder(left, right)
			KtTokens.IDENTIFIER -> return infix(binary, left, right)
			KtTokens.IN_KEYWORD -> return inRange(binary, left, body.expression(left), right)
			KtTokens.NOT_IN -> return negation(inRange(binary, left, body.expression(left), right))
			KtTokens.ELVIS -> return elvis(binary, left, right)
			in ASSIGNMENTS -> throw Rejection(binary, "In Lua an assignment is a statement, not a value; assign on a"
				+ " line of its own.")
			else -> throw Rejection.notYet(binary, "The operator ${binary.operationReference.text}")
		}
		return Expression.Binary(operator, body.expression(left), body.expression(right))
	}

	/** `<`, `>`, `<=` and `>=`; on an enum's constants, which compare by their place in the enum, on their ordinals. */
	private fun comparison(binary: KtBinaryExpression, left: KtExpression, right: KtExpression): Expression {
		val operator = when (binary.operationToken) {
			KtTokens.LT -> BinaryOperator.LESS
			KtTokens.GT -> BinaryOperator.GREATER
			KtTokens.LTEQ -> BinaryOperator.LESS_EQUAL
			else -> BinaryOperator.GREATER_EQUAL
		}
		if (body.pluginEnum(with(unit.session) { left.expressionType }) != null) {
			return Expression.binary(Expression.field(body.expression(left), "ordinal"), operator,
				Expression.field(body.expression(right), "ordinal"))
		}
		return Expression.binary(body.expression(left), operator, body.expression(right))
	}

	/**
	 * A test turned around: `a ~= b` for `a == b`, which reads better than `not (a == b)` and always means the same,
	 * the test itself for `not test`, and `not test` for any other.
	 */
	fun negation(test: Expression): Expression {
		inverted(test)?.let { return it }
		if (test is Expression.Unary && test.operator() == UnaryOperator.NOT) {
			return test.operand()
		}
		return Expression.Unary(UnaryOperator.NOT, test)
	}

	/** `a ~= b` for `a == b`, and `a == b` for `a ~= b`; null for any other expression. */
	private fun inverted(expression: Expression): Expression? {
		val comparison = expression as? Expression.Binary ?: return null
		return when (comparison.operator()) {
			BinaryOperator.EQUAL -> Expression.binary(comparison.left(), BinaryOperator.NOT_EQUAL, comparison.right())
			BinaryOperator.NOT_EQUAL -> Expression.binary(comparison.left(), BinaryOperator.EQUAL, comparison.right())
			else -> null
		}
	}

	/**
	 * `x in range`: within a range of integers, `x >= a and x <= b`, which reads the value twice, so it must be a name
	 * or a property read; otherwise a collection's `contains`.
	 *
	 * @param element the expression whose type the value has
	 */
	fun inRange(where: PsiElement, element: KtExpression, value: Expression, range: KtExpression,
		stable: Boolean = false): Expression {
		val bounds = range as? KtBinaryExpression
		val token = bounds?.operationToken
		val inclusive = token == KtTokens.RANGE
		if (bounds == null || !(inclusive || token == KtTokens.RANGE_UNTIL ||
				bounds.operationReference.getReferencedName() == "until")) {
			return body.library.contains(where, element, value, Receiver.Written(range))
		}
		if (!stable && !body.isStable(element)) {
			throw Rejection(element, "Lua compares this value with both ends of the range; store it in a local first.")
		}
		val low = body.expression(bounds.left ?: throw Rejection(bounds, EMPTY))
		val high = body.expression(bounds.right ?: throw Rejection(bounds, EMPTY))
		val above = Expression.binary(value, BinaryOperator.GREATER_EQUAL, low)
		val below = Expression.binary(value, if (inclusive) BinaryOperator.LESS_EQUAL else BinaryOperator.LESS, high)
		return Expression.binary(above, BinaryOperator.AND, below)
	}

	/** `value ?: default`; see [orElse]. */
	private fun elvis(binary: KtBinaryExpression, left: KtExpression, right: KtExpression): Expression {
		if (Branches.jumps(right) && right !is KtThrowExpression) {
			throw Rejection(binary, "In Lua leaving with ?: takes a statement of its own; write it as a local, val x ="
				+ " value ?: return, or test the value with if.")
		}
		return orElse(body.expression(left), with(unit.session) { left.expressionType }, right)
	}

	/**
	 * A value that may be nil, or a default: `value or default`, unless the value can be `false`, which `or` would
	 * replace too; then a helper replaces only nil.
	 *
	 * @param type the value's type
	 */
	fun orElse(value: Expression, type: KaType?, default: KtExpression): Expression {
		if (type != null && types.isBoolean(type)) {
			// The helper works out its default first, which would raise every time
			if (with(unit.session) { default.expressionType }?.let(types::isNothing) == true) {
				throw Rejection(default, Preconditions.FALSE_IS_NOT_NULL)
			}
			// A nullable flag with a fixed default compares with the other value
			when ((default as? KtConstantExpression)?.text) {
				"true" -> return Expression.binary(value, BinaryOperator.NOT_EQUAL, Expression.BooleanLiteral(false))
				"false" -> return Expression.binary(value, BinaryOperator.EQUAL, Expression.BooleanLiteral(true))
			}
			unit.use(Helper.VALUE_OR)
			return Helper.VALUE_OR.invoke(value, body.expression(default))
		}
		return Expression.binary(value, BinaryOperator.OR, body.expression(default))
	}

	/**
	 * Integer `/` keeps Kotlin's rounding toward zero, through a helper, unless both operands are known never to be
	 * negative, where Lua's `//` agrees; other numbers divide as they are.
	 */
	fun division(left: KtExpression, right: KtExpression): Expression {
		val dividend = body.expression(left)
		val divisor = body.expression(right)
		if (!integral(left, right)) {
			return Expression.binary(dividend, BinaryOperator.DIVIDE, divisor)
		}
		if (unit.nonNegative.isKnown(left) && unit.nonNegative.isKnown(right)) {
			return Expression.binary(dividend, BinaryOperator.FLOOR_DIVIDE, divisor)
		}
		unit.use(Helper.QUOTIENT)
		return Helper.QUOTIENT.invoke(dividend, divisor)
	}

	/**
	 * `%` keeps the sign of the dividend, as Lua's `math.fmod` does; Lua's `%` agrees when both operands are integers
	 * known never to be negative.
	 */
	fun remainder(left: KtExpression, right: KtExpression): Expression {
		val dividend = body.expression(left)
		val divisor = body.expression(right)
		if (integral(left, right) && unit.nonNegative.isKnown(left) && unit.nonNegative.isKnown(right)) {
			return Expression.binary(dividend, BinaryOperator.MODULO, divisor)
		}
		unit.useGlobal("math")
		return Expression.call(Expression.field(Expression.name("math"), "fmod"), dividend, divisor)
	}

	private fun integral(left: KtExpression, right: KtExpression): Boolean = with(unit.session) {
		val leftType = left.expressionType
		val rightType = right.expressionType
		leftType != null && rightType != null && types.isIntegral(leftType) && types.isIntegral(rightType)
	}

	/**
	 * Kotlin's infix operators of the library: `and`, `or` and `xor` on integers and booleans, and the shifts `shl`,
	 * `shr` and `ushr`.
	 */
	private fun infix(binary: KtBinaryExpression, left: KtExpression, right: KtExpression): Expression {
		val name = binary.operationReference.getReferencedName()
		val type = with(unit.session) { left.expressionType }
		val function = with(unit.session) { binary.operationReference.resolveToCall()?.successfulFunctionCallOrNull() }
		if (type == null || function == null || !body.isLibrary(function.symbol)) {
			throw Rejection.notYet(binary, "The infix function $name")
		}
		val first = body.expression(left)
		val second = body.expression(right)
		if (types.isBoolean(type)) {
			if (name == "xor") {
				return Expression.binary(first, BinaryOperator.NOT_EQUAL, second)
			}
			if (PsiTreeUtil.findChildOfType(right, KtCallExpression::class.java, false) != null) {
				throw Rejection(binary, "Kotlin evaluates both sides of $name, but Lua's skips the right side when the"
					+ " left decides; evaluate the right side into a local first.")
			}
		}
		val operator = when (name) {
			"and" -> if (types.isBoolean(type)) BinaryOperator.AND else BinaryOperator.BIT_AND
			"or" -> if (types.isBoolean(type)) BinaryOperator.OR else BinaryOperator.BIT_OR
			"xor" -> BinaryOperator.BIT_XOR
			"shl" -> BinaryOperator.SHIFT_LEFT
			"shr" -> if (unit.nonNegative.isKnown(left)) {
				BinaryOperator.SHIFT_RIGHT
			} else {
				// Kotlin's shr keeps the sign, which is floor division by a power of two; Lua's >> is a logical shift
				return Expression.binary(first, BinaryOperator.FLOOR_DIVIDE, powerOfTwo(second))
			}
			"ushr" -> if (types.isLong(type)) {
				BinaryOperator.SHIFT_RIGHT
			} else {
				// An Int's 32 bits, shifted as the unsigned number they make
				val bits = Expression.binary(first, BinaryOperator.BIT_AND, Expression.NumberLiteral("0xFFFFFFFF"))
				return Expression.binary(bits, BinaryOperator.SHIFT_RIGHT, second)
			}
			else -> throw Rejection.notYet(binary, "The infix function $name")
		}
		return Expression.binary(first, operator, second)
	}

	/** `1 << n`, folded to a number when n is a literal. */
	private fun powerOfTwo(exponent: Expression): Expression {
		val literal = IndexArithmetic.integerValue(exponent)
		if (literal != null && literal in 0..62) {
			return Expression.integer(1L shl literal.toInt())
		}
		return Expression.binary(Expression.integer(1), BinaryOperator.SHIFT_LEFT, exponent)
	}

	/**
	 * Kotlin's `==` calls `equals`, which a data class, or a class that declares `equals`, makes compare values; Lua's
	 * compares tables by identity, as Kotlin's `===` does.
	 */
	private fun checkEquality(binary: KtBinaryExpression, left: KtExpression): Unit = with(unit.session) {
		val type = left.expressionType?.expandedSymbol as? KaNamedClassSymbol ?: return
		if (!unit.isPluginType(type)) {
			return
		}
		val declaresEquals = type.declaredMemberScope.callables.any {
			it.origin == KaSymbolOrigin.SOURCE && it.callableId?.callableName?.asString() == "equals"
		}
		if (type.isData || declaresEquals) {
			throw Rejection(binary, "Kotlin's == compares these objects by equals, but Lua's compares tables by"
				+ " identity; compare their properties instead, or use === for identity.")
		}
	}

	/** `i + 1` or `1 + i` where `i` counts from 1 in Lua: the variable itself. */
	private fun oneBasedPlusOne(left: KtExpression, right: KtExpression): Expression? {
		for ((variable, other) in listOf(left to right, right to left)) {
			if (body.oneBasedVariable(variable) && other is KtConstantExpression && other.text == "1") {
				return body.expression(variable)
			}
		}
		return null
	}

	private companion object {
		const val EMPTY = "This expression is incomplete and does not compile."
		val ASSIGNMENTS = setOf(KtTokens.EQ, KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ,
			KtTokens.PERCEQ)
	}
}
