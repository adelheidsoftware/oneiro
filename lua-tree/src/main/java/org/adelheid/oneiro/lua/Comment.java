package org.adelheid.oneiro.lua;

/**
 * One comment line. A plain comment prints as {@code -- text}, or as a bare {@code --} when empty (a paragraph break
 * in a file header); a Lua Language Server annotation prints as {@code ---@param ...}.
 *
 * @param text the comment without its leading dashes; never contains a line break
 * @param annotation whether the line is a Lua Language Server annotation such as {@code @param}
 */
public record Comment(String text, boolean annotation) {

	public Comment {
		if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
			throw new IllegalArgumentException("A comment is a single line: " + text);
		}
	}

	public static Comment line(String text) {
		return new Comment(text, false);
	}

	public static Comment annotation(String text) {
		return new Comment(text, true);
	}

	/** The comment as it appears in Lua, dashes included. */
	String render() {
		if (annotation) {
			return "---" + text;
		}
		return text.isEmpty() ? "--" : "-- " + text;
	}
}
