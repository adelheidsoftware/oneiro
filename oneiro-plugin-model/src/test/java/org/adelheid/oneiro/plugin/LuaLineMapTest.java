package org.adelheid.oneiro.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The line map file the compiler writes and explainError reads. */
class LuaLineMapTest {

	@TempDir
	Path folder;

	@Test
	void readsWhatItWrites() throws IOException {
		LuaLineMap.Source tally = new LuaLineMap.Source("com/example/herbs/Tally.java", 12);
		LuaLineMap written = new LuaLineMap(Map.of("src/tally.lua", Map.of(5, tally)));
		Path file = folder.resolve("main.txt");
		written.write(file);
		assertEquals(List.of("# Oneiro line map: Lua file, Lua line, source file, source line",
				"src/tally.lua\t5\tcom/example/herbs/Tally.java\t12"), Files.readAllLines(file));
		LuaLineMap read = LuaLineMap.read(file);
		assertEquals(tally, read.sourceOf("src/tally.lua", 5));
		assertNull(read.sourceOf("src/tally.lua", 6), "a line without a source");
		assertNull(read.sourceOf("main.lua", 1), "a file the map does not know");
	}

	@Test
	void theDevSourceSetsMapJoinsTheMainOne() {
		LuaLineMap main = new LuaLineMap(Map.of("main.lua", Map.of(3, new LuaLineMap.Source("Herbs.java", 9))));
		LuaLineMap dev = new LuaLineMap(Map.of("dev/main.lua", Map.of(4, new LuaLineMap.Source("TickLog.java", 7))));
		assertEquals(Set.of("main.lua", "dev/main.lua"), main.with(dev).luaFiles());
	}

	@Test
	void aLineThatIsNotOfALineMapIsReported() throws IOException {
		Path file = folder.resolve("broken.txt");
		Files.writeString(file, "main.lua\tthree\tHerbs.java\t9\n", StandardCharsets.UTF_8);
		IOException error = assertThrows(IOException.class, () -> LuaLineMap.read(file));
		assertEquals(file + ":1 is not a line of a line map: main.lua\tthree\tHerbs.java\t9", error.getMessage());
	}
}
