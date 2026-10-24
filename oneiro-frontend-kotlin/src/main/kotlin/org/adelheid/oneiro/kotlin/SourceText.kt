package org.adelheid.oneiro.kotlin

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import org.adelheid.oneiro.lowering.CommentText
import org.adelheid.oneiro.lowering.Diagnostic
import org.adelheid.oneiro.lua.Comment
import org.adelheid.oneiro.lua.SourcePosition
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile

/**
 * A source file's text as lowering needs it: the line and column of an offset, for diagnostics and the line map, and
 * the file's comments. Kotlin's tree keeps every comment, either beside the statement it describes or, for a
 * declaration, as its first child. Lowering claims each comment as it places it, and whatever it never claims gets a
 * warning, so a comment can end up misplaced but is never dropped silently. Doc comments belong to their declarations
 * and are read there.
 */
internal class SourceText(private val file: KtFile) {

	private val text: String = file.text
	private val lineStarts: IntArray = buildList {
		add(0)
		text.forEachIndexed { index, character ->
			if (character == '\n') {
				add(index + 1)
			}
		}
	}.toIntArray()
	private val comments: List<PsiComment> = PsiTreeUtil.collectElementsOfType(file, PsiComment::class.java)
		.filter { it !is KDoc }
		.sortedBy { it.textOffset }
	private val claimed = mutableSetOf<PsiComment>()

	/** The file's path inside its source folder, as its package names it: `plugin/core/Row.kt`. */
	val relativePath: String = file.packageFqName.asString()
		.let { if (it.isEmpty()) file.name else it.replace('.', '/') + "/" + file.name }

	fun position(element: PsiElement): SourcePosition = SourcePosition(relativePath, lineIndex(element.textOffset) + 1)

	/** A diagnostic at an offset, with its line's text, in the form javac's diagnostics take. */
	fun diagnostic(severity: Diagnostic.Severity, offset: Int, message: String): Diagnostic {
		val index = lineIndex(offset)
		val start = lineStarts[index]
		val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
		val line = text.substring(start, end).removeSuffix("\r")
		// A column in characters, so that a tab counts as one, as the Java front end counts it
		return Diagnostic(severity, file.virtualFile.path, index + 1L, offset - start + 1L, message, line,
			offset.toLong())
	}

	private fun lineIndex(offset: Int): Int {
		val found = lineStarts.binarySearch(offset)
		return if (found >= 0) found else -found - 2
	}

	/**
	 * The comments directly above an element, each claimed: those the tree made its first children, and those
	 * between it and what comes before it. A comment on the line of the statement before trails that statement.
	 */
	fun leading(element: PsiElement): List<Comment> {
		val found = mutableListOf<PsiComment>()
		var child = element.firstChild
		while (child is PsiComment || child is PsiWhiteSpace) {
			if (child is PsiComment && child !is KDoc) {
				found += child
			}
			child = child.nextSibling
		}
		var before = element.prevSibling
		val above = mutableListOf<PsiComment>()
		while (before is PsiComment || before is PsiWhiteSpace) {
			if (before is PsiComment && before !is KDoc && before !in claimed && !trailsCode(before)) {
				above.add(0, before)
			}
			before = before.prevSibling
		}
		return (above + found).flatMap(::claim)
	}

	/**
	 * Whether a blank line separates an element, with the comments above it, from the code before it; a blank line
	 * at the start of a block or a file does not count.
	 */
	fun blankLineBefore(element: PsiElement): Boolean {
		var before = element.prevSibling
		while (true) {
			when {
				before is PsiWhiteSpace && isBlankLine(before) -> {
					val code = before.prevSibling
					return code != null && code.node.elementType != KtTokens.LBRACE
				}
				before is PsiWhiteSpace || before is PsiComment && !trailsCode(before) -> before = before.prevSibling
				else -> return false
			}
		}
	}

	/** The comment after an element on its last line, claimed; null when there is none. */
	fun trailing(element: PsiElement): String? {
		val inside = element.lastChild
		val candidate = if (inside is PsiComment && inside !is KDoc) inside else {
			var after = element.nextSibling
			while (after is PsiWhiteSpace && !after.textContains('\n')) {
				after = after.nextSibling
			}
			after as? PsiComment
		}
		if (candidate == null || candidate is KDoc || candidate in claimed || !trailsCode(candidate)) {
			return null
		}
		return claim(candidate).joinToString(" ") { it.text() }
	}

	/** The comments before an offset that nothing has claimed, claimed now: a file's header, above its declaration. */
	fun before(offset: Int): List<Comment> = comments
		.filter { it !in claimed && it.textRange.endOffset <= offset }
		.flatMap(::claim)

	/** The comments between an element's start and its end that nothing has claimed, claimed now. */
	fun remainingIn(element: PsiElement): List<Comment> = comments
		.filter { it !in claimed && element.textRange.contains(it.textRange) }
		.flatMap(::claim)

	/** The comments nothing claimed, which could not be placed in the Lua. */
	fun unclaimed(): List<PsiComment> = comments.filter { it !in claimed }

	private fun claim(comment: PsiComment): List<Comment> {
		claimed += comment
		return CommentText.lines(comment.text).map(Comment::line)
	}

	/** Whether code comes before a comment on its line, which makes it a trailing comment. */
	private fun trailsCode(comment: PsiComment): Boolean {
		val lineStart = lineStarts[lineIndex(comment.textOffset)]
		return text.substring(lineStart, comment.textOffset).isNotBlank()
	}

	private fun isBlankLine(whiteSpace: PsiElement): Boolean = whiteSpace.text.count { it == '\n' } >= 2
}
