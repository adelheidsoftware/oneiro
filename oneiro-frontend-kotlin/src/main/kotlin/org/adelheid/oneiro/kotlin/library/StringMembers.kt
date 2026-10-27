package org.adelheid.oneiro.kotlin.library

import org.adelheid.oneiro.kotlin.core.BodyLowering
import org.adelheid.oneiro.kotlin.core.Receiver
import org.adelheid.oneiro.kotlin.core.Rejection
import org.adelheid.oneiro.lowering.translation.Helper
import org.adelheid.oneiro.lowering.translation.IndexArithmetic
import org.adelheid.oneiro.lua.BinaryOperator
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.UnaryOperator
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * A Kotlin string's members on Lua's strings, as in the Java front end: `length` is `#text`, `substring` is `sub`
 * with Lua's positions, which count from 1, and the tests and searches use Lua's own functions, finding text as it is
 * written rather than as a pattern.
 */
internal class StringMembers(private val body: BodyLowering) {

	private val unit = body.unit

	/** A member of a string, or null when it is none of those this lists. */
	fun member(where: KtExpression, name: String, receiver: Receiver, arguments: LibraryArguments): Expression? {
		if (name == "isNullOrEmpty" || name == "isNullOrBlank") {
			return nullOrEmpty(name, receiver)
		}
		val text = body.expression(receiver)
		if (name == "equals" && arguments.byName.containsKey("ignoreCase")) {
			return equalsIgnoringCase(where, text, arguments)
		}
		if (arguments.byName.containsKey("ignoreCase")) {
			throw Rejection.notYet(where, "Comparing text with ignoreCase")
		}
		if (arguments.isEmpty()) {
			ofText(name, text)?.let { return it }
		}
		return when (name) {
			"equals" -> Expression.binary(text, BinaryOperator.EQUAL, body.expression(arguments[0]))
			"substring" -> if (arguments.size == 1) {
				Expression.methodCall(text, "sub", IndexArithmetic.plusOne(body.expression(arguments[0])))
			} else {
				Expression.methodCall(text, "sub", IndexArithmetic.plusOne(body.expression(arguments[0])),
					body.expression(arguments[1]))
			}
			"contains" -> contains(text, body.expression(arguments[0]))
			"indexOf" -> {
				if (arguments.size != 1) {
					throw Rejection.notYet(where, "String.indexOf with a start position")
				}
				helper(Helper.STRING_INDEX_OF, text, body.expression(arguments[0]))
			}
			"startsWith" -> {
				val prefix = body.expression(arguments[0])
				val start = Expression.methodCall(text, "sub", Expression.integer(1), byteLength(arguments[0], prefix))
				Expression.binary(start, BinaryOperator.EQUAL, prefix)
			}
			"endsWith" -> {
				val suffix = body.expression(arguments[0])
				val literal = literalText(arguments[0])
				if (literal != null && literal.isNotEmpty()) {
					val length = Expression.integer(literal.toByteArray(Charsets.UTF_8).size.toLong())
					val end = Expression.methodCall(text, "sub", Expression.Unary(UnaryOperator.NEGATE, length))
					Expression.binary(end, BinaryOperator.EQUAL, suffix)
				} else {
					helper(Helper.ENDS_WITH, text, suffix)
				}
			}
			"repeat" -> Expression.methodCall(text, "rep", body.expression(arguments[0]))
			"padStart" -> helper(Helper.PAD_START, text, body.expression(arguments[0]), padChar(arguments))
			"padEnd" -> helper(Helper.PAD_END, text, body.expression(arguments[0]), padChar(arguments))
			"removePrefix" -> helper(Helper.REMOVE_PREFIX, text, body.expression(arguments[0]))
			"removeSuffix" -> helper(Helper.REMOVE_SUFFIX, text, body.expression(arguments[0]))
			"substringBefore", "substringAfter" -> {
				if (arguments.size != 1) {
					throw Rejection.notYet(where, "$name with a value for text without the delimiter")
				}
				val helper = if (name == "substringBefore") Helper.SUBSTRING_BEFORE else Helper.SUBSTRING_AFTER
				helper(helper, text, body.expression(arguments[0]))
			}
			"take" -> Expression.methodCall(text, "sub", Expression.integer(1), body.expression(arguments[0]))
			"drop" -> Expression.methodCall(text, "sub", IndexArithmetic.plusOne(body.expression(arguments[0])))
			"trimIndent", "trimMargin" -> trimmed(where, name, receiver, arguments)
			"get" -> body.library.characterAt(text, arguments[0])
			"split" -> split(where, text, arguments)
			// The text found as it is written, as Kotlin's replace of a string or a char finds it
			"replace" -> helper(Helper.REPLACE, text, body.expression(arguments[0]), body.expression(arguments[1]))
			"compareTo" -> helper(Helper.COMPARE, text, body.expression(arguments[0]))
			else -> null
		}
	}

	/**
	 * A nullable string's `isNullOrEmpty()` and `isNullOrBlank()`: nil, or empty, or for the second without a char
	 * that is not whitespace. The text is read twice, so it is a name or a property read.
	 */
	private fun nullOrEmpty(name: String, receiver: Receiver): Expression {
		val text = body.library.stable(receiver)
		val absent = Expression.binary(text, BinaryOperator.EQUAL, Expression.Nil())
		val empty = ofText(if (name == "isNullOrEmpty") "isEmpty" else "isBlank", text)!!
		return Expression.binary(absent, BinaryOperator.OR, empty)
	}

	/** A member of a string that takes no arguments, which a reference such as `String::uppercase` names too. */
	fun ofText(name: String, text: Expression): Expression? = when (name) {
		"isEmpty" -> Expression.binary(text, BinaryOperator.EQUAL, Expression.string(""))
		"isNotEmpty" -> Expression.binary(text, BinaryOperator.NOT_EQUAL, Expression.string(""))
		"uppercase" -> Expression.methodCall(text, "upper")
		"lowercase" -> Expression.methodCall(text, "lower")
		"trim" -> helper(Helper.TRIM, text)
		// Lua's %s and %S know ASCII's whitespace only, as its other character classes know ASCII only
		"trimStart" -> Expression.methodCall(text, "match", Expression.string("^%s*(.*)"))
		"trimEnd" -> Expression.methodCall(text, "match", Expression.string("^(.-)%s*$"))
		"isBlank" -> Expression.binary(Expression.methodCall(text, "find", Expression.string("%S")),
			BinaryOperator.EQUAL, Expression.Nil())
		"isNotBlank" -> Expression.binary(Expression.methodCall(text, "find", Expression.string("%S")),
			BinaryOperator.NOT_EQUAL, Expression.Nil())
		"toString" -> text
		"orEmpty" -> Expression.binary(text, BinaryOperator.OR, Expression.string(""))
		"lines" -> helper(Helper.LINES, text)
		"reversed" -> Expression.methodCall(text, "reverse")
		"first" -> Expression.methodCall(text, "sub", Expression.integer(1), Expression.integer(1))
		"last" -> Expression.methodCall(text, "sub", Expression.integer(-1))
		// Parsing gives nil for text that is no number, where Kotlin's toInt() throws
		"toInt", "toIntOrNull", "toLong", "toLongOrNull" -> call("tonumber", text, Expression.integer(10))
		"toDouble", "toDoubleOrNull" -> call("tonumber", text)
		else -> null
	}

	/**
	 * `text.split(",")`: the parts between the separators, empty ones among them, as Kotlin splits, through a helper.
	 * The separator is a string that stands for itself, as Kotlin's is.
	 */
	private fun split(where: KtExpression, text: Expression, arguments: LibraryArguments): Expression {
		val limit = arguments.byName["limit"]
		if (arguments.size != (if (limit == null) 1 else 2)) {
			throw Rejection.notYet(where, "split with more than one separator")
		}
		val separator = arguments[0]
		val regex = with(unit.session) { separator.expressionType?.expandedSymbol?.classId?.asFqNameString() }
		if (regex == "kotlin.text.Regex") {
			throw Rejection(separator, "Lua has no regular expressions, so split takes only a string separator that"
				+ " stands for itself, such as a comma; for a pattern, use LuaString.gmatch.")
		}
		if (literalText(separator) == null) {
			throw Rejection.notYet(where, "split with a separator that is not a string literal")
		}
		// Kotlin's limit of 0 makes any number of parts, which the helper's -1 does
		val most = limit?.let { value ->
			if ((value as? KtConstantExpression)?.text == "0") null else body.expression(value)
		} ?: Expression.integer(-1)
		return helper(Helper.SPLIT, text, body.expression(separator), most)
	}

	/**
	 * A raw string's `trimIndent()` or `trimMargin()`, worked out here: the Lua holds the trimmed text, between long
	 * brackets when it has several lines.
	 */
	private fun trimmed(where: KtExpression, name: String, receiver: Receiver, arguments: LibraryArguments):
		Expression {
		val written = (receiver as? Receiver.Written)?.expression
		val raw = written?.let(::literalText)
		if (raw == null || written !is KtStringTemplateExpression || !body.isRaw(written)) {
			throw Rejection.notYet(where, "$name of anything but a raw string without templates")
		}
		val text = if (name == "trimIndent") {
			raw.trimIndent()
		} else {
			val prefix = arguments.byName["marginPrefix"]?.let(::literalText)
			if (arguments.isNotEmpty() && prefix == null) {
				throw Rejection.notYet(where, "trimMargin with a prefix that is not a literal")
			}
			raw.trimMargin(prefix ?: "|")
		}
		return Expression.StringLiteral(text, text.contains('\n'))
	}

	/** `a.equals(b, ignoreCase = true)` as `a:lower() == b:lower()`. */
	private fun equalsIgnoringCase(where: KtExpression, text: Expression, arguments: LibraryArguments): Expression {
		val ignoring = arguments.byName["ignoreCase"]
		if ((ignoring as? KtConstantExpression)?.text != "true") {
			throw Rejection.notYet(where, "equals with an ignoreCase that is not true")
		}
		val other = Expression.methodCall(body.expression(arguments[0]), "lower")
		return Expression.binary(Expression.methodCall(text, "lower"), BinaryOperator.EQUAL, other)
	}

	/** The char `padStart` and `padEnd` pad with, a space unless the call gives one. */
	private fun padChar(arguments: LibraryArguments): Expression =
		arguments.byName["padChar"]?.let(body::expression) ?: Expression.string(" ")

	private fun call(function: String, vararg arguments: Expression): Expression {
		unit.useGlobal(function)
		return Expression.Call(Expression.name(function), arguments.toList())
	}

	/** `text in other`, or `other.contains(text)`: Lua's `find` of the text as it is written. */
	fun contains(text: Expression, sought: Expression): Expression {
		val found = Expression.methodCall(text, "find", sought, Expression.integer(1), Expression.BooleanLiteral(true))
		return Expression.binary(found, BinaryOperator.NOT_EQUAL, Expression.Nil())
	}

	/** `#text`, or the byte count itself when the text is a literal. */
	private fun byteLength(tree: KtExpression, lowered: Expression): Expression {
		val literal = literalText(tree) ?: return LibraryCalls.length(lowered)
		return Expression.integer(literal.toByteArray(Charsets.UTF_8).size.toLong())
	}

	/** The text of a string literal without templates, or null for any other expression. */
	private fun literalText(tree: KtExpression): String? {
		val template = tree as? KtStringTemplateExpression ?: return null
		if (template.hasInterpolation()) {
			return null
		}
		return with(unit.session) { template.evaluate()?.value as? String }
	}

	private fun helper(helper: Helper, vararg arguments: Expression): Expression {
		unit.use(helper)
		return helper.invoke(*arguments)
	}
}
