package org.adelheid.oneiro.kotlin

import com.intellij.psi.PsiElement
import org.adelheid.oneiro.lowering.ReviewAreas
import org.adelheid.oneiro.lowering.SourceLanguage
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Warnings at uses of the API that Jagex's moderators review closely, which the bindings mark `@LuaReview` with an
 * area. Each warning names what moderation looks at in that area, so that the developer meets it before submitting
 * rather than in a moderator's report. Once the plugin does, `@Suppress("oneiro:<area>")` on a function, class or file
 * around the use silences the area's warnings there, as it silences Kotlin's own. The dev source set never ships, so
 * its uses are not warned about.
 */
internal class ReviewWarnings(private val unit: FileLowering) {

	/**
	 * Warns at a use of a reviewed member, unless the code around it silences the member's area.
	 *
	 * @param marked the member, or the event payload, that may carry `@LuaReview`
	 * @param use the use as the warning starts it: `Chat.AddMessage`, or `Subscribing to ChatMessage`
	 */
	fun check(marked: KaSymbol, use: String, where: PsiElement) {
		val area = unit.annotations.value(marked, Annotations.LUA_REVIEW) ?: return
		if (unit.options.plugin.dev() || isSuppressed(where, ReviewAreas.key(area))) {
			return
		}
		unit.warning(where, ReviewAreas.warning(use, area, SourceLanguage.KOTLIN))
	}

	/** Whether a declaration around the use, or its file, carries `@Suppress` with the area's key. */
	private fun isSuppressed(where: PsiElement, key: String): Boolean =
		generateSequence(where) { it.parent }.filterIsInstance<KtAnnotated>().any { declaration ->
			declaration.annotationEntries.any { entry ->
				entry.shortName?.asString() == "Suppress" && entry.valueArguments.any { argument ->
					(argument.getArgumentExpression() as? KtStringTemplateExpression)?.entries
						?.joinToString("") { it.text } == key
				}
			}
		}
}
