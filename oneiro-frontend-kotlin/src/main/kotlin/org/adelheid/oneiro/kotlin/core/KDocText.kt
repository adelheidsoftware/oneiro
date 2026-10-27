package org.adelheid.oneiro.kotlin.core

import org.jetbrains.kotlin.kdoc.psi.api.KDoc

/** The text of KDoc comments as Lua comments carry it: descriptions, and the text of `@param` and `@return`. */
internal object KDocText {

	/**
	 * The description, before any tag, as lines. The indentation the lines share is removed and the rest kept, so an
	 * indented continuation of a list item stays indented.
	 */
	fun description(doc: KDoc): List<String> = lines(doc.getDefaultSection().getContent())

	/**
	 * The text of the `@param` tag for a parameter, or of the `@property` tag a class gives a constructor's property,
	 * on one line; empty without one.
	 */
	fun param(doc: KDoc, parameter: String): String {
		val section = doc.getDefaultSection()
		val tag = (section.findTagsByName("param") + section.findTagsByName("property"))
			.firstOrNull { it.getSubjectName() == parameter }
		return tag?.let { lines(it.getContent()).joinToString(" ") }.orEmpty()
	}

	/** The text of the `@return` tag, on one line; empty without one. */
	fun returned(doc: KDoc): String =
		doc.getDefaultSection().findTagByName("return")?.let { lines(it.getContent()).joinToString(" ") }.orEmpty()

	private fun lines(text: String): List<String> {
		val lines = text.lines().map(String::trimEnd).toMutableList()
		while (lines.isNotEmpty() && lines.first().isBlank()) {
			lines.removeFirst()
		}
		while (lines.isNotEmpty() && lines.last().isBlank()) {
			lines.removeLast()
		}
		if (lines.isEmpty()) {
			return lines
		}
		lines[0] = lines[0].trim()
		val shared = lines.drop(1).filter(String::isNotEmpty).minOfOrNull { it.length - it.trimStart().length } ?: 0
		return lines.mapIndexed { index, line -> if (index == 0 || line.isEmpty()) line else line.substring(shared) }
	}
}
