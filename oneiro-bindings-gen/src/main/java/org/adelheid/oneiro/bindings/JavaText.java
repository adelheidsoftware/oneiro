package org.adelheid.oneiro.bindings;

import java.util.List;
import java.util.Locale;
import javax.lang.model.SourceVersion;

/**
 * The text of the Java sources the bindings are: identifiers made from Lua names, string literals, and Javadoc
 * from the stubs' descriptions, escaped.
 */
final class JavaText {

	private JavaText() {
	}

	static String javaIdentifier(String luaName) {
		if (SourceVersion.isName(luaName)) {
			return luaName;
		}
		String cleaned = luaName.replaceAll("[^A-Za-z0-9_$]", "_");
		if (cleaned.isEmpty() || !Character.isJavaIdentifierStart(cleaned.charAt(0))) {
			cleaned = "_" + cleaned;
		}
		return SourceVersion.isName(cleaned) ? cleaned : cleaned + "_";
	}

	static String javaString(String text) {
		return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	static String simpleLuaName(String luaName) {
		return luaName.substring(luaName.lastIndexOf('.') + 1);
	}

	/** The name with its first word in lower case, an acronym whole: HTTPRequest is httpRequest, AABB is aabb. */
	static String lowerFirstWord(String name) {
		int capitals = 0;
		while (capitals < name.length() && Character.isUpperCase(name.charAt(capitals))) {
			capitals++;
		}
		// Of an acronym followed by a word, the last capital starts that word
		int end = capitals > 1 && capitals < name.length() && Character.isLowerCase(name.charAt(capitals))
				? capitals - 1
				: capitals;
		return name.substring(0, end).toLowerCase(Locale.ROOT) + name.substring(end);
	}

	/** Text from the stubs as Javadoc lines, escaped. */
	static List<String> lines(String text) {
		return text.isBlank() ? List.of() : text.lines().map(String::strip).map(JavaText::escape).toList();
	}

	/** A Javadoc comment of lines that are already Javadoc text. */
	static void javadoc(StringBuilder out, String indent, List<String> lines) {
		if (lines.isEmpty()) {
			return;
		}
		out.append(indent).append("/**\n");
		boolean previousBlank = true;
		boolean tags = false;
		for (String line : lines) {
			// A blank line before each paragraph and before the block tags, as Javadoc is usually laid out
			boolean opensSection = line.startsWith("<p>") || (line.startsWith("@") && !tags);
			if (opensSection && !previousBlank) {
				out.append(indent).append(" *\n");
			}
			tags |= line.startsWith("@");
			out.append(indent).append(line.isEmpty() ? " *" : " * " + line.replace("*/", "*&#47;")).append("\n");
			previousBlank = line.isEmpty();
		}
		out.append(indent).append(" */\n");
	}

	/** Text from the stubs as Javadoc shows it: HTML's special characters and Javadoc's tag characters escaped. */
	static String escape(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("@", "&#64;")
				.replace("{", "&#123;").replace("}", "&#125;");
	}
}
