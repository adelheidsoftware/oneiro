package org.adelheid.oneiro.kotlin

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.adelheid.oneiro.lowering.OptionalDependencies
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtWhileExpression

/**
 * The rule on an optional dependency ([OptionalDependencies]), as the Java front end holds code to it: code that
 * reaches its global, a static member of its class (`@LuaOptional`) or of a class nested in it, compiles only where a
 * check that it is loaded reaches it, in the same function: inside `if (gauges.isLoaded())` or a loop on it, in the
 * `else` of the negated check, on the right of `gauges.isLoaded() &&`, or after `if (!gauges.isLoaded()) return`
 * or another exit. The search stops at a lambda, a function or a class, whose code runs later.
 */
internal class OptionalChecks(private val unit: FileLowering) {

	private val annotations = unit.annotations

	/** The alias of the optional dependency whose `isLoaded()` an expression calls; null for any other. */
	fun loaded(expression: KtExpression): String? = with(unit.session) {
		val inner = KtPsiUtil.safeDeparenthesize(expression)
		val call = (inner as? KtQualifiedExpression)?.selectorExpression as? KtCallExpression
			?: inner as? KtCallExpression ?: return null
		val function = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol as? KaNamedFunctionSymbol
		return function?.let(::loadedCheck)
	}

	/** The alias of the optional dependency whose check a function is, its global's `isLoaded()`; null otherwise. */
	fun loadedCheck(function: KaNamedFunctionSymbol): String? {
		if (function.name.asString() != "isLoaded" || function.valueParameters.isNotEmpty() || !function.isStatic) {
			return null
		}
		val owner = ApiCalls(unit).owner(function) ?: return null
		return if (annotations.has(owner, Annotations.LUA_OPTIONAL)) {
			annotations.value(owner, Annotations.LUA_GLOBAL)
		} else {
			null
		}
	}

	/** Rejects a use of an optional dependency's member where no check that it is loaded reaches. */
	fun check(owner: KaClassSymbol, where: PsiElement): Unit = with(unit.session) {
		var outermost = owner
		while (true) {
			outermost = outermost.classId?.outerClassId?.let { findClass(it) } ?: break
		}
		if (!annotations.has(outermost, Annotations.LUA_OPTIONAL)) {
			return
		}
		val alias = annotations.value(outermost, Annotations.LUA_GLOBAL) ?: return
		if (!reached(where, alias)) {
			throw Rejection(where, OptionalDependencies.unchecked(alias))
		}
	}

	private fun reached(use: PsiElement, alias: String): Boolean {
		var child = use
		var parent = use.parent
		while (parent != null && parent !is KtFile) {
			if (parent is KtFunctionLiteral || parent is KtLambdaExpression || parent is KtNamedFunction ||
				parent is KtPropertyAccessor || parent is KtClassOrObject || parent is KtObjectLiteralExpression) {
				// Code there runs later, when the library may be gone
				return false
			}
			if (checksChild(parent, child, alias)) {
				return true
			}
			child = parent
			parent = parent.parent
		}
		return false
	}

	/** Whether an expression checks that the dependency is loaded for one of its parts, `child`. */
	private fun checksChild(parent: PsiElement, child: PsiElement, alias: String): Boolean = when (parent) {
		is KtIfExpression -> (inside(child, parent.then) && checks(parent.condition, alias, true)) ||
			(inside(child, parent.`else`) && checks(parent.condition, alias, false))
		is KtWhileExpression -> inside(child, parent.body) && checks(parent.condition, alias, true)
		is KtBinaryExpression -> child == parent.right &&
			((parent.operationToken == KtTokens.ANDAND && checks(parent.left, alias, true)) ||
				(parent.operationToken == KtTokens.OROR && checks(parent.left, alias, false)))
		is KtBlockExpression -> parent.statements.takeWhile { it != child }.any { exitsUnloaded(it, alias) }
		else -> false
	}

	/** Whether `child`, a part of an `if` or a loop, holds its branch or body, which the PSI wraps in a node. */
	private fun inside(child: PsiElement, branch: KtExpression?): Boolean =
		branch != null && PsiTreeUtil.isAncestor(child, branch, false)

	/**
	 * Whether a condition tells that the dependency is loaded: when it holds, or for `holds` false, when it fails, as
	 * `!gauges.isLoaded()` does.
	 */
	private fun checks(condition: KtExpression?, alias: String, holds: Boolean): Boolean {
		val inner = KtPsiUtil.safeDeparenthesize(condition ?: return false)
		if (loaded(inner) == alias) {
			return holds
		}
		return when {
			inner is KtPrefixExpression && inner.operationToken == KtTokens.EXCL ->
				checks(inner.baseExpression, alias, !holds)
			inner is KtBinaryExpression && inner.operationToken == KtTokens.ANDAND ->
				holds && (checks(inner.left, alias, true) || checks(inner.right, alias, true))
			inner is KtBinaryExpression && inner.operationToken == KtTokens.OROR ->
				!holds && (checks(inner.left, alias, false) || checks(inner.right, alias, false))
			else -> false
		}
	}

	/** Whether a statement leaves the code that follows it where the dependency is not loaded. */
	private fun exitsUnloaded(statement: KtExpression, alias: String): Boolean =
		statement is KtIfExpression && statement.`else` == null && checks(statement.condition, alias, false) &&
			exits(statement.then)

	private fun exits(expression: KtExpression?): Boolean = when (expression) {
		is KtReturnExpression, is KtThrowExpression, is KtBreakExpression, is KtContinueExpression -> true
		is KtBlockExpression -> exits(expression.statements.lastOrNull())
		else -> false
	}
}
