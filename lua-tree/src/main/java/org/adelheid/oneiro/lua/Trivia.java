package org.adelheid.oneiro.lua;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What surrounds a statement in the source and carries over into the Lua: a blank line before it, comment lines above
 * it, and a comment at the end of its last line.
 *
 * @param blankLineBefore whether an empty line separates the statement from the one before it
 * @param leading the comment lines directly above the statement, annotations included
 * @param trailing the text of a comment after the statement on its last line, without the dashes
 */
public record Trivia(boolean blankLineBefore, List<Comment> leading, @Nullable String trailing) {

	public static final Trivia NONE = new Trivia(false, List.of(), null);

	public Trivia {
		leading = List.copyOf(leading);
		if (trailing != null && (trailing.indexOf('\n') >= 0 || trailing.indexOf('\r') >= 0)) {
			throw new IllegalArgumentException("A trailing comment is a single line: " + trailing);
		}
	}
}
