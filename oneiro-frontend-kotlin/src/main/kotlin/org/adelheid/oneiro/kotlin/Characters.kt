package org.adelheid.oneiro.kotlin

import org.adelheid.oneiro.lua.BinaryOperator
import org.adelheid.oneiro.lua.Expression
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtExpression

/**
 * Kotlin's `Char` in Lua, where it is a one-character string, as in the Java front end. Comparing chars compares the
 * strings, which Lua does byte by byte; where Kotlin reads a char as its number, the Lua reads `string.byte(c)`, and
 * makes a char of a number with `string.char(n)`. The character tests use Lua's character classes, which know ASCII
 * only.
 */
internal class Characters(private val body: BodyLowering) {

	private val unit = body.unit

	fun isChar(type: KaType?): Boolean = with(unit.session) { type?.expandedSymbol?.classId == CHAR }

	fun isChar(expression: KtExpression): Boolean = isChar(with(unit.session) { expression.expressionType })

	/** A char literal, `'a'`, as the one-character string it is. */
	fun literal(constant: KtConstantExpression): Expression = with(unit.session) {
		val value = constant.evaluate()?.value as? Char ?: throw Rejection(constant, "This char literal is incomplete.")
		Expression.string(value.toString())
	}

	/**
	 * `+` and `-` with a char: `c - 'a'` is the difference of their numbers, `string.byte(c) - string.byte("a")`, and
	 * `'a' + index` the char of a number, `string.char(string.byte("a") + index)`. Null when neither operand is a char.
	 */
	fun arithmetic(binary: KtBinaryExpression, left: KtExpression, right: KtExpression): Expression? {
		if (!isChar(left) && !isChar(right)) {
			return null
		}
		val operator = if (binary.operationToken == KtTokens.PLUS) BinaryOperator.ADD else BinaryOperator.SUBTRACT
		val result = Expression.binary(number(left), operator, number(right))
		return if (isChar(with(unit.session) { binary.expressionType })) character(result) else result
	}

	/** `c++` and `c--` on a char: the next or the previous char, `string.char(string.byte(c) + 1)`. */
	fun stepped(target: Expression, up: Boolean): Expression {
		val operator = if (up) BinaryOperator.ADD else BinaryOperator.SUBTRACT
		return character(Expression.binary(byte(target), operator, Expression.integer(1)))
	}

	/** A char's members: its number, its case, and the tests of what kind of character it is. */
	fun member(name: String, receiver: KtExpression): Expression? {
		val value = body.expression(receiver)
		val characterClass = when (name) {
			"isDigit" -> "%d"
			"isLetter" -> "%a"
			"isLetterOrDigit" -> "%w"
			"isWhitespace" -> "%s"
			"isUpperCase" -> "%u"
			"isLowerCase" -> "%l"
			else -> null
		}
		if (characterClass != null) {
			val found = Expression.methodCall(value, "find", Expression.string(characterClass))
			return Expression.binary(found, BinaryOperator.NOT_EQUAL, Expression.Nil())
		}
		return when (name) {
			"code" -> byte(value)
			"uppercaseChar", "uppercase" -> Expression.methodCall(value, "upper")
			"lowercaseChar", "lowercase" -> Expression.methodCall(value, "lower")
			"digitToInt" -> call("tonumber", value)
			"toString" -> value
			else -> null
		}
	}

	/** A value as the number Kotlin reads: a char's, `string.byte(c)`, or an integer as it is. */
	fun number(value: KtExpression): Expression =
		if (isChar(value)) byte(body.expression(value)) else body.expression(value)

	/** `n.toChar()`, the char of a number: `string.char(n)`. */
	fun character(number: Expression): Expression =
		Expression.call(Expression.field(Expression.name("string"), "char"), number)

	private fun byte(value: Expression): Expression =
		Expression.call(Expression.field(Expression.name("string"), "byte"), value)

	private fun call(function: String, vararg arguments: Expression): Expression {
		unit.useGlobal(function)
		return Expression.Call(Expression.name(function), arguments.toList())
	}

	private companion object {
		val CHAR = ClassId.fromString("kotlin/Char")
	}
}
