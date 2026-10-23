package org.adelheid.oneiro.lua;

import org.jspecify.annotations.Nullable;

/** String literals written as StyLua writes them with {@code quote_style = "AutoPreferDouble"}. */
final class LuaStrings {

	private LuaStrings() {
	}

	/**
	 * The text in quotes: double quotes, unless it holds more double quotes than single ones. Backslashes, the quote,
	 * control characters and invisible characters are escaped; everything else, non-ASCII letters included, is kept
	 * as it is.
	 */
	static String quoted(String value) {
		long doubles = value.chars().filter(character -> character == '"').count();
		long singles = value.chars().filter(character -> character == '\'').count();
		char quote = doubles > singles ? '\'' : '"';
		StringBuilder out = new StringBuilder().append(quote);
		value.codePoints().forEach(codePoint -> appendEscaped(out, codePoint, quote));
		return out.append(quote).toString();
	}

	/**
	 * The text as a long bracket string, {@code [[...]]}, with as many {@code =} between the brackets as it takes
	 * for the text not to close the string early. Null when the text holds a carriage return, which Lua would read
	 * back as a line feed. The opening bracket is followed by a line break, which Lua drops, so the text starts on a
	 * line of its own.
	 */
	static @Nullable String longBracket(String value) {
		if (value.indexOf('\r') >= 0) {
			return null;
		}
		int level = 0;
		while (value.contains("]" + "=".repeat(level) + "]") || value.endsWith("]" + "=".repeat(level))) {
			level++;
		}
		String equals = "=".repeat(level);
		return "[" + equals + "[\n" + value + "]" + equals + "]";
	}

	private static void appendEscaped(StringBuilder out, int codePoint, char quote) {
		switch (codePoint) {
			case '\\' -> out.append("\\\\");
			case '\n' -> out.append("\\n");
			case '\r' -> out.append("\\r");
			case '\t' -> out.append("\\t");
			default -> {
				if (codePoint == quote) {
					out.append('\\').append(quote);
				} else if (codePoint < 0x20 || codePoint == 0x7F) {
					out.append("\\%03d".formatted(codePoint));
				} else if (isInvisible(codePoint)) {
					out.append("\\u{").append(Integer.toHexString(codePoint)).append('}');
				} else {
					out.appendCodePoint(codePoint);
				}
			}
		}
	}

	/** Characters a reader could not see, or could not tell from a space; escaping them shows them to the moderator. */
	private static boolean isInvisible(int codePoint) {
		return switch (Character.getType(codePoint)) {
			case Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
					Character.SPACE_SEPARATOR, Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED ->
					codePoint != ' ';
			default -> false;
		};
	}
}
