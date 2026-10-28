package org.adelheid.oneiro.kotlin.library

import org.adelheid.oneiro.kotlin.core.BodyLowering
import org.adelheid.oneiro.kotlin.core.Rejection
import org.adelheid.oneiro.kotlin.core.isPlainData
import org.adelheid.oneiro.lowering.SourceLanguage
import org.adelheid.oneiro.lowering.client.ClientApi
import org.adelheid.oneiro.lowering.translation.FormatTranslation
import org.adelheid.oneiro.lowering.translation.Helper
import org.adelheid.oneiro.lua.BinaryOperator
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.Statement
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtSimpleNameStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Kotlin's string building in the house style: templates and `+` become one call of the client's `formatStr`,
 * `format` becomes one through [FormatTranslation], and values become text by their type, as in the Java front end.
 * The client hands every number to `formatStr` as a double, so a `Long` goes through `tostring` to stay exact. A
 * `StringBuilder` is a list of pieces, which `table.concat` joins.
 */
internal class StringBuilding(private val body: BodyLowering) {

	private val unit = body.unit
	private val types = body.types

	/** The locals of the `buildString` builders being lowered, innermost last, which their `append`s add to. */
	private val builders = ArrayDeque<String>()

	/** One piece of a string being built: literal text, or a value to turn into text. */
	private class Part(val text: String?, val value: KtExpression?)

	/**
	 * A string template, or a `+` concatenation of strings: `formatStr("text {}", value)` when it has literal text or
	 * a value that is not a string, and `a .. b` when it joins only strings, a `Char` being a one-character string.
	 */
	fun concatenation(expression: KtExpression): Expression {
		val parts = mutableListOf<Part>()
		flatten(expression, parts)
		val onlyStrings = parts.all { part -> part.value != null && isString(part.value) }
		if (onlyStrings) {
			// Lowered in the order Kotlin evaluates them, which the values they hoist keep
			val lowered = parts.map { body.expression(it.value!!) }
			var joined = lowered.last()
			for (index in lowered.size - 2 downTo 0) {
				joined = Expression.binary(lowered[index], BinaryOperator.CONCAT, joined)
			}
			return joined
		}
		val template = StringBuilder()
		val plain = StringBuilder()
		val arguments = mutableListOf<Expression>()
		for (part in parts) {
			if (part.text != null) {
				template.append(FormatTranslation.escapeBraces(part.text))
				plain.append(part.text)
			} else {
				template.append("{}")
				arguments += formatArgument(part.value!!)
			}
		}
		if (arguments.isEmpty()) {
			return Expression.string(plain.toString())
		}
		if (template.toString() == "{}") {
			// "" + value, or "$value": the value as text
			return toText(parts.first { it.value != null }.value!!)
		}
		return ClientApi.formatStr(template.toString(), arguments)
	}

	/** The pieces of a template or of `a + b + c`, following only the `+` that join strings, in order. */
	private fun flatten(expression: KtExpression, parts: MutableList<Part>) {
		when {
			expression is KtParenthesizedExpression && isConcatenation(expression.expression) ->
				flatten(expression.expression!!, parts)
			expression is KtBinaryExpression && isConcatenation(expression) -> {
				flatten(expression.left!!, parts)
				flatten(expression.right!!, parts)
			}
			expression is KtStringTemplateExpression -> for (entry in expression.entries) {
				when (entry) {
					is KtSimpleNameStringTemplateEntry, is KtBlockStringTemplateEntry ->
						parts += Part(null, entry.expression ?: throw Rejection(entry, EMPTY))
					else -> parts += Part(body.literalText(entry), null)
				}
			}
			else -> parts += Part(null, expression)
		}
	}

	private fun isConcatenation(expression: KtExpression?): Boolean {
		if (expression !is KtBinaryExpression || expression.operationToken != KtTokens.PLUS) {
			return false
		}
		val type = with(unit.session) { expression.expressionType } ?: return false
		return types.isString(type)
	}

	/** Whether a piece is a string or a char, a one-character string, whose Lua is text already. */
	private fun isString(value: KtExpression): Boolean {
		if (value is KtStringTemplateExpression) {
			return false
		}
		val type = with(unit.session) { value.expressionType } ?: return false
		return types.isString(type) && !types.isNullable(type) || isChar(type)
	}

	private fun isChar(type: KaType): Boolean =
		with(unit.session) { type.expandedSymbol?.classId == ClassId.fromString("kotlin/Char") }

	/** The value appended by `text += value`, as the right operand of `..`: a string. */
	fun concatOperand(value: KtExpression): Expression {
		val type = with(unit.session) { value.expressionType }
		if (value is KtStringTemplateExpression || value is KtBinaryExpression && isConcatenation(value)) {
			return concatenation(value)
		}
		return if (type != null && types.isString(type) && !types.isNullable(type)) body.expression(value)
		else toText(value)
	}

	/**
	 * A value inside `formatStr`: strings, numbers, booleans and nil as they are; a `Long` through `tostring`, because
	 * the client would round it to a double; other objects as their text.
	 */
	fun formatArgument(value: KtExpression): Expression {
		val type = with(unit.session) { value.expressionType } ?: throw Rejection(value, EMPTY)
		return when {
			types.isLong(type) -> call("tostring", body.expression(value))
			types.isString(type) || types.isIntegral(type) || types.isFloating(type) || types.isBoolean(type) ||
				isNull(value) -> body.expression(value)
			else -> objectText(value, type)
		}
	}

	/**
	 * A value for `table.concat`, `print` or `error`, which take strings and numbers: integers as they are, decimals
	 * through `formatStr` so that they print as Kotlin prints them, booleans and nil through `tostring`.
	 */
	fun asText(value: KtExpression): Expression {
		val type = with(unit.session) { value.expressionType } ?: throw Rejection(value, EMPTY)
		return when {
			types.isString(type) && !types.isNullable(type) || types.isIntegral(type) -> body.expression(value)
			types.isFloating(type) -> ClientApi.formatStr("{}", listOf(body.expression(value)))
			types.isBoolean(type) || isNull(value) || types.isString(type) -> call("tostring", body.expression(value))
			else -> objectText(value, type)
		}
	}

	/** A value turned into a string, as `toString()` does. */
	fun toText(value: KtExpression): Expression {
		val type = with(unit.session) { value.expressionType } ?: throw Rejection(value, EMPTY)
		return when {
			types.isString(type) && !types.isNullable(type) -> body.expression(value)
			types.isFloating(type) -> ClientApi.formatStr("{}", listOf(body.expression(value)))
			types.isIntegral(type) || types.isBoolean(type) || isNull(value) || types.isString(type) ->
				call("tostring", body.expression(value))
			else -> objectText(value, type)
		}
	}

	private fun isNull(value: KtExpression): Boolean =
		value is KtConstantExpression && value.node.elementType == KtNodeTypes.NULL

	/**
	 * An object as text: a char as the string it is, a collection as Kotlin writes it, a `StringBuilder`'s pieces
	 * joined, a simple enum's constant as it is, the client's objects and errors through `tostring`, and an object of
	 * the plugin's by its own `toString()`.
	 */
	private fun objectText(value: KtExpression, type: KaType): Expression = with(unit.session) {
		if (isChar(type)) {
			return body.expression(value)
		}
		if (types.isArray(type)) {
			throw Rejection(value, "Kotlin writes an array as its type and identity, which Lua has no form of; write"
				+ " its elements with joinToString().")
		}
		if (types.isList(type) || types.isSet(type) || types.isMap(type)) {
			return collectionText(value, type)
		}
		if (isBuilder(type)) {
			return Expression.call(Expression.field(Expression.name("table"), "concat"), body.expression(value))
		}
		val symbol = type.expandedSymbol ?: throw Rejection.notYet(value, "Turning this value into text")
		if (body.api.isApi(symbol) && symbol.classKind == KaClassKind.ENUM_CLASS) {
			return body.api.enumName(value, body.expression(value), symbol)
		}
		if (body.api.isApi(symbol) || body.isThrowable(symbol)) {
			// The client's objects describe themselves through tostring; a caught error is its value
			return call("tostring", body.expression(value))
		}
		if (!unit.isPluginType(symbol)) {
			throw Rejection.notYet(value, "Turning a ${symbol.classId?.shortClassName} into text")
		}
		val declaration = symbol.psi as? KtClass
		if (symbol.classKind == KaClassKind.ENUM_CLASS) {
			return body.enumText(value)!!
		}
		if (declaration?.isPlainData() == true) {
			throw Rejection(value, "A data class of properties alone is a plain Lua table without a toString(); put its"
				+ " properties into the text instead.")
		}
		val ownToString = symbol.memberScope.callables.any {
			it.callableId?.callableName?.asString() == "toString" && it.origin == KaSymbolOrigin.SOURCE
		}
		if (!ownToString && (symbol as? KaNamedClassSymbol)?.isData == true) {
			throw Rejection(value, "Kotlin generates toString() for a data class, but its Lua table has no such"
				+ " function; give ${symbol.classId?.shortClassName} a toString() function, or put its properties into"
				+ " the text instead.")
		}
		if (!ownToString) {
			throw Rejection(value, "${symbol.classId?.shortClassName} has no toString() of its own, so Kotlin would"
				+ " write its class name and hash code; give it a toString() function.")
		}
		Expression.methodCall(body.expression(value), "toString")
	}

	/**
	 * A list, set or map as Kotlin writes it, `[a, b]` or `{k=v}`, through a helper. Its elements must be strings,
	 * numbers or booleans, which Lua writes as Kotlin does; anything else Lua writes as a table address.
	 */
	private fun collectionText(value: KtExpression, type: KaType): Expression {
		val isMap = types.isMap(type)
		for (index in 0 until if (isMap) 2 else 1) {
			val element = types.typeArgumentOf(type, index)
			if (element == null || !(types.isString(element) || types.isIntegral(element) ||
					types.isFloating(element) || types.isBoolean(element) || isChar(element))) {
				throw Rejection(value, "Lua would write each element as its table address, not as Kotlin's"
					+ " toString(); join the elements with joinToString().")
			}
		}
		val helper = if (isMap) Helper.MAP_TEXT else if (types.isSet(type)) Helper.SET_TEXT else Helper.LIST_TEXT
		unit.use(helper)
		return helper.invoke(body.expression(value))
	}

	/**
	 * `format`, `"%d apples".format(count)` or `String.format("%d apples", count)`, with a literal format, as one call
	 * of `formatStr`.
	 */
	fun format(where: KtExpression, format: KtExpression, values: List<KtExpression>): Expression {
		val text = (format as? KtStringTemplateExpression)?.takeIf { !it.hasInterpolation() }
			?.let { template -> template.entries.joinToString("") { body.literalText(it) } }
			?: throw Rejection(where, "format needs a literal format string, so that Oneiro can translate its"
				+ " specifiers when it compiles.")
		val translated = object : FormatTranslation.Values {
			override fun size(): Int = values.size

			override fun kind(index: Int): FormatTranslation.Kind = kind(values[index])

			override fun formatArgument(index: Int): Expression = formatArgument(values[index])

			override fun expression(index: Int): Expression = body.expression(values[index])
		}
		try {
			return FormatTranslation.translate(text, translated, SourceLanguage.KOTLIN)
		} catch (problem: FormatTranslation.Problem) {
			val at = if (problem.value() < 0) where else values[problem.value()]
			throw if (problem.notYet()) Rejection.notYet(at, problem.text()) else Rejection(at, problem.text())
		}
	}

	/** What kind of value a format formats, by its Kotlin type. */
	private fun kind(value: KtExpression): FormatTranslation.Kind {
		val type = with(unit.session) { value.expressionType } ?: return FormatTranslation.Kind.OTHER
		return when {
			isChar(type) -> FormatTranslation.Kind.CHAR
			types.isLong(type) -> FormatTranslation.Kind.LONG
			types.isIntegral(type) -> FormatTranslation.Kind.INTEGER
			types.isFloating(type) -> FormatTranslation.Kind.FLOATING
			types.isBoolean(type) -> FormatTranslation.Kind.BOOLEAN
			types.isString(type) -> FormatTranslation.Kind.STRING
			else -> FormatTranslation.Kind.OTHER
		}
	}

	/** Whether a type is `StringBuilder`, which Lua holds as a list of pieces. */
	fun isBuilder(type: KaType): Boolean = with(unit.session) {
		type.expandedSymbol?.classId?.asFqNameString() == "java.lang.StringBuilder"
	}

	/** `StringBuilder()` as an empty list of pieces, and `StringBuilder(text)` as a list of that one piece. */
	fun newBuilder(arguments: List<KtExpression>): Expression =
		Expression.Table(arguments.map { Expression.TableField.Positional(asText(it)) })

	/**
	 * `builder.append(a).append(b)` as one `builder[#builder + 1] = ...` line per appended value, in order; inside
	 * `buildString`, the `append`s without a receiver add to its builder. Null when the expression is no such chain.
	 */
	fun appends(expression: KtExpression): List<Statement>? {
		val values = mutableListOf<KtExpression>()
		var current: KtExpression? = expression
		var builder: Expression? = null
		while (current != null) {
			val call = (if (current is KtDotQualifiedExpression) current.selectorExpression else current)
				as? KtCallExpression ?: break
			if (!isAppend(call)) {
				break
			}
			values.add(0, call.valueArguments.singleOrNull()?.getArgumentExpression()
				?: throw Rejection.notYet(call, "append with other than one value"))
			if (current is KtDotQualifiedExpression) {
				current = current.receiverExpression
				continue
			}
			// An append with no receiver adds to the builder of the buildString around it
			builder = Expression.name(builders.lastOrNull() ?: return null)
			current = null
		}
		if (values.isEmpty()) {
			return null
		}
		val target = builder ?: current?.let { body.reused(it, LibraryCalls.READ_TWICE) } ?: return null
		return values.map { value -> Statement.assignment(Expression.Index(target, LibraryCalls.nextIndex(target)),
			asText(value)) }
	}

	private fun isAppend(call: KtCallExpression): Boolean = with(unit.session) {
		val function = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol ?: return false
		function.callableId?.callableName?.asString() == "append" &&
			function.callableId?.classId?.asFqNameString() == "java.lang.StringBuilder"
	}

	/** Whether an expression is `buildString { ... }`. */
	fun isBuildString(expression: KtExpression): Boolean = with(unit.session) {
		val call = expression as? KtCallExpression ?: return false
		val function = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol ?: return false
		function.callableId?.callableName?.asString() == "buildString" &&
			function.callableId?.packageName?.asString() == "kotlin.text" && call.lambdaArguments.size == 1
	}

	/**
	 * `buildString { ... }` as the statements that build it: its pieces, as [pieces] puts them together, and then the
	 * joined pieces handed to the sink.
	 */
	fun buildString(call: KtCallExpression, sink: (Expression) -> Statement): List<Statement> {
		val name = body.invent("text", call)
		return pieces(call, name) + sink(joined(name))
	}

	/**
	 * The statements that put the pieces of `buildString { ... }` together in a local of this name: an empty list of
	 * pieces, and then the lambda's code, whose `append`s add to it.
	 */
	fun pieces(call: KtCallExpression, name: String): List<Statement> {
		val lambda: KtLambdaExpression = call.lambdaArguments.single().getLambdaExpression()
			?: throw Rejection(call, EMPTY)
		val literal = lambda.functionLiteral
		unit.inlinedLambdas += literal
		builders.addLast(name)
		val code = try {
			body.block(literal.bodyBlockExpression ?: throw Rejection(lambda, EMPTY))
		} finally {
			builders.removeLast()
		}
		return listOf(Statement.Local(listOf(name), listOf(Expression.Table(listOf())))) +
			code.entries().map { it.statement() }
	}

	/** The text of the pieces in the local of this name, joined: `table.concat(text)`. */
	fun joined(name: String): Expression {
		unit.useGlobal("table")
		return Expression.call(Expression.field(Expression.name("table"), "concat"), Expression.name(name))
	}

	private fun call(function: String, vararg arguments: Expression): Expression {
		unit.useGlobal(function)
		return Expression.Call(Expression.name(function), arguments.toList())
	}

	private companion object {
		const val EMPTY = "This expression is incomplete and does not compile."
	}
}
