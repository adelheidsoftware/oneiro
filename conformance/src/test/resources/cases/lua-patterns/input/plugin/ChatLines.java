package plugin;

import java.util.ArrayList;
import java.util.List;
import org.adelheid.oneiro.api.LuaString;
import org.jspecify.annotations.Nullable;

/** Chat lines read with Lua patterns, through LuaString where Java would use regular expressions. */
public final class ChatLines {

	private ChatLines() {
	}

	/** The fish a catch message names, such as "shark" in "You catch a shark.", or null for another line. */
	public static @Nullable String caught(String line) {
		return LuaString.match(line, "^You catch an? (.+)%.$");
	}

	/** The amount and the item of a drop message, such as "3" and "rune bars" in "Drop: 3 x rune bars". */
	public static List<String> drop(String line) {
		return LuaString.captures(line, "^Drop: (%d+) x (.+)$");
	}

	/** Where the line's first number starts, or -1. */
	public static int firstNumber(String line) {
		return LuaString.find(line, "%d");
	}

	/** The line with each number hidden, such as "Level #" for "Level 99". */
	public static String masked(String line) {
		return LuaString.gsub(line, "%d+", "#");
	}

	/** The words of the line. */
	public static List<String> words(String line) {
		List<String> words = new ArrayList<>();
		for (String word : LuaString.gmatch(line, "%a+")) {
			words.add(word);
		}
		return words;
	}
}
