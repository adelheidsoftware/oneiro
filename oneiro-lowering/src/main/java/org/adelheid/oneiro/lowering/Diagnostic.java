package org.adelheid.oneiro.lowering;

import java.nio.file.Path;

/**
 * An error or warning about a plugin's source, printed as javac or kotlinc prints one, so that IDEs and build logs
 * link it to the line: the location and message, and for javac the source line and a caret under the column.
 *
 * @param severity whether it is an error or a warning
 * @param file the source file, as the compiler names it
 * @param line the line number, starting at 1
 * @param column the column, starting at 1, counted in characters, so that a tab counts as one
 * @param message what is wrong and what to do instead
 * @param sourceLine the text of the line, for the caret display
 * @param position the character offset in the file, by which the javac plugin finds the tree to report it at; -1
 *     when unknown
 */
public record Diagnostic(Severity severity, String file, long line, long column, String message, String sourceLine,
		long position) {

	public enum Severity {
		ERROR,
		WARNING
	}

	/**
	 * The diagnostic as kotlinc prints it, which IDEs link to the place: {@code e: file:///.../Row.kt:12:5 message},
	 * or {@code w:} for a warning.
	 */
	public String kotlincFormat() {
		return (severity == Severity.ERROR ? "e: " : "w: ") + Path.of(file).toUri() + ":" + line + ":" + column + " "
				+ message;
	}

	/** The diagnostic as javac prints it: {@code File.java:12: error: message}, the line, and a caret. */
	public String format() {
		StringBuilder caret = new StringBuilder();
		for (int index = 0; index < column - 1 && index < sourceLine.length(); index++) {
			// Tabs stay tabs, so the caret lines up however wide the reader's tabs are
			caret.append(sourceLine.charAt(index) == '\t' ? '\t' : ' ');
		}
		String label = severity == Severity.ERROR ? "error" : "warning";
		return file + ":" + line + ": " + label + ": " + message + "\n" + sourceLine + "\n" + caret + "^";
	}
}
