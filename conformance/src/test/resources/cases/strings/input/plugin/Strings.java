package plugin;

import java.util.List;

/** Strings in the house style: formatStr for building, table.concat for joining. */
public final class Strings {

	private Strings() {
	}

	public static String greeting(String name, int level) {
		return "Hello " + name + ", level " + level;
	}

	public static String joined(String first, String second) {
		return first + second;
	}

	public static String tracking(long id, double ratio, boolean done) {
		return "id " + id + " ratio " + ratio + " done " + done;
	}

	public static String formatted(int count, double share, long big, int mask) {
		return String.format("%5d|%-6s|%.2f|%x|%08X|%+d|%%", count, "ab", share, mask, mask, big);
	}

	public static String listed(List<String> names) {
		StringBuilder text = new StringBuilder("names:");
		for (String name : names) {
			text.append(" ").append(name);
		}
		return text.toString();
	}

	public static String methods(String text) {
		if (text.isEmpty() || !text.startsWith("x")) {
			return text.toUpperCase();
		}
		return text.substring(1, 3) + text.trim() + text.indexOf("y") + text.length();
	}

	public static String literal() {
		return "a{b}" + "c";
	}

	public static String braced(int value) {
		return "{" + value + "}";
	}

	public static String counted(int count) {
		String text = "count";
		text += "=" + count;
		return text;
	}

	public static boolean compare(String a, String b) {
		return a.equals(b) || a.equalsIgnoreCase(b) || a.contains(b) || a.endsWith(".lua") || a.endsWith(b);
	}

	public static String converted(int count, double ratio, long id, boolean flag) {
		String number = String.valueOf(count) + Integer.toString(count);
		return number + Double.toString(ratio) + String.valueOf(flag) + Long.toString(id);
	}

	public static String changed(String text) {
		return text.repeat(2) + text.toLowerCase();
	}

	public static int parsed(String text) {
		return Integer.parseInt(text) + Integer.parseInt("7");
	}

	public static String joinedAll(List<String> names) {
		return String.join(", ", names) + String.join("-", "a", "b");
	}

	public static String numbered(String name, int count) {
		return String.format("%2$d %1$s", name, count);
	}

	public static String specifiers(String text, double value) {
		return String.format("%.3s %.1e%n", text, value);
	}

	public static String built(boolean flag, double ratio) {
		StringBuilder text = new StringBuilder();
		text.append(flag).append(ratio);
		return "" + text;
	}
}
