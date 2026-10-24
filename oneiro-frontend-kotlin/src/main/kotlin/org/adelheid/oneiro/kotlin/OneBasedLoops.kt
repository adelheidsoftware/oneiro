package org.adelheid.oneiro.kotlin

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtContainerNode
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParameter

/**
 * A range a `for` loop counts over by one: `start until end` and `start ..< end`, which stop before the end, and
 * `start..end`, which includes it.
 */
internal class CountingRange(val start: KtExpression, val end: KtExpression, val inclusive: Boolean) {

	companion object {

		/** The loop's range, or null when it loops over something else. */
		fun of(loop: KtForExpression): CountingRange? {
			val range = loop.loopRange as? KtBinaryExpression ?: return null
			val start = range.left ?: return null
			val end = range.right ?: return null
			return when {
				range.operationToken == KtTokens.RANGE -> CountingRange(start, end, true)
				range.operationToken == KtTokens.RANGE_UNTIL -> CountingRange(start, end, false)
				range.operationReference.getReferencedName() == "until" -> CountingRange(start, end, false)
				else -> null
			}
		}
	}
}

/**
 * Decides which loops count from 1 in Lua, by the Java front end's rule. A loop `for (i in 0 until n)` becomes
 * `for i = 1, n do` when its body uses `i` only as an index (`list[i]`), in `i + 1`, or compared with another loop
 * variable that counts from 1. Lua's `i` is then Kotlin's `i + 1`: every index loses its `+ 1`, `i + 1` becomes `i`,
 * and comparing two such variables keeps its meaning. Whether a comparison qualifies depends on the other loop, so the
 * whole file is decided at once, dropping loops until every one left qualifies.
 */
internal object OneBasedLoops {

	fun find(unit: FileLowering): Set<KtParameter> {
		val candidates = PsiTreeUtil.collectElementsOfType(unit.file, KtForExpression::class.java)
			.filter { countsFromZero(it) }
			.associateBy { it.loopParameter!! }
			.toMutableMap()
		while (true) {
			val unfit = candidates.filter { (parameter, loop) -> !qualifies(unit, parameter, loop, candidates.keys) }
			if (unfit.isEmpty()) {
				return candidates.keys.toSet()
			}
			unfit.keys.forEach(candidates::remove)
		}
	}

	private fun countsFromZero(loop: KtForExpression): Boolean {
		val range = CountingRange.of(loop) ?: return false
		return loop.loopParameter?.destructuringDeclaration == null && loop.loopParameter?.name != null &&
			range.start is KtConstantExpression && range.start.text == "0"
	}

	private fun qualifies(unit: FileLowering, parameter: KtParameter, loop: KtForExpression,
			candidates: Set<KtParameter>): Boolean {
		val body = loop.body ?: return true
		return PsiTreeUtil.collectElementsOfType(body, KtNameReferenceExpression::class.java)
			.filter { declaration(unit, it) == parameter }
			.all { fits(unit, it, candidates) }
	}

	/** Whether one use of the loop variable keeps its meaning when the variable counts from 1. */
	private fun fits(unit: FileLowering, use: KtNameReferenceExpression, candidates: Set<KtParameter>): Boolean {
		val parent = use.parent
		if (parent is KtContainerNode) {
			val access = parent.parent as? KtArrayAccessExpression ?: return false
			val receiver = access.arrayExpression ?: return false
			val receiverType = with(unit.session) { receiver.expressionType } ?: return false
			return access.indexExpressions.singleOrNull() == use && unit.types.isList(receiverType)
		}
		if (parent !is KtBinaryExpression) {
			return false
		}
		val other = (if (parent.left == use) parent.right else parent.left) ?: return false
		return when (parent.operationToken) {
			KtTokens.PLUS -> other is KtConstantExpression && other.text == "1" &&
				with(unit.session) { parent.expressionType }?.let(unit.types::isIntegral) == true
			KtTokens.EQEQ, KtTokens.EXCLEQ, KtTokens.LT, KtTokens.LTEQ, KtTokens.GT, KtTokens.GTEQ ->
				other is KtNameReferenceExpression && declaration(unit, other) in candidates
			else -> false
		}
	}

	private fun declaration(unit: FileLowering, reference: KtNameReferenceExpression): Any? =
		with(unit.session) { reference.resolveSymbol()?.psi }
}
