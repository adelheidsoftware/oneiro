package org.adelheid.oneiro.lowering.translation;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Lua Language Server annotations a function's doc comment and signature give, the same from Javadoc and KDoc. The
 * return value is named by convention: a return text that starts with a name and a colon, {@code complete: true when
 * ...}, names it.
 */
public final class DocAnnotations {

	private static final Pattern NAMED_RETURN = Pattern.compile("([A-Za-z_]\\w*):\\s+(.+)", Pattern.DOTALL);

	private DocAnnotations() {
	}

	/**
	 * {@code @return boolean # text}, or a named return, {@code @return boolean complete text}, when the text is
	 * {@code complete: text}.
	 */
	public static String returned(String luaType, String text) {
		Matcher named = NAMED_RETURN.matcher(text);
		if (named.matches()) {
			return "@return " + luaType + " " + named.group(1) + " " + named.group(2);
		}
		return "@return " + luaType + (text.isEmpty() ? "" : " # " + text);
	}
}
