package org.adelheid.oneiro.kotlin.library

import com.intellij.psi.util.PsiTreeUtil
import org.adelheid.oneiro.kotlin.core.FileLowering
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtThisExpression

/**
 * Which of Kotlin's maps and sets need not keep the order their keys were added in, because no code can see it: the
 * value of a `val`, a local or a private property, that the code reads, sets, tests and removes keys of, counts, and
 * tests for emptiness, but never walks, copies, returns, hands on or writes as text. Such a map or set is a plain
 * table, as `hashMapOf()` makes one, rather than one the helper `orderedTable()` makes, which keeps the order.
 */
internal class UnseenOrder(private val unit: FileLowering) {

	/** Whether nothing can see the order of the map or set a value makes; see [UnseenOrder]. */
	fun isUnseen(value: KtExpression): Boolean = with(unit.session) {
		val property = value.parent as? KtProperty ?: return false
		if (property.initializer != value || property.isVar || property.hasDelegate() || property.getter != null) {
			return false
		}
		if (!property.isLocal && !property.hasModifier(KtTokens.PRIVATE_KEYWORD)) {
			return false
		}
		val scope = if (property.isLocal) property.parent else property.containingKtFile
		PsiTreeUtil.collectElementsOfType(scope, KtNameReferenceExpression::class.java)
			.filter { it.getReferencedName() == property.name && it.resolveSymbol()?.psi == property }
			.all(::usesKeysAlone)
	}

	/**
	 * Whether a use of a map or set reads or changes it by a key, `stock[item]`, `item in seen` or
	 * `stock.remove(item)`, or counts it, which its order changes nothing about.
	 */
	private fun usesKeysAlone(reference: KtNameReferenceExpression): Boolean {
		// this.stock is the property, as stock is
		val qualified = reference.parent as? KtDotQualifiedExpression
		val use = if (qualified?.selectorExpression == reference && qualified.receiverExpression is KtThisExpression) {
			qualified
		} else {
			reference
		}
		return when (val parent = use.parent) {
			is KtArrayAccessExpression -> parent.arrayExpression == use
			is KtBinaryExpression -> parent.right == use && parent.operationToken in IN
			is KtDotQualifiedExpression -> parent.receiverExpression == use && memberName(parent) in BY_KEY
			else -> false
		}
	}

	/** The name of the member a qualified expression reads or calls, `remove` for `stock.remove(item)`. */
	private fun memberName(qualified: KtDotQualifiedExpression): String? =
		when (val selector = qualified.selectorExpression) {
			is KtNameReferenceExpression -> selector.getReferencedName()
			is KtCallExpression -> (selector.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
			else -> null
		}

	private companion object {

		val IN = setOf(KtTokens.IN_KEYWORD, KtTokens.NOT_IN)

		/** The members that read or change a map or set by a key, count it, or test it for emptiness. */
		val BY_KEY = setOf("get", "getValue", "getOrDefault", "getOrElse", "getOrPut", "set", "put", "remove",
			"containsKey", "contains", "add", "size", "isEmpty", "isNotEmpty", "clear")
	}
}
