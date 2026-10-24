package org.adelheid.oneiro.kotlin

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * Finds the plugin's enums whose constants are objects rather than the strings of their names. An enum's constants
 * become objects when the enum has anything but plain constants (a constructor, properties, functions or constant
 * bodies), or when code reads their order, through `ordinal`, `compareTo`, a comparison such as `a < b`, a sort in
 * natural order, or a key that a sort or a comparator orders by, `sortedBy { it.herb }`. Strings would sort by name.
 */
internal object ObjectEnums {

	fun find(session: KaSession, file: KtFile, found: MutableSet<String>): Unit = with(session) {
		for (declaration in PsiTreeUtil.collectElementsOfType(file, KtClass::class.java)) {
			if (declaration.isEnum() && !isSimple(declaration)) {
				declaration.fqName?.asString()?.let(found::add)
			}
		}
		for (reference in PsiTreeUtil.collectElementsOfType(file, KtNameReferenceExpression::class.java)) {
			val name = reference.getReferencedName()
			if (name != "ordinal" && name != "compareTo") {
				continue
			}
			val symbol = reference.resolveSymbol() ?: continue
			if ((symbol.containingSymbol as? KaClassSymbol)?.classId?.asFqNameString() != "kotlin.Enum") {
				continue
			}
			val receiver = (reference.parent as? KtDotQualifiedExpression)?.receiverExpression
				?: (reference.parent?.parent as? KtDotQualifiedExpression)?.receiverExpression
			note(receiver?.expressionType, found)
		}
		for (comparison in PsiTreeUtil.collectElementsOfType(file, KtBinaryExpression::class.java)) {
			if (comparison.operationToken in ORDERED) {
				note(comparison.left?.expressionType, found)
			}
		}
		for (call in PsiTreeUtil.collectElementsOfType(file, KtCallExpression::class.java)) {
			val function = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol ?: continue
			val id = function.callableId ?: continue
			val name = id.callableName.asString()
			val library = id.packageName.asString().let { it == "kotlin.collections" || it == "kotlin.comparisons" }
			if (library && name in KEYED) {
				for (argument in call.valueArguments) {
					val key = argument.getArgumentExpression() as? KtLambdaExpression ?: continue
					note(key.bodyExpression?.statements?.lastOrNull()?.expressionType, found)
				}
			}
			if (name !in NATURAL_SORTS || call.valueArguments.isNotEmpty() || !library) {
				continue
			}
			val receiver = (call.parent as? KtDotQualifiedExpression)?.receiverExpression?.expressionType
			val element = (receiver as? KaClassType)?.typeArguments?.firstOrNull()?.type
			note(element, found)
		}
	}

	/** Whether an enum has nothing but plain constants. */
	fun isSimple(declaration: KtClass): Boolean = declaration.primaryConstructorParameters.isEmpty() &&
		declaration.declarations.all { it is KtEnumEntry && it.body == null && it.initializerList == null }

	private fun KaSession.note(type: KaType?, found: MutableSet<String>) {
		val symbol = type?.expandedSymbol ?: return
		if (symbol.classKind == KaClassKind.ENUM_CLASS && symbol.psi?.containingFile is KtFile) {
			symbol.classId?.asFqNameString()?.let(found::add)
		}
	}

	private val ORDERED = setOf(KtTokens.LT, KtTokens.GT, KtTokens.LTEQ, KtTokens.GTEQ)

	/** The library's functions that sort, or pick by, natural order, which an enum's order gives. */
	private val NATURAL_SORTS = setOf("sort", "sorted", "sortDescending", "sortedDescending", "max", "min",
		"maxOrNull", "minOrNull")

	/** The library's functions that order by the key a lambda gives. */
	private val KEYED = setOf("compareBy", "compareByDescending", "thenBy", "thenByDescending", "sortBy",
		"sortByDescending", "sortedBy", "sortedByDescending", "maxBy", "minBy", "maxByOrNull", "minByOrNull", "maxOf",
		"minOf", "maxOfOrNull", "minOfOrNull")
}
