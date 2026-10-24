package org.adelheid.oneiro.lowering;

import java.util.ArrayList;
import java.util.List;

/**
 * The text of a source comment as Lua comment lines carry it. Java and Kotlin write comments alike: line comments,
 * block comments, and doc comments, whose lines may start with an asterisk.
 */
public final class CommentText {

	private CommentText() {
	}

	/** The comment's text without its delimiters or leading asterisks, one entry per line. */
	public static List<String> lines(String raw) {
		if (raw.startsWith("//")) {
			return List.of(raw.substring(2).strip());
		}
		String inner = raw.substring(raw.startsWith("/**") ? 3 : 2, raw.length() - 2);
		List<String> lines = new ArrayList<>();
		for (String line : inner.lines().toList()) {
			String text = line.strip();
			if (text.startsWith("*")) {
				text = text.substring(1).strip();
			}
			lines.add(text);
		}
		while (!lines.isEmpty() && lines.getFirst().isEmpty()) {
			lines.removeFirst();
		}
		while (!lines.isEmpty() && lines.getLast().isEmpty()) {
			lines.removeLast();
		}
		return lines;
	}
}
