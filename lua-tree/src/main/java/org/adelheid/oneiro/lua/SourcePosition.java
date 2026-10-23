package org.adelheid.oneiro.lua;

/**
 * A line in a plugin's source file. Statements carry one, so that every printed Lua line can be traced back to the
 * Java or Kotlin line it came from.
 *
 * @param path the source file, relative to the plugin's source root
 * @param line the line number, starting at 1
 */
public record SourcePosition(String path, int line) {

	public SourcePosition {
		if (line < 1) {
			throw new IllegalArgumentException("Line numbers start at 1: " + line);
		}
	}

	@Override
	public String toString() {
		return path + ":" + line;
	}
}
