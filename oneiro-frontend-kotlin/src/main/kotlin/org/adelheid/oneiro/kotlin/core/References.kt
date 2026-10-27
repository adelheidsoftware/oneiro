package org.adelheid.oneiro.kotlin.core

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty

/**
 * The declarations of the file that a member's code uses: its functions, properties, classes and constructors, by
 * which [org.adelheid.oneiro.lowering.ModuleLayout] puts code that runs while the module loads after what it uses, and
 * declares ahead a local used above its definition. A constructor stands for its class too.
 */
internal object References {

	fun of(unit: FileLowering, element: PsiElement?): Set<PsiElement> {
		if (element == null) {
			return setOf()
		}
		val found = mutableSetOf<PsiElement>()
		for (reference in PsiTreeUtil.collectElementsOfType(element, KtNameReferenceExpression::class.java)) {
			val declaration = with(unit.session) { reference.resolveSymbol()?.psi } ?: continue
			if (declaration.containingFile != unit.file || !isMember(declaration)) {
				continue
			}
			found += declaration
			if (declaration is KtPrimaryConstructor) {
				found += declaration.getContainingClassOrObject()
			}
		}
		return found
	}

	/** Whether a declaration is a member of a class or object, or one of the file's own, rather than a local. */
	private fun isMember(declaration: PsiElement): Boolean = when (declaration) {
		is KtClassOrObject, is KtPrimaryConstructor, is KtEnumEntry -> true
		is KtNamedFunction, is KtProperty -> declaration.parent is KtClassBody || declaration.parent is KtFile
		else -> false
	}
}
