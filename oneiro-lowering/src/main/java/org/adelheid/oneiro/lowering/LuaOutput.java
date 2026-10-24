package org.adelheid.oneiro.lowering;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.adelheid.oneiro.lua.LineMap;
import org.adelheid.oneiro.lua.LuaPrinter;
import org.adelheid.oneiro.lua.PrintedLua;
import org.adelheid.oneiro.lua.SourcePosition;
import org.adelheid.oneiro.plugin.LuaLineMap;

/**
 * Writes a compile's Lua into the build: each module printed into the Lua's folder, which holds only what Oneiro writes
 * there, and the line map from Lua lines back to source lines, which {@code explainError} reads.
 */
public final class LuaOutput {

	private LuaOutput() {
	}

	/** Replaces the Lua of an earlier build with these modules, and its line map. */
	public static void write(List<LuaModule> modules, Path output, Path lineMap) throws IOException {
		// The Lua of an earlier build goes, and the folders it leaves empty; files of anything else stay
		if (Files.isDirectory(output)) {
			try (Stream<Path> old = Files.walk(output)) {
				for (Path path : old.sorted(Comparator.reverseOrder()).toList()) {
					boolean stale = Files.isDirectory(path)
							? !path.equals(output) && isEmpty(path)
							: path.toString().endsWith(".lua");
					if (stale) {
						Files.delete(path);
					}
				}
			}
		}
		Map<String, Map<Integer, LuaLineMap.Source>> lines = new TreeMap<>();
		for (LuaModule module : modules) {
			Path file = output.resolve(module.path());
			Files.createDirectories(file.getParent());
			PrintedLua printed = LuaPrinter.print(module.chunk());
			Files.writeString(file, printed.text(), StandardCharsets.UTF_8);
			lines.put(module.path(), sources(printed.lineMap()));
		}
		new LuaLineMap(lines).write(lineMap);
	}

	/** The source line of each line of a printed file that has one. */
	private static Map<Integer, LuaLineMap.Source> sources(LineMap lineMap) {
		Map<Integer, LuaLineMap.Source> sources = new TreeMap<>();
		for (int line = 1; line <= lineMap.lineCount(); line++) {
			SourcePosition origin = lineMap.sourceOf(line);
			if (origin != null) {
				sources.put(line, new LuaLineMap.Source(origin.path(), origin.line()));
			}
		}
		return sources;
	}

	private static boolean isEmpty(Path folder) throws IOException {
		try (Stream<Path> entries = Files.list(folder)) {
			return entries.findAny().isEmpty();
		}
	}
}
