package org.adelheid.oneiro.kotlin

import com.intellij.psi.PsiElement

/**
 * Thrown while lowering when a construct has no Lua translation, yet or ever. The member being lowered is skipped and
 * the rejection becomes an error at the element's position, so one run reports every problem in a file.
 */
internal class Rejection(val element: PsiElement, override val message: String) :
	RuntimeException(message, null, false, false) {

	companion object {

		/** The page that lists the Kotlin Oneiro supports, which messages point plugin authors to. */
		const val SUPPORTED_KOTLIN = "https://github.com/adelheidsoftware/oneiro/blob/main/docs/kotlin-subset.md"

		/** A construct that Lua could express but Oneiro does not translate yet. */
		fun notYet(element: PsiElement, construct: String): Rejection =
			Rejection(element, "$construct is not supported yet; $SUPPORTED_KOTLIN lists the Kotlin that is.")
	}
}
