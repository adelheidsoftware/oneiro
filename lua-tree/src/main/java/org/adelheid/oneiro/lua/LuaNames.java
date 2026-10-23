package org.adelheid.oneiro.lua;

import java.util.Set;
import java.util.regex.Pattern;

/** Lua's reserved words and its rule for names, from section 3.1 of the Lua 5.4 manual. */
public final class LuaNames {

	private static final Set<String> KEYWORDS = Set.of(
			"and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in", "local", "nil",
			"not", "or", "repeat", "return", "then", "true", "until", "while");

	private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	private LuaNames() {
	}

	/** Whether the text can be used as a Lua name: ASCII letters, digits and underscores, and not a keyword. */
	public static boolean isName(String text) {
		return NAME.matcher(text).matches() && !KEYWORDS.contains(text);
	}

	public static boolean isKeyword(String text) {
		return KEYWORDS.contains(text);
	}

	static String requireName(String text) {
		if (!isName(text)) {
			throw new IllegalArgumentException("Not a Lua name: " + text);
		}
		return text;
	}
}
