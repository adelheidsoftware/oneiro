package org.adelheid.oneiro.kotlin

import com.intellij.psi.PsiComment
import org.adelheid.oneiro.lowering.IndexArithmetic
import org.adelheid.oneiro.lua.BinaryOperator
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Comment
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.Statement
import org.adelheid.oneiro.lua.Trivia
import org.adelheid.oneiro.lua.UnaryOperator
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** Lowers the statements and expressions of one function into the Lua tree. */
internal class BodyLowering(private val unit: FileLowering) {

	private val source = unit.source
	private val types = unit.types

	/** A block's statements, each with the comments above it and after it, and the comments that close the block. */
	fun block(block: KtBlockExpression): Block {
		val entries = mutableListOf<Block.Entry>()
		for (statement in block.statements) {
			entries += entries(statement)
		}
		// Comments after the last statement, before the closing brace, close the block
		val closing = generateSequence(block.rBrace?.prevSibling) { it.prevSibling }
			.takeWhile { it !is KtExpression }
			.filterIsInstance<PsiComment>()
			.toList()
			.reversed()
			.flatMap { source.leading(it) + listOfNotNull(source.trailing(it)).map(Comment::line) }
		return Block(entries, closing)
	}

	/** A statement as block entries: the first carries the comments above it, the last the one after it. */
	private fun entries(statement: KtExpression): List<Block.Entry> {
		val blankLine = source.blankLineBefore(statement)
		val leading = source.leading(statement)
		val lowered = statement(statement)
		val trailing = source.trailing(statement)
		val origin = source.position(unit.codeStart(statement))
		return lowered.mapIndexed { index, lua ->
			val trivia = Trivia(index == 0 && blankLine, if (index == 0) leading else listOf(),
				if (index == lowered.lastIndex) trailing else null)
			Block.Entry(lua, trivia, origin)
		}
	}

	/** A branch's body: a block, or a single statement. */
	private fun body(expression: KtExpression): Block =
		if (expression is KtBlockExpression) block(expression) else Block(entries(expression), listOf())

	private fun statement(statement: KtExpression): List<Statement> = when (statement) {
		is KtProperty -> listOf(local(statement))
		is KtIfExpression -> listOf(ifChain(statement))
		is KtReturnExpression -> listOf(returnStatement(statement))
		is KtForExpression -> listOf(forLoop(statement))
		else -> throw Rejection.notYet(statement, "This kind of statement")
	}

	private fun returnStatement(statement: KtReturnExpression): Statement =
		Statement.Return(listOfNotNull(statement.returnedExpression?.let(::expression)))

	private fun local(property: KtProperty): Statement {
		val name = unit.names.luaName(property.name!!)
		val initializer = property.initializer ?: throw Rejection.notYet(property, "A local without a value")
		return Statement.Local(listOf(name), listOf(expression(initializer)))
	}

	/** `if`, `else if` and `else` as one Lua `if` with `elseif` branches. */
	private fun ifChain(statement: KtIfExpression): Statement {
		val branches = mutableListOf<Statement.If.Branch>()
		var current: KtIfExpression = statement
		while (true) {
			val condition = current.condition ?: throw Rejection(current, "An if without a condition does not compile.")
			val then = current.then ?: throw Rejection(current, "An if without a body does not compile.")
			branches += Statement.If.Branch(expression(condition), body(then))
			when (val otherwise = current.`else`) {
				null -> return Statement.If(branches, null)
				is KtIfExpression -> current = otherwise
				else -> return Statement.If(branches, body(otherwise))
			}
		}
	}

	/** A loop over a range of integers, as a numeric `for`, counting from 1 when the file's loops allow it. */
	private fun forLoop(loop: KtForExpression): Statement {
		val parameter = loop.loopParameter ?: throw Rejection.notYet(loop, "A for loop that destructures")
		val range = CountingRange.of(loop) ?: throw Rejection.notYet(loop, "A for loop over anything but a range")
		val name = unit.names.luaName(parameter.name!!)
		val start = expression(range.start)
		val end = expression(range.end)
		val body = body(loop.body ?: throw Rejection(loop, "A for loop without a body does not compile."))
		return if (parameter in unit.oneBased) {
			// Lua's variable is Kotlin's plus one, so its range is one higher
			val last = if (range.inclusive) IndexArithmetic.plusOne(end) else end
			Statement.NumericFor(name, IndexArithmetic.plusOne(start), last, null, body)
		} else {
			val last = if (range.inclusive) end else IndexArithmetic.minusOne(end)
			Statement.NumericFor(name, start, last, null, body)
		}
	}

	// ---- Expressions

	fun expression(expression: KtExpression): Expression = when (expression) {
		is KtConstantExpression -> constant(expression)
		is KtStringTemplateExpression -> string(expression)
		is KtNameReferenceExpression -> reference(expression)
		is KtParenthesizedExpression -> expression(expression.expression ?: throw Rejection(expression, EMPTY))
		is KtPrefixExpression -> prefix(expression)
		is KtBinaryExpression -> binary(expression)
		is KtArrayAccessExpression -> index(expression)
		else -> throw Rejection.notYet(expression, "This kind of expression")
	}

	private fun constant(constant: KtConstantExpression): Expression = when (constant.node.elementType) {
		KtNodeTypes.NULL -> Expression.Nil()
		KtNodeTypes.BOOLEAN_CONSTANT -> Expression.BooleanLiteral(constant.text == "true")
		KtNodeTypes.INTEGER_CONSTANT -> integer(constant)
		else -> throw Rejection.notYet(constant, "This literal")
	}

	/** An integer literal, decimal or hexadecimal, as Lua writes it; Kotlin's underscores and suffixes go. */
	private fun integer(constant: KtConstantExpression): Expression {
		val text = constant.text.replace("_", "").trimEnd('L', 'l', 'u', 'U')
		return when {
			text.startsWith("0x") || text.startsWith("0X") -> Expression.NumberLiteral(text)
			text.startsWith("0b") || text.startsWith("0B") -> Expression.integer(text.substring(2).toLong(2))
			else -> Expression.integer(text.toLong())
		}
	}

	private fun string(template: KtStringTemplateExpression): Expression {
		if (template.hasInterpolation()) {
			throw Rejection.notYet(template, "A string template")
		}
		val text = template.entries.joinToString("") { entry ->
			when (entry) {
				is KtEscapeStringTemplateEntry -> entry.unescapedValue
				else -> entry.text
			}
		}
		return Expression.string(text)
	}

	/** A name: a local or parameter of the function. */
	private fun reference(reference: KtNameReferenceExpression): Expression {
		val symbol = with(unit.session) { reference.resolveSymbol() }
		if (symbol is KaLocalVariableSymbol || symbol is KaValueParameterSymbol) {
			return Expression.name(unit.names.luaName(reference.getReferencedName()))
		}
		throw Rejection.notYet(reference, "Using ${reference.getReferencedName()} here")
	}

	private fun prefix(prefix: KtPrefixExpression): Expression {
		val operand = expression(prefix.baseExpression ?: throw Rejection(prefix, EMPTY))
		return when (prefix.operationToken) {
			KtTokens.EXCL -> Expression.Unary(UnaryOperator.NOT, operand)
			KtTokens.MINUS -> Expression.Unary(UnaryOperator.NEGATE, operand)
			else -> throw Rejection.notYet(prefix, "The prefix operator ${prefix.operationReference.text}")
		}
	}

	private fun binary(binary: KtBinaryExpression): Expression {
		val left = binary.left ?: throw Rejection(binary, EMPTY)
		val right = binary.right ?: throw Rejection(binary, EMPTY)
		val operator = when (binary.operationToken) {
			KtTokens.EQEQ -> BinaryOperator.EQUAL
			KtTokens.EXCLEQ -> BinaryOperator.NOT_EQUAL
			KtTokens.LT -> BinaryOperator.LESS
			KtTokens.GT -> BinaryOperator.GREATER
			KtTokens.LTEQ -> BinaryOperator.LESS_EQUAL
			KtTokens.GTEQ -> BinaryOperator.GREATER_EQUAL
			KtTokens.ANDAND -> BinaryOperator.AND
			KtTokens.OROR -> BinaryOperator.OR
			KtTokens.PLUS -> {
				// In a loop that counts from 1, Lua's variable is already Kotlin's plus one
				oneBasedPlusOne(left, right)?.let { return it }
				BinaryOperator.ADD
			}
			KtTokens.MINUS -> BinaryOperator.SUBTRACT
			KtTokens.MUL -> BinaryOperator.MULTIPLY
			else -> throw Rejection.notYet(binary, "The operator ${binary.operationReference.text}")
		}
		return Expression.Binary(operator, expression(left), expression(right))
	}

	/** `i + 1` or `1 + i` where `i` counts from 1 in Lua: the variable itself. */
	private fun oneBasedPlusOne(left: KtExpression, right: KtExpression): Expression? {
		for ((variable, other) in listOf(left to right, right to left)) {
			if (oneBasedVariable(variable) && other is KtConstantExpression && other.text == "1") {
				return expression(variable)
			}
		}
		return null
	}

	/** `list[i]`, the element at a position Kotlin counts from 0 and Lua from 1. */
	private fun index(access: KtArrayAccessExpression): Expression {
		val receiver = access.arrayExpression ?: throw Rejection(access, EMPTY)
		val position = access.indexExpressions.singleOrNull()
			?: throw Rejection.notYet(access, "Indexing with several values")
		val receiverType = with(unit.session) { receiver.expressionType }
		if (receiverType == null || !types.isList(receiverType)) {
			throw Rejection.notYet(access, "Indexing this type")
		}
		val key = expression(position).let { if (oneBasedVariable(position)) it else IndexArithmetic.plusOne(it) }
		return Expression.Index(expression(receiver), key)
	}

	/** Whether an expression names the variable of a loop that counts from 1. */
	private fun oneBasedVariable(expression: KtExpression): Boolean {
		if (expression !is KtNameReferenceExpression) {
			return false
		}
		val declaration = with(unit.session) { expression.resolveSymbol()?.psi }
		return declaration is KtParameter && declaration in unit.oneBased
	}

	private companion object {
		const val EMPTY = "This expression is incomplete and does not compile."
	}
}
