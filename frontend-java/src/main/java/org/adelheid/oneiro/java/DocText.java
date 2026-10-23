package org.adelheid.oneiro.java;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.LinkTree;
import com.sun.source.doctree.LiteralTree;
import com.sun.source.doctree.ParamTree;
import com.sun.source.doctree.ReturnTree;
import com.sun.source.doctree.TextTree;
import java.util.ArrayList;
import java.util.List;

/**
 * The text of Javadoc comments as Lua comments carry it: descriptions, and the text of {@code @param} and {@code
 * @return}.
 */
final class DocText {

	private DocText() {
	}

	/** The text of the {@code @param} tag for a parameter, on one line; empty without one. */
	static String param(DocCommentTree doc, String parameter) {
		for (DocTree tag : doc.getBlockTags()) {
			if (tag instanceof ParamTree param && param.getName().getName().contentEquals(parameter)) {
				return String.join(" ", lines(param.getDescription()));
			}
		}
		return "";
	}

	/** The text of the {@code @return} tag, on one line; empty without one. */
	static String returned(DocCommentTree doc) {
		for (DocTree tag : doc.getBlockTags()) {
			if (tag instanceof ReturnTree returned) {
				return String.join(" ", lines(returned.getDescription()));
			}
		}
		return "";
	}

	/**
	 * Javadoc text as plain lines: {@code {@code x}} becomes {@code `x`}, which the Lua Language Server shows. The
	 * indentation the lines share is removed and the rest kept, so an indented continuation of a list item stays
	 * indented.
	 */
	static List<String> lines(List<? extends DocTree> body) {
		StringBuilder text = new StringBuilder();
		for (DocTree node : body) {
			switch (node) {
				case TextTree plain -> text.append(plain.getBody());
				case LiteralTree literal -> text.append('`').append(literal.getBody().getBody()).append('`');
				case LinkTree link -> text.append('`').append(link.getReference().getSignature()).append('`');
				default -> text.append(node);
			}
		}
		List<String> lines = new ArrayList<>(text.toString().lines().map(String::stripTrailing).toList());
		if (lines.isEmpty()) {
			return lines;
		}
		// The first line follows the comment's opening; the others share the indentation after each asterisk
		lines.set(0, lines.getFirst().strip());
		int shared = lines.stream().skip(1).filter(line -> !line.isEmpty())
				.mapToInt(line -> line.length() - line.stripLeading().length())
				.min().orElse(0);
		for (int index = 1; index < lines.size(); index++) {
			String line = lines.get(index);
			lines.set(index, line.isEmpty() ? line : line.substring(shared));
		}
		while (!lines.isEmpty() && lines.getLast().isEmpty()) {
			lines.removeLast();
		}
		return lines;
	}
}
