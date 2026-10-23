package org.adelheid.oneiro.java;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Every comment in one source file, found by a small lexer over the raw text, because javac keeps only Javadoc in its
 * public tree API. Lowering claims comments by position as it walks the tree: the ones between two statements lead
 * the second, one after a statement on the same line trails it, and the rest of a block closes it. Whatever is never
 * claimed is reported as a warning, so a comment can end up misplaced but is never dropped silently.
 */
final class CommentIndex {

	enum Kind {
		LINE,
		BLOCK,
		DOC
	}

	/** One comment: its character range in the source, its kind, and its text as written. */
	record SourceComment(int start, int end, Kind kind, String raw) {

		/** The comment's text without delimiters or leading asterisks, one entry per line. */
		List<String> lines() {
			if (kind == Kind.LINE) {
				return List.of(raw.substring(2).strip());
			}
			String inner = raw.substring(kind == Kind.DOC ? 3 : 2, raw.length() - 2);
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

	private final CharSequence source;
	private final List<SourceComment> comments;
	private final boolean[] claimed;

	private CommentIndex(CharSequence source, List<SourceComment> comments) {
		this.source = source;
		this.comments = comments;
		this.claimed = new boolean[comments.size()];
	}

	static CommentIndex scan(CharSequence source) {
		List<SourceComment> found = new ArrayList<>();
		int length = source.length();
		int index = 0;
		while (index < length) {
			char c = source.charAt(index);
			if (c == '"' && startsWith(source, index, "\"\"\"")) {
				index = skipTextBlock(source, index + 3);
			} else if (c == '"' || c == '\'') {
				index = skipQuoted(source, index + 1, c);
			} else if (c == '/' && startsWith(source, index, "//")) {
				int end = lineEnd(source, index);
				found.add(new SourceComment(index, end, Kind.LINE, source.subSequence(index, end).toString()));
				index = end;
			} else if (c == '/' && startsWith(source, index, "/*")) {
				int close = indexOf(source, "*/", index + 2);
				int end = close < 0 ? length : close + 2;
				Kind kind = startsWith(source, index, "/**") && !startsWith(source, index, "/**/")
						? Kind.DOC : Kind.BLOCK;
				found.add(new SourceComment(index, end, kind, source.subSequence(index, end).toString()));
				index = end;
			} else {
				index++;
			}
		}
		return new CommentIndex(source, found);
	}

	/**
	 * Claims and returns the unclaimed comments inside {@code [from, to)}. Javadoc comments in the range are claimed
	 * but not returned, because javac's DocTrees delivers them attached to their declaration.
	 */
	List<SourceComment> take(int from, int to) {
		List<SourceComment> taken = new ArrayList<>();
		for (int index = 0; index < comments.size(); index++) {
			SourceComment comment = comments.get(index);
			if (claimed[index] || comment.start() < from || comment.end() > to) {
				continue;
			}
			claimed[index] = true;
			if (comment.kind() != Kind.DOC) {
				taken.add(comment);
			}
		}
		return taken;
	}

	/**
	 * Claims the comment that trails code ending at {@code codeEnd}: the next comment, if only spaces, tabs or a
	 * semicolon separate it from that code on the same line.
	 */
	@Nullable SourceComment takeTrailing(int codeEnd) {
		return takeTrailing(codeEnd, "");
	}

	/**
	 * {@link #takeTrailing(int)}, with other characters allowed in between too, such as the comma after an enum
	 * constant.
	 */
	@Nullable SourceComment takeTrailing(int codeEnd, String skipped) {
		for (int index = 0; index < comments.size(); index++) {
			SourceComment comment = comments.get(index);
			if (claimed[index] || comment.start() < codeEnd) {
				continue;
			}
			for (int position = codeEnd; position < comment.start(); position++) {
				char c = source.charAt(position);
				if (c != ' ' && c != '\t' && c != ';' && skipped.indexOf(c) < 0) {
					return null;
				}
			}
			claimed[index] = true;
			return comment;
		}
		return null;
	}

	/** Whether {@code [from, to)} contains an empty line, which the Lua keeps to separate groups of statements. */
	boolean hasBlankLine(int from, int to) {
		int lineBreaks = 0;
		for (int position = Math.max(from, 0); position < to && position < source.length(); position++) {
			char c = source.charAt(position);
			if (c == '\n') {
				lineBreaks++;
				if (lineBreaks == 2) {
					return true;
				}
			} else if (!Character.isWhitespace(c)) {
				lineBreaks = 0;
			}
		}
		return false;
	}

	List<SourceComment> unclaimed() {
		List<SourceComment> left = new ArrayList<>();
		for (int index = 0; index < comments.size(); index++) {
			if (!claimed[index]) {
				left.add(comments.get(index));
			}
		}
		return left;
	}

	private static int skipQuoted(CharSequence source, int index, char quote) {
		while (index < source.length()) {
			char c = source.charAt(index);
			if (c == '\\') {
				index += 2;
			} else if (c == quote) {
				return index + 1;
			} else if (c == '\n') {
				return index;
			} else {
				index++;
			}
		}
		return index;
	}

	private static int skipTextBlock(CharSequence source, int index) {
		while (index < source.length()) {
			if (source.charAt(index) == '\\') {
				index += 2;
			} else if (startsWith(source, index, "\"\"\"")) {
				return index + 3;
			} else {
				index++;
			}
		}
		return index;
	}

	private static int lineEnd(CharSequence source, int index) {
		while (index < source.length() && source.charAt(index) != '\n' && source.charAt(index) != '\r') {
			index++;
		}
		return index;
	}

	private static int indexOf(CharSequence source, String needle, int from) {
		for (int index = from; index + needle.length() <= source.length(); index++) {
			if (startsWith(source, index, needle)) {
				return index;
			}
		}
		return -1;
	}

	private static boolean startsWith(CharSequence source, int index, String prefix) {
		if (index + prefix.length() > source.length()) {
			return false;
		}
		for (int offset = 0; offset < prefix.length(); offset++) {
			if (source.charAt(index + offset) != prefix.charAt(offset)) {
				return false;
			}
		}
		return true;
	}
}
