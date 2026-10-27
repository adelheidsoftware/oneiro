package org.adelheid.oneiro.kotlin.language

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.adelheid.oneiro.kotlin.core.SourceText
import org.adelheid.oneiro.lowering.DevSourceSet
import org.adelheid.oneiro.lowering.Diagnostic
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
 * Only code of the files lowered together decides about their enums: the dev source set's code cannot change what the
 * main set's Lua makes of the main set's.
 */
internal object ObjectEnums {

	/**
	 * Where code reads the order of an enum of the plugin's that the files it is lowered with do not declare.
	 *
	 * @property enum the enum's qualified name
	 */
	class OrderRead(private val where: PsiElement, private val name: String, val enum: String) {

		/** Why the read cannot be: the enum's constants are strings in its own source set's Lua. */
		fun diagnostic(): Diagnostic = SourceText(where.containingFile as KtFile)
			.diagnostic(Diagnostic.Severity.ERROR, where.textRange.startOffset, DevSourceSet.enumOrder(name))
	}

	/**
	 * Adds to [found] the enums of a file that have more than plain constants, and those whose order the file reads,
	 * where the files it is lowered with declare them.
	 *
	 * @param owned the files lowered together, the file among them
	 * @param elsewhere where the file reads the order of an enum of the plugin's that [owned] does not declare; null
	 *     to leave those out
	 */
	fun find(session: KaSession, file: KtFile, owned: Set<KtFile>, found: MutableSet<String>,
		elsewhere: MutableList<OrderRead>?): Unit = with(session) {
		// Marks an enum whose order the file reads at a place
		fun note(type: KaType?, where: PsiElement) {
			val symbol = type?.expandedSymbol ?: return
			val declared = symbol.psi?.containingFile as? KtFile ?: return
			val id = symbol.classId ?: return
			if (symbol.classKind != KaClassKind.ENUM_CLASS) {
				return
			}
			if (declared in owned) {
				found += id.asFqNameString()
			} else {
				elsewhere?.add(OrderRead(where, id.shortClassName.asString(), id.asFqNameString()))
			}
		}
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
			val read = reference.parent as? KtDotQualifiedExpression
				?: reference.parent?.parent as? KtDotQualifiedExpression ?: continue
			note(read.receiverExpression.expressionType, read)
		}
		for (comparison in PsiTreeUtil.collectElementsOfType(file, KtBinaryExpression::class.java)) {
			if (comparison.operationToken in ORDERED) {
				note(comparison.left?.expressionType, comparison)
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
					note(key.bodyExpression?.statements?.lastOrNull()?.expressionType, call)
				}
			}
			if (name !in NATURAL_SORTS || call.valueArguments.isNotEmpty() || !library) {
				continue
			}
			val receiver = (call.parent as? KtDotQualifiedExpression)?.receiverExpression?.expressionType
			val element = (receiver as? KaClassType)?.typeArguments?.firstOrNull()?.type
			note(element, call)
		}
	}

	/** Whether an enum has nothing but plain constants. */
	fun isSimple(declaration: KtClass): Boolean = declaration.primaryConstructorParameters.isEmpty() &&
		declaration.declarations.all { it is KtEnumEntry && it.body == null && it.initializerList == null }

	private val ORDERED = setOf(KtTokens.LT, KtTokens.GT, KtTokens.LTEQ, KtTokens.GTEQ)

	/** The library's functions that sort, or pick by, natural order, which an enum's order gives. */
	private val NATURAL_SORTS = setOf("sort", "sorted", "sortDescending", "sortedDescending", "max", "min",
		"maxOrNull", "minOrNull")

	/** The library's functions that order by the key a lambda gives. */
	private val KEYED = setOf("compareBy", "compareByDescending", "thenBy", "thenByDescending", "sortBy",
		"sortByDescending", "sortedBy", "sortedByDescending", "maxBy", "minBy", "maxByOrNull", "minByOrNull", "maxOf",
		"minOf", "maxOfOrNull", "minOfOrNull")
}
