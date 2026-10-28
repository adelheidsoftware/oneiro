package org.adelheid.oneiro.kotlin.language

import com.intellij.psi.util.PsiTreeUtil
import org.adelheid.oneiro.kotlin.core.BodyLowering
import org.adelheid.oneiro.kotlin.core.Rejection
import org.adelheid.oneiro.lowering.translation.PlainValues
import org.adelheid.oneiro.lua.BinaryOperator
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Comment
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.Statement
import org.adelheid.oneiro.lua.Trivia
import org.adelheid.oneiro.lua.UnaryOperator
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtWhenCondition
import org.jetbrains.kotlin.psi.KtWhenConditionInRange
import org.jetbrains.kotlin.psi.KtWhenConditionIsPattern
import org.jetbrains.kotlin.psi.KtWhenConditionWithExpression
import org.jetbrains.kotlin.psi.KtWhenExpression

/**
 * Kotlin's `if` and `when` as Lua's `if`: `when (x) { a, b -> ... }` tests `x == a or x == b` in turn, as `elseif`
 * branches. Where `if` or `when` gives a value to a `return`, a local or an assignment, each branch hands its last
 * expression to that statement, so `return when (mode) { FAST -> "fast" ... }` returns in each branch.
 */
internal class Branches(private val body: BodyLowering) {

	private val unit = body.unit

	/** `if`, `else if` and `else` as one Lua `if` with `elseif` branches. */
	fun ifStatement(statement: KtIfExpression): Statement = ifChain(statement, null)

	/** `when` as one Lua `if`, each entry a branch and `else` the last. */
	fun whenStatement(statement: KtWhenExpression): List<Statement> = whenChain(statement, null)

	/**
	 * An `if` or `when` that gives a value, as statements that hand each branch's value to the sink: a `return`, a
	 * local's assignment or another's. Null when the expression is neither.
	 */
	fun deliver(expression: KtExpression, sink: (Expression) -> Statement): List<Statement>? =
		when (val inner = unwrapped(expression)) {
			is KtIfExpression -> listOf(ifChain(inner, sink))
			is KtWhenExpression -> whenChain(inner, sink)
			else -> null
		}

	private fun unwrapped(expression: KtExpression): KtExpression =
		if (expression is KtParenthesizedExpression) {
			expression.expression?.let(::unwrapped) ?: expression
		} else {
			expression
		}

	private fun ifChain(statement: KtIfExpression, sink: ((Expression) -> Statement)?): Statement {
		val branches = mutableListOf<Statement.If.Branch>()
		var current: KtIfExpression = statement
		while (true) {
			val condition = current.condition ?: throw Rejection(current, "An if without a condition does not compile.")
			val then = current.then ?: throw Rejection(current, "An if without a body does not compile.")
			branches += Statement.If.Branch(body.condition(condition), branch(then, sink))
			when (val otherwise = current.`else`) {
				null -> {
					if (sink != null) {
						throw Rejection(current, "An if that gives a value needs an else.")
					}
					return Statement.If(branches, null)
				}
				is KtIfExpression -> current = otherwise
				else -> return Statement.If(branches, branch(otherwise, sink))
			}
		}
	}

	/**
	 * `when` as an `if` chain. The subject goes into a local when it has a call, which Kotlin evaluates once, or when
	 * its Lua is more than a name or a field and more than one condition compares it; otherwise each condition compares
	 * it as it is. A subject declared in the `when`, `when (val x = f())`, is that local.
	 */
	private fun whenChain(statement: KtWhenExpression, sink: ((Expression) -> Statement)?): List<Statement> {
		val before = mutableListOf<Statement>()
		val declared = statement.subjectVariable
		val comparisons = statement.entries.sumOf { it.conditions.size }
		// The subject as Lua reads it, and the expression whose type it has
		val subject: Pair<Expression, KtExpression>? = when {
			declared != null -> {
				val name = unit.localName(declared, declared.name!!)
				val value = declared.initializer ?: throw Rejection(declared, "A when's subject needs a value.")
				before += Statement.Local(listOf(name), listOf(body.expression(value)))
				Expression.name(name) to value
			}
			statement.subjectExpression != null -> {
				val tree = statement.subjectExpression!!
				val value = body.expression(tree)
				val calls = tree is KtCallExpression || PsiTreeUtil.findChildOfType(tree, KtCallExpression::class.java,
					false) != null
				if (calls || !PlainValues.isPlain(value) && comparisons > 1) {
					val name = body.invent(subjectName(tree), statement)
					before += Statement.Local(listOf(name), listOf(value))
					Expression.name(name) to tree
				} else {
					value to tree
				}
			}
			else -> null
		}
		val branches = mutableListOf<Statement.If.Branch>()
		var otherwise: Block? = null
		for (entry in statement.entries) {
			val result = entry.expression ?: throw Rejection(entry, "A when branch without a body does not compile.")
			if (entry.isElse) {
				otherwise = withComments(unit.source.leading(entry), branch(result, sink))
				continue
			}
			val comments = unit.source.leading(entry)
			val tests = entry.conditions.map { condition(it, subject) }
			val matches = tests.reduce { left, right -> Expression.binary(left, BinaryOperator.OR, right) }
			// A guard, `is Monster if level > 100`, adds its test to the condition's
			val guard = entry.guard?.getExpression()
			val test = if (guard == null) matches else Expression.binary(matches, BinaryOperator.AND,
				body.expression(guard))
			branches += Statement.If.Branch(test, withComments(comments, branch(result, sink)))
		}
		if (branches.isEmpty()) {
			// Only an else: its code runs as it is
			return before + (otherwise?.entries()?.map { it.statement() } ?: listOf())
		}
		return before + Statement.If(branches, otherwise)
	}

	/** `size` for `values.size`, `kind` for `event.kind()`: the name of a subject read into a local. */
	private fun subjectName(subject: KtExpression): String {
		val selector = (subject as? KtQualifiedExpression)?.selectorExpression ?: subject
		val name = when (selector) {
			is KtNameReferenceExpression -> selector.getReferencedName()
			is KtCallExpression -> (selector.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
			else -> null
		}
		return name?.takeIf { selector !is KtCallExpression || selector.valueArguments.isEmpty() } ?: "selected"
	}

	/**
	 * A branch's block with the comments above its `when` entry ahead of those of its first statement, or, in a branch
	 * with no code, as its content.
	 */
	private fun withComments(comments: List<Comment>, block: Block): Block {
		if (comments.isEmpty()) {
			return block
		}
		val first = block.entries().firstOrNull() ?: return Block(listOf(), comments + block.closingComments())
		val trivia = Trivia(first.trivia().blankLineBefore(), comments + first.trivia().leading(),
			first.trivia().trailing())
		return Block(listOf(Block.Entry(first.statement(), trivia, first.origin())) + block.entries().drop(1),
			block.closingComments())
	}

	/** One condition of a `when` entry: equal to the subject, or in a range or collection; with no subject, a test. */
	private fun condition(condition: KtWhenCondition, subject: Pair<Expression, KtExpression>?): Expression {
		if (subject == null) {
			val test = (condition as? KtWhenConditionWithExpression)?.expression
				?: throw Rejection(condition, "A when without a subject takes conditions that are true or false.")
			return body.expression(test)
		}
		val (value, typed) = subject
		return when (condition) {
			is KtWhenConditionWithExpression -> Expression.binary(value, BinaryOperator.EQUAL,
				body.expression(condition.expression ?: throw Rejection(condition, EMPTY)))
			is KtWhenConditionInRange -> {
				val range = condition.rangeExpression ?: throw Rejection(condition, EMPTY)
				val test = body.operators.inRange(condition, typed, range, value)
				if (condition.isNegated) Expression.Unary(UnaryOperator.NOT, test) else test
			}
			is KtWhenConditionIsPattern -> {
				val type = with(unit.session) { condition.typeReference?.type }
					?: throw Rejection(condition, "This type test names no type.")
				val test = body.typeTests.typeTest(value, type, condition)
				if (condition.isNegated) Expression.Unary(UnaryOperator.NOT, test) else test
			}
			else -> throw Rejection.notYet(condition, "This kind of when condition")
		}
	}

	/**
	 * A branch's code. With a sink, its last expression gives the branch's value, unless it leaves the branch by
	 * itself, with `return`, `throw`, `break` or `continue`.
	 */
	private fun branch(code: KtExpression, sink: ((Expression) -> Statement)?): Block {
		if (sink == null) {
			return body.block(code)
		}
		val last = if (code is KtBlockExpression) code.statements.lastOrNull() else code
		if (last == null) {
			throw Rejection(code, "A branch that gives a value needs one.")
		}
		return if (jumps(last)) body.block(code) else body.block(code) { body.handedOn(it, sink) }
	}

	companion object {

		/** Whether code leaves where it is by itself: `return`, `throw`, `break` or `continue`. */
		fun jumps(code: KtExpression): Boolean =
			code is KtReturnExpression || code is KtThrowExpression || code is KtBreakExpression ||
				code is KtContinueExpression
	}
}

private const val EMPTY = "This expression is incomplete and does not compile."
