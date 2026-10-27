package org.adelheid.oneiro.kotlin.language

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.adelheid.oneiro.kotlin.core.FileLowering
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtContainerNode
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPsiUtil

/**
 * A range a `for` loop counts over: `start until end` and `start ..< end`, which stop before the end; `start..end`,
 * which includes it; `start downTo end`, which counts down to it; any of them with `step`; and `list.indices`, the
 * positions of a list or an array.
 *
 * @property start the first value, or null for `indices`, which start at 0
 * @property end the end, or null for `indices`, which end before the collection's size
 * @property indicesOf the collection whose `indices` the loop counts over, or null
 * @property step the step as written, always positive, or null for 1
 */
internal class CountingRange(
	val start: KtExpression?,
	val end: KtExpression?,
	val inclusive: Boolean,
	val descending: Boolean,
	val step: KtExpression?,
	val indicesOf: KtExpression?,
) {

	/** Whether the range counts up by one from 0: the loops that may count from 1 in Lua instead. */
	val countsFromZero: Boolean
		get() = !descending && step == null &&
			(indicesOf != null || start is KtConstantExpression && start.text == "0")

	companion object {

		/** The loop's range, or null when it loops over something else. */
		fun of(loop: KtForExpression): CountingRange? = loop.loopRange?.let(::of)

		/** The range an expression is, or null when it is no range of integers. */
		fun of(range: KtExpression): CountingRange? {
			if (range is KtParenthesizedExpression) {
				return range.expression?.let(::of)
			}
			if (range is KtDotQualifiedExpression) {
				val selector = range.selectorExpression as? KtNameReferenceExpression ?: return null
				return if (selector.getReferencedName() == "indices") {
					CountingRange(null, null, false, false, null, range.receiverExpression)
				} else {
					null
				}
			}
			val binary = range as? KtBinaryExpression ?: return null
			val start = binary.left ?: return null
			val end = binary.right ?: return null
			val name = binary.operationReference.getReferencedName()
			return when {
				binary.operationToken == KtTokens.RANGE -> CountingRange(start, end, true, false, null, null)
				binary.operationToken == KtTokens.RANGE_UNTIL || name == "until" ->
					CountingRange(start, end, false, false, null, null)
				name == "downTo" -> CountingRange(start, end, true, true, null, null)
				name == "step" -> of(start)?.takeIf { it.step == null }?.let {
					CountingRange(it.start, it.end, it.inclusive, it.descending, end, it.indicesOf)
				}
				else -> null
			}
		}
	}
}

/**
 * Decides which loops count from 1 in Lua, by the Java front end's rule. A loop `for (i in 0 until n)` becomes
 * `for i = 1, n do` when its body uses `i` only as an index (`list[i]`), in `i + 1`, or compared with another loop
 * variable that counts from 1. Lua's `i` is then Kotlin's `i + 1`: every index loses its `+ 1`, `i + 1` becomes `i`,
 * and comparing two such variables keeps its meaning. The position of a list's element follows the same rule, in
 * `for ((index, name) in names.withIndex())` and the lambdas of `forEachIndexed`, `mapIndexed` and `filterIndexed`,
 * where it is `ipairs`'s own then. Whether a comparison qualifies depends on the other loop, so the whole file is
 * decided at once, dropping loops until every one left qualifies.
 */
internal object OneBasedLoops {

	fun find(unit: FileLowering): Set<KtNamedDeclaration> {
		// Each variable that may count from 1, with the code it is read in
		val candidates = mutableMapOf<KtNamedDeclaration, PsiElement?>()
		for (loop in PsiTreeUtil.collectElementsOfType(unit.file, KtForExpression::class.java)) {
			if (countsFromZero(loop)) {
				candidates[loop.loopParameter!!] = loop.body
			}
			val index = loop.destructuringDeclaration?.entries?.firstOrNull()
			if (index != null && index.name != "_" && indexedList(unit, loop.loopRange) != null) {
				candidates[index] = loop.body
			}
		}
		for (call in PsiTreeUtil.collectElementsOfType(unit.file, KtCallExpression::class.java)) {
			indexParameter(unit, call)?.let { (parameter, code) -> candidates[parameter] = code }
		}
		while (true) {
			val unfit = candidates.filter { (variable, code) -> !qualifies(unit, variable, code, candidates.keys) }
			if (unfit.isEmpty()) {
				return candidates.keys.toSet()
			}
			unfit.keys.forEach(candidates::remove)
		}
	}

	/** The list a loop walks the positions and elements of, `names` of `names.withIndex()`; null for another range. */
	fun indexedList(unit: FileLowering, range: KtExpression?): KtExpression? = with(unit.session) {
		val qualified = range?.let(KtPsiUtil::safeDeparenthesize) as? KtDotQualifiedExpression ?: return null
		val call = qualified.selectorExpression as? KtCallExpression ?: return null
		val id = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol?.callableId ?: return null
		if (id.packageName.asString() != "kotlin.collections" || id.callableName.asString() != "withIndex") {
			return null
		}
		val list = qualified.receiverExpression
		if (list.expressionType?.let(unit.types::isSequence) == true) list else null
	}

	/**
	 * The position parameter of the lambda of `forEachIndexed`, `mapIndexed` or `filterIndexed`, with the lambda's
	 * code; null for any other call, and for a lambda that leaves the position unnamed.
	 */
	private fun indexParameter(unit: FileLowering, call: KtCallExpression): Pair<KtParameter, PsiElement?>? =
		with(unit.session) {
			val id = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol?.callableId ?: return null
			if (id.packageName.asString() != "kotlin.collections" || id.callableName.asString() !in INDEXED) {
				return null
			}
			val lambda = call.lambdaArguments.singleOrNull()?.getLambdaExpression() ?: return null
			val index = lambda.functionLiteral.valueParameters.firstOrNull() ?: return null
			if (index.name == null || index.name == "_" || index.destructuringDeclaration != null) null
			else index to lambda.bodyExpression
		}

	private fun countsFromZero(loop: KtForExpression): Boolean {
		val range = CountingRange.of(loop) ?: return false
		return loop.loopParameter?.destructuringDeclaration == null && loop.loopParameter?.name != null &&
			range.countsFromZero
	}

	private fun qualifies(unit: FileLowering, variable: KtNamedDeclaration, code: PsiElement?,
			candidates: Set<KtNamedDeclaration>): Boolean {
		val body = code ?: return true
		return PsiTreeUtil.collectElementsOfType(body, KtNameReferenceExpression::class.java)
			.filter { declaration(unit, it) == variable }
			.all { fits(unit, it, candidates) }
	}

	/** Whether one use of the loop variable keeps its meaning when the variable counts from 1. */
	private fun fits(unit: FileLowering, use: KtNameReferenceExpression, candidates: Set<KtNamedDeclaration>):
		Boolean {
		val parent = use.parent
		if (parent is KtContainerNode) {
			val access = parent.parent as? KtArrayAccessExpression ?: return false
			val receiver = access.arrayExpression ?: return false
			val receiverType = with(unit.session) { receiver.expressionType } ?: return false
			return access.indexExpressions.singleOrNull() == use &&
				(unit.types.isSequence(receiverType) || unit.types.isString(receiverType))
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

	/** The functions whose lambda takes an element's position before the element. */
	private val INDEXED = setOf("forEachIndexed", "mapIndexed", "filterIndexed")
}
