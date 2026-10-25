package org.adelheid.oneiro.kotlin

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtThisExpression

/**
 * Which integer values of a file are known never to be negative, so that integer `/` and `%` on them can be Lua's `//`
 * and `%`, which agree with Kotlin's only for non-negative operands, and `shr` can be Lua's `>>`. A value is known so
 * when it is a non-negative literal, a collection's `size` or a string's `length`, an `abs`, a sum, product, quotient
 * or remainder of such values, or it names a variable, property or function of the file that only ever holds or
 * returns such values, as a counter or a constant does.
 */
internal class NonNegative(private val unit: FileLowering) {

	/** What is known of each declaration so far; false while it is being worked out, for one that rests on itself. */
	private val known = mutableMapOf<PsiElement, Boolean>()

	fun isKnown(expression: KtExpression): Boolean = when (expression) {
		is KtConstantExpression -> expression.node.elementType == KtNodeTypes.INTEGER_CONSTANT &&
			!expression.text.startsWith("-")
		is KtParenthesizedExpression -> expression.expression?.let(::isKnown) == true
		is KtBinaryExpression -> expression.operationToken in KEEPING && expression.left?.let(::isKnown) == true &&
			expression.right?.let(::isKnown) == true
		is KtNameReferenceExpression -> declaration(expression)?.let(::holdsNonNegative) == true
		is KtDotQualifiedExpression -> qualified(expression)
		is KtCallExpression -> call(expression)
		else -> false
	}

	/** `list.size`, `text.length`, `this.count`, or a call through a receiver. */
	private fun qualified(expression: KtDotQualifiedExpression): Boolean {
		val selector = expression.selectorExpression
		if (selector is KtCallExpression) {
			return call(selector)
		}
		if (selector !is KtNameReferenceExpression) {
			return false
		}
		val receiverType = with(unit.session) { expression.receiverExpression.expressionType } ?: return false
		val name = selector.getReferencedName()
		val sized = name == "size" && (unit.types.isSequence(receiverType) || unit.types.isMap(receiverType) ||
			unit.types.isSet(receiverType))
		if (sized || name == "length" && unit.types.isString(receiverType)) {
			return true
		}
		return expression.receiverExpression is KtThisExpression &&
			declaration(selector)?.let(::holdsNonNegative) == true
	}

	/** `abs(x)`, or a call of a function of the file that returns only non-negative integers. */
	private fun call(call: KtCallExpression): Boolean = with(unit.session) {
		val function = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol ?: return false
		val id = function.callableId
		if (id?.callableName?.asString() == "abs" && id.packageName.asString() == "kotlin.math") {
			return true
		}
		val declaration = function.psi as? KtNamedFunction ?: return false
		declaration.containingFile == unit.file && holdsNonNegative(declaration)
	}

	private fun holdsNonNegative(declaration: PsiElement): Boolean {
		known[declaration]?.let { return it }
		known[declaration] = false
		val result = when (declaration) {
			is KtProperty -> property(declaration)
			is KtParameter -> loopVariable(declaration)
			is KtNamedFunction -> returnsNonNegative(declaration)
			else -> false
		}
		known[declaration] = result
		return result
	}

	/**
	 * A property or local that starts non-negative and is only ever raised where it can be written: in its block for a
	 * local, in the file for a private or file-level property; a `val` is never written again.
	 */
	private fun property(property: KtProperty): Boolean = with(unit.session) {
		if (!unit.types.isIntegral(property.symbol.returnType)) {
			return false
		}
		val initializer = property.initializer ?: return false
		if (!isKnown(initializer)) {
			return false
		}
		if (!property.isVar) {
			return true
		}
		val scope = when {
			property.isLocal -> property.parent
			property.isPrivateInLua() -> unit.file
			// Code in other files may lower a property they can see
			else -> return false
		}
		onlyRaises(scope, property)
	}

	/**
	 * A loop variable that counts up from a non-negative start, as `for (i in 0 until n)` does, or down to a
	 * non-negative end.
	 */
	private fun loopVariable(parameter: KtParameter): Boolean {
		val loop = parameter.parent as? KtForExpression ?: return false
		if (loop.loopParameter != parameter) {
			return false
		}
		val range = CountingRange.of(loop) ?: return false
		val from = if (range.descending) range.end else range.start
		return parameter in unit.oneBased || range.indicesOf != null || from?.let(::isKnown) == true
	}

	/**
	 * Whether every call of a function returns a non-negative integer: one that nothing overrides, each of whose
	 * values, outside the lambdas in it, is known never to be negative.
	 */
	private fun returnsNonNegative(function: KtNamedFunction): Boolean = with(unit.session) {
		if (!unit.types.isIntegral(function.symbol.returnType) || function.hasModifier(KtTokens.OPEN_KEYWORD) ||
			function.hasModifier(KtTokens.OVERRIDE_KEYWORD) || function.hasModifier(KtTokens.ABSTRACT_KEYWORD)) {
			return false
		}
		val body = function.bodyExpression ?: return false
		if (!function.hasBlockBody()) {
			return isKnown(body)
		}
		val returned = PsiTreeUtil.collectElementsOfType(body, KtReturnExpression::class.java)
			.filter { PsiTreeUtil.getParentOfType(it, KtLambdaExpression::class.java, KtNamedFunction::class.java) ==
				function }
			.map { it.returnedExpression ?: return false }
		returned.isNotEmpty() && returned.all(::isKnown)
	}

	/**
	 * Whether every write to the variable in the scope keeps a non-negative value non-negative: `++`; `+=`, `*=`, `/=`
	 * or `%=` with a non-negative value; or `=` of one.
	 */
	private fun onlyRaises(scope: PsiElement, variable: KtProperty): Boolean {
		for (assignment in PsiTreeUtil.collectElementsOfType(scope, KtBinaryExpression::class.java)) {
			val token = assignment.operationToken
			if (token !in ASSIGNMENTS || !writes(assignment.left, variable)) {
				continue
			}
			if (token !in RAISING || assignment.right?.let(::isKnown) != true) {
				return false
			}
		}
		val steps = PsiTreeUtil.collectElementsOfType(scope, KtPostfixExpression::class.java).map {
			it.operationToken to it.baseExpression
		} + PsiTreeUtil.collectElementsOfType(scope, KtPrefixExpression::class.java).map {
			it.operationToken to it.baseExpression
		}
		return steps.none { (token, base) -> token == KtTokens.MINUSMINUS && writes(base, variable) }
	}

	/** Whether an assignment's target names the variable, as `count` or `this.count`. */
	private fun writes(target: KtExpression?, variable: KtProperty): Boolean {
		val name = when (target) {
			is KtNameReferenceExpression -> target
			is KtDotQualifiedExpression -> target.selectorExpression as? KtNameReferenceExpression
			else -> null
		}
		return name != null && declaration(name) == variable
	}

	private fun declaration(reference: KtNameReferenceExpression): PsiElement? =
		with(unit.session) { reference.resolveSymbol()?.psi }

	private companion object {
		/** The operators whose result is non-negative when both operands are. */
		val KEEPING = setOf(KtTokens.PLUS, KtTokens.MUL, KtTokens.DIV, KtTokens.PERC)
		val ASSIGNMENTS = setOf(KtTokens.EQ, KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ,
			KtTokens.PERCEQ)
		val RAISING = setOf(KtTokens.EQ, KtTokens.PLUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ)
	}
}
