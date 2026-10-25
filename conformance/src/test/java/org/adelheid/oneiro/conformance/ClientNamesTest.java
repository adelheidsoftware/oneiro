package org.adelheid.oneiro.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps the client's names in one place. The RS3 API reaches the compiler as the bindings' annotations, apart from the
 * few parts it writes on its own, which {@code ClientApi} in {@code oneiro-lowering} holds: the {@code Event} and
 * {@code PersistentDB} globals, {@code PluginShutdown}, {@code formatStr}, {@code FromID} and the permission check. No
 * other source of the front ends or of the lowering spells one in a string, so that when the client changes one, the
 * compiler changes in {@code ClientApi} alone.
 */
class ClientNamesTest {

	// The names ClientApi holds, as whole words
	private static final Pattern CLIENT_NAME = Pattern.compile(
			"\\b(Event|PersistentDB|PluginShutdown|formatStr|FromID|CurrentPlugin|PluginPermissions|HasPermission)\\b");

	@Test
	void onlyClientApiSpellsTheClientsNames() throws IOException {
		List<String> spelled = new ArrayList<>();
		for (String folder : System.getProperty("oneiro.compilerSources").split(File.pathSeparator)) {
			List<Path> sources;
			try (Stream<Path> files = Files.walk(Path.of(folder))) {
				sources = files.filter(file -> file.toString().endsWith(".java") || file.toString().endsWith(".kt"))
						.filter(file -> !file.getFileName().toString().equals("ClientApi.java"))
						.sorted()
						.toList();
			}
			for (Path source : sources) {
				boolean kotlin = source.toString().endsWith(".kt");
				for (Literal literal : new Literals(Files.readString(source), kotlin).read()) {
					if (CLIENT_NAME.matcher(literal.text()).find()) {
						spelled.add(source.getFileName() + ":" + literal.line() + ": " + literal.text());
					}
				}
			}
		}
		assertEquals(List.of(), spelled, "Strings that spell a name ClientApi holds");
	}

	/** The reader finds the literals of both languages, and nothing in comments or character literals. */
	@Test
	void readsTheLiteralsOutsideComments() {
		String java = """
				char quote = '"'; // "comment"
				String text = "a // b" + "c \\" d"; /* "comment" */
				""";
		assertEquals(List.of("a // b", "c \\\" d"), texts(java, false));
		String kotlin = """
				val a = "one" /* "two" /* nested */ "three" */
				val b = "x ${f("four")} y" + '\\''
				val c = \"""raw ${"five"} "quoted\"""\"
				""";
		assertEquals(List.of("one", "four", "x ${} y", "five", "raw ${} \"quoted\""), texts(kotlin, true));
	}

	private static List<String> texts(String source, boolean kotlin) {
		return new Literals(source, kotlin).read().stream().map(Literal::text).toList();
	}

	/** A string literal's text, without its quotes, and the line it starts on. */
	private record Literal(int line, String text) {
	}

	/**
	 * Reads the string literals of a Java or Kotlin source, skipping comments and character literals. The expressions
	 * of a Kotlin template, {@code "${f("a")}"}, are code: their literals are read as literals of their own, and the
	 * template's text keeps {@code ${}} in their place.
	 */
	private static final class Literals {

		private final String source;
		private final boolean kotlin;
		private final List<Literal> literals = new ArrayList<>();
		private int at;
		private int line = 1;

		Literals(String source, boolean kotlin) {
			this.source = source;
			this.kotlin = kotlin;
		}

		List<Literal> read() {
			code(false);
			return literals;
		}

		/** Code to the end of the source, or inside a template, to the brace that closes it. */
		private void code(boolean template) {
			int braces = 0;
			while (at < source.length()) {
				if (startsWith("//")) {
					while (at < source.length() && source.charAt(at) != '\n') {
						at++;
					}
				} else if (startsWith("/*")) {
					blockComment();
				} else if (startsWith("\"\"\"")) {
					string("\"\"\"");
				} else if (startsWith("\"")) {
					string("\"");
				} else if (startsWith("'")) {
					character();
				} else {
					char c = next();
					if (template && c == '{') {
						braces++;
					} else if (template && c == '}' && braces-- == 0) {
						return;
					}
				}
			}
		}

		/** A block comment. Kotlin's nest; Java's end at the first closing mark. */
		private void blockComment() {
			int depth = 0;
			do {
				if (startsWith("/*") && (kotlin || depth == 0)) {
					depth++;
					at += 2;
				} else if (startsWith("*/")) {
					depth--;
					at += 2;
				} else {
					next();
				}
			} while (depth > 0 && at < source.length());
		}

		/** A string, a Java text block or a Kotlin raw string, which takes no escapes. */
		private void string(String quote) {
			int start = line;
			boolean raw = kotlin && quote.length() == 3;
			at += quote.length();
			StringBuilder text = new StringBuilder();
			while (at < source.length()) {
				if (raw && startsWith("\"\"\"\"")) {
					// A quote just before the closing three is text
					text.append(next());
				} else if (startsWith(quote)) {
					break;
				} else if (kotlin && startsWith("${")) {
					at += 2;
					code(true);
					text.append("${}");
				} else if (!raw && startsWith("\\")) {
					text.append(next()).append(next());
				} else {
					text.append(next());
				}
			}
			at += quote.length();
			literals.add(new Literal(start, text.toString()));
		}

		/** A character literal, {@code 'a'} or {@code '\''}. */
		private void character() {
			at++;
			while (at < source.length() && !startsWith("'")) {
				if (startsWith("\\")) {
					at++;
				}
				next();
			}
			at++;
		}

		private boolean startsWith(String text) {
			return source.startsWith(text, at);
		}

		private char next() {
			char c = source.charAt(at++);
			if (c == '\n') {
				line++;
			}
			return c;
		}
	}
}
