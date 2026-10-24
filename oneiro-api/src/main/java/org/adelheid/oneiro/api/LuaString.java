package org.adelheid.oneiro.api;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Lua's string patterns, for what Java's regular expressions and Kotlin's {@code Regex} do elsewhere: Lua has no
 * regular expressions, so a plugin matches text with Lua patterns through these functions, each Lua's string function
 * of its name.
 *
 * <pre>{@code
 * String fish = LuaString.match(line, "^You catch an? (.+)%.$");
 * }</pre>
 *
 * <p>A pattern is written in Lua's syntax, not a regular expression's: {@code %d} is a digit, {@code %a} a letter,
 * {@code %s} a space and {@code %w} a letter or digit, and their capitals the opposite; {@code .} is any character;
 * {@code *}, {@code +} and {@code ?} repeat the item before them, and {@code -} repeats it as few times as it can;
 * {@code ^} and {@code $} anchor the pattern; {@code %} escapes a character with a meaning of its own, {@code %.}; and
 * parentheses capture. Section 6.4.1 of the Lua 5.4 manual describes the whole syntax. A position capture,
 * {@code ()}, gives a number, which these functions' strings do not hold; {@link #find} gives positions.
 *
 * <p>The functions stand for Lua and have no Java code: they run only in the Lua Oneiro writes.
 */
public final class LuaString {

	private LuaString() {
	}

	/**
	 * The pattern's first match in the text: its first capture, or the whole match when the pattern has none.
	 *
	 * <p>Lua: {@code text:match(pattern)}
	 *
	 * @return the capture or the match, or null when nothing matches
	 */
	public static native @Nullable String match(String text, String pattern);

	/**
	 * Every capture of the pattern's first match in the text, in order, or the whole match when the pattern has none.
	 *
	 * <p>Lua: {@code { text:match(pattern) }}
	 *
	 * @return the captures, or an empty list when nothing matches
	 */
	public static native List<String> captures(String text, String pattern);

	/**
	 * Where the pattern first matches in the text, counted from 0 as Java and Kotlin count.
	 *
	 * <p>Lua: {@code (text:find(pattern) or 0) - 1}
	 *
	 * @return the position, or -1 when nothing matches
	 */
	public static native int find(String text, String pattern);

	/**
	 * The text with every match of the pattern replaced. In the replacement, {@code %1} to {@code %9} stand for the
	 * captures, {@code %0} for the whole match, and {@code %%} for a percent sign.
	 *
	 * <p>Lua: {@code (text:gsub(pattern, replacement))}
	 */
	public static native String gsub(String text, String pattern, String replacement);

	/**
	 * Each match of the pattern in the text, in order, for a for loop to walk: its first capture, or the whole match
	 * when the pattern has none. Lua gives the matches through an iterator, which only a for loop takes.
	 *
	 * <p>Lua: {@code for word in text:gmatch(pattern) do}
	 */
	public static native Iterable<String> gmatch(String text, String pattern);
}
