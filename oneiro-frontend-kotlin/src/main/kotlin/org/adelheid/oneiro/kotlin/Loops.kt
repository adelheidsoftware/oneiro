package org.adelheid.oneiro.kotlin

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.adelheid.oneiro.lowering.IndexArithmetic
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.Statement
import org.adelheid.oneiro.lua.UnaryOperator
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtExpressionWithLabel
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtWhileExpression

/**
 * Kotlin's loops as Lua's: a loop over a range of integers as a numeric `for`, a loop over a collection as a generic
 * `for` with `ipairs` for a list or an array and `pairs` for a set or a map, `while` as `while`, and `do`-`while` as
 * `repeat ... until not`. Lua has `break` but no `continue`, so a `continue` jumps to a label at the end of its loop's
 * body.
 */
internal class Loops(private val body: BodyLowering) {

	private val unit = body.unit

	/** The labels the loops being lowered end their bodies with, innermost last; null for a loop without one. */
	private val enclosing = ArrayDeque<String?>()

	fun forLoop(loop: KtForExpression): Statement {
		val range = CountingRange.of(loop)
		return if (range != null) counting(loop, range) else forEach(loop)
	}

	/**
	 * A loop over a range of integers, as a numeric `for`, counting from 1 when the file's loops allow it:
	 * `0 until n` is `for i = 0, n - 1 do`, `from downTo 1` is `for i = from, 1, -1 do`, and `0..limit step 2` is
	 * `for i = 0, limit, 2 do`.
	 */
	private fun counting(loop: KtForExpression, range: CountingRange): Statement {
		val parameter = loop.loopParameter?.takeIf { it.destructuringDeclaration == null }
			?: throw Rejection(loop, "A range's integers cannot be destructured.")
		val name = unit.names.luaName(parameter.name!!)
		val start = range.start?.let(body::expression) ?: Expression.integer(0)
		// The end of indices is the collection's size, which they stop before
		val end = range.end?.let(body::expression) ?: LibraryCalls.length(body.expression(range.indicesOf!!))
		val block = loopBody(loop)
		if (parameter in unit.oneBased) {
			// Lua's variable is Kotlin's plus one, so its range is one higher
			val last = if (range.inclusive) IndexArithmetic.plusOne(end) else end
			return Statement.NumericFor(name, IndexArithmetic.plusOne(start), last, null, block)
		}
		val step = range.step?.let(body::expression)
		if (range.descending) {
			return Statement.NumericFor(name, start, end, negated(step ?: Expression.integer(1)), block)
		}
		val last = if (range.inclusive) end else IndexArithmetic.minusOne(end)
		return Statement.NumericFor(name, start, last, step, block)
	}

	/** A step that counts down: `-2` for a step of 2. */
	private fun negated(step: Expression): Expression {
		val literal = IndexArithmetic.integerValue(step) ?: return Expression.Unary(UnaryOperator.NEGATE, step)
		return Expression.integer(-literal)
	}

	/**
	 * `for (x in list)` as `for _, x in ipairs(list)`, `for (x in set)` as `for x in pairs(set)`, and
	 * `for ((key, value) in map)` as `for key, value in pairs(map)`; a loop over a map's `keys` or `values` is the
	 * same loop with one variable.
	 */
	private fun forEach(loop: KtForExpression): Statement {
		val collection = loop.loopRange ?: throw Rejection(loop, "A for loop without a collection does not compile.")
		val variables = variables(loop)
		if (with(unit.session) { collection.expressionType }?.let(unit.types::isString) == true) {
			// A text, walked one char at a time
			val characters = Expression.methodCall(body.expression(collection), "gmatch", Expression.string("."))
			return Statement.GenericFor(listOf(single(loop, variables)), listOf(characters), loopBody(loop))
		}
		val view = mapView(collection)
		val kind = if (view != null) null else body.library.kind(with(unit.session) { collection.expressionType })
		val names = when {
			view != null && view.selector == "keys" -> listOf(single(loop, variables))
			view != null && view.selector == "values" -> listOf("_", single(loop, variables))
			view != null -> pair(loop, variables)
			kind == LibraryCalls.Kind.SEQUENCE -> listOf("_", single(loop, variables))
			kind == LibraryCalls.Kind.SET -> listOf(single(loop, variables))
			kind == LibraryCalls.Kind.MAP -> pair(loop, variables)
			else -> throw Rejection.notYet(collection, "A for loop over this kind of value")
		}
		val walk = if (kind == LibraryCalls.Kind.SEQUENCE) "ipairs" else "pairs"
		val iterated = body.expression(view?.map ?: collection)
		return Statement.GenericFor(names, listOf(Expression.call(Expression.name(walk), iterated)), loopBody(loop))
	}

	/** `while (condition) { ... }`. */
	fun whileLoop(loop: KtWhileExpression): Statement {
		val condition = loop.condition ?: throw Rejection(loop, "A while loop without a condition does not compile.")
		return Statement.While(body.expression(condition), loopBody(loop))
	}

	/** `do { ... } while (condition)` as `repeat ... until not (condition)`, which Lua tests after the body too. */
	fun doWhileLoop(loop: KtDoWhileExpression): Statement {
		val condition = loop.condition ?: throw Rejection(loop, "A do-while loop without a condition does not compile.")
		val block = loopBody(loop)
		return Statement.Repeat(block, Expression.Unary(UnaryOperator.NOT, body.expression(condition)))
	}

	/** `break`, which leaves the innermost loop, as Lua's does. */
	fun breakStatement(jump: KtExpressionWithLabel): Statement {
		checkUnlabelled(jump)
		return Statement.Break()
	}

	/** `continue` as `goto continue`, to the label at the end of the innermost loop's body. */
	fun continueStatement(jump: KtContinueExpression): Statement {
		checkUnlabelled(jump)
		val label = enclosing.lastOrNull() ?: throw IllegalStateException("continue outside a loop scanned for it")
		return Statement.Goto(label)
	}

	private fun checkUnlabelled(jump: KtExpressionWithLabel) {
		if (jump.getTargetLabel() != null) {
			throw Rejection(jump, "Lua's break and continue only reach the innermost loop; move the loops into a"
				+ " function and return from it.")
		}
	}

	/** A loop's body, with the label its `continue`s jump to as its last statement. */
	private fun loopBody(loop: KtLoopExpression): Block {
		val code = loop.body ?: throw Rejection(loop, "A loop without a body does not compile.")
		val label = if (usesContinue(loop)) continueLabel() else null
		enclosing.addLast(label)
		val lowered = try {
			body.block(code)
		} finally {
			enclosing.removeLast()
		}
		if (label == null) {
			return lowered
		}
		return Block(lowered.entries() + Block.Entry.of(Statement.Label(label)), lowered.closingComments())
	}

	/**
	 * The label for a loop's `continue`: `continue`, or `continue2` and up inside a loop that already has one, because
	 * Lua forbids a label where one of the same name is visible.
	 */
	private fun continueLabel(): String {
		val labelled = enclosing.count { it != null }
		return if (labelled == 0) "continue" else "continue${labelled + 1}"
	}

	/** Whether a `continue` in the loop's body continues this loop, not one nested in it or in a lambda inside. */
	private fun usesContinue(loop: KtLoopExpression): Boolean =
		PsiTreeUtil.collectElementsOfType(loop.body ?: return false, KtContinueExpression::class.java).any { jump ->
			innermostLoop(jump) == loop
		}

	private fun innermostLoop(jump: PsiElement): PsiElement? =
		PsiTreeUtil.getParentOfType(jump, KtLoopExpression::class.java, KtFunction::class.java)

	/** The Lua names of the loop's variable, or of the variables it destructures into. */
	private fun variables(loop: KtForExpression): List<String> {
		val destructured = loop.destructuringDeclaration
		if (destructured != null) {
			return destructured.entries.map { unit.names.luaName(it.name ?: "_") }
		}
		return listOf(unit.names.luaName(loop.loopParameter?.name ?: "_"))
	}

	private fun single(loop: KtForExpression, variables: List<String>): String =
		variables.singleOrNull() ?: throw Rejection.notYet(loop, "Destructuring each element of this collection")

	/** A map's key and value: `for ((key, value) in map)`. */
	private fun pair(loop: KtForExpression, variables: List<String>): List<String> {
		if (variables.size != 2) {
			throw Rejection.notYet(loop, "A loop over a map's entries that does not destructure them; write for ((key,"
				+ " value) in map) instead")
		}
		return variables
	}

	/** `map.keys`, `map.values` or `map.entries`, which a loop walks on the map itself. */
	private class MapView(val map: KtExpression, val selector: String)

	private fun mapView(collection: KtExpression): MapView? {
		val qualified = collection as? KtDotQualifiedExpression ?: return null
		val selector = (qualified.selectorExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
		val receiverType = with(unit.session) { qualified.receiverExpression.expressionType }
		val view = selector in setOf("keys", "values", "entries")
		if (!view || receiverType == null || !unit.types.isMap(receiverType)) {
			return null
		}
		return MapView(qualified.receiverExpression, selector)
	}
}
