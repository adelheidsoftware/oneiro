package org.adelheid.oneiro.plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The Java line that each line of a plugin's Lua came from, which the compiler writes into the build so that
 * {@code explainError} can trace a client error back to the source. The file is plain text with one line per Lua line
 * that has a source: the Lua file and line, then the Java file and line, separated by tabs.
 */
public final class LuaLineMap {

	/**
	 * A line of the plugin's Java source.
	 *
	 * @param path the source file, relative to the source folder that holds it: {@code com/example/herbs/Tally.java}
	 * @param line the line number, starting at 1
	 */
	public record Source(String path, int line) {
	}

	private static final String HEADER = "# Oneiro line map: Lua file, Lua line, Java file, Java line";

	// Each Lua file's lines that have a source, by line number
	private final Map<String, Map<Integer, Source>> files;

	/** @param files each Lua file's lines that have a source, by the file's path in the plugin and the line number */
	public LuaLineMap(Map<String, Map<Integer, Source>> files) {
		Map<String, Map<Integer, Source>> copy = new TreeMap<>();
		files.forEach((lua, lines) -> copy.put(lua, Collections.unmodifiableMap(new TreeMap<>(lines))));
		this.files = Collections.unmodifiableMap(copy);
	}

	/** The Java line that a line of a Lua file came from; null when it has none, or the map does not know the file. */
	public @Nullable Source sourceOf(String luaFile, int luaLine) {
		Map<Integer, Source> lines = files.get(luaFile);
		return lines == null ? null : lines.get(luaLine);
	}

	/** The Lua files the map knows, by their paths in the plugin, such as {@code src/core/row.lua}. */
	public Set<String> luaFiles() {
		return files.keySet();
	}

	/** This map and another, such as those of a plugin's two source sets; the other's files win where both have one. */
	public LuaLineMap with(LuaLineMap other) {
		Map<String, Map<Integer, Source>> merged = new TreeMap<>(files);
		merged.putAll(other.files);
		return new LuaLineMap(merged);
	}

	public void write(Path file) throws IOException {
		List<String> lines = new ArrayList<>();
		lines.add(HEADER);
		files.forEach((lua, sources) -> sources.forEach((line, source) ->
				lines.add(lua + "\t" + line + "\t" + source.path() + "\t" + source.line())));
		Files.createDirectories(file.toAbsolutePath().getParent());
		Files.write(file, lines, StandardCharsets.UTF_8);
	}

	public static LuaLineMap read(Path file) throws IOException {
		Map<String, Map<Integer, Source>> files = new TreeMap<>();
		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		for (int index = 0; index < lines.size(); index++) {
			String line = lines.get(index);
			if (line.isBlank() || line.startsWith("#")) {
				continue;
			}
			String[] fields = line.split("\t", -1);
			try {
				if (fields.length != 4) {
					throw new NumberFormatException("4 fields expected");
				}
				files.computeIfAbsent(fields[0], lua -> new TreeMap<>())
						.put(Integer.parseInt(fields[1]), new Source(fields[2], Integer.parseInt(fields[3])));
			} catch (NumberFormatException e) {
				throw new IOException(file + ":" + (index + 1) + " is not a line of a line map: " + line, e);
			}
		}
		return new LuaLineMap(files);
	}
}
