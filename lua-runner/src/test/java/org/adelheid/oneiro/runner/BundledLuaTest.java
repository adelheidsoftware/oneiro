package org.adelheid.oneiro.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The bundled Lua is the client's version, sees its arguments, and reports what a script printed and why it failed. */
class BundledLuaTest {

	@TempDir
	Path folder;

	@Test
	void runsLua548WithArguments() throws IOException {
		Path script = write("print(_VERSION, math.type(1), 7 // 2, arg[1], arg[2])");

		LuaRunner.Result result = LuaRunner.of(null).run(script, List.of("first", "second"));

		assertTrue(result.succeeded(), result.output());
		assertEquals("Lua 5.4\tinteger\t3\tfirst\tsecond", result.output());
	}

	@Test
	void reportsWhatWasPrintedBeforeAnError() throws IOException {
		Path script = write("print('before')\nassert(1 == 2, 'one is not two')");

		LuaRunner.Result result = LuaRunner.of(null).run(script, List.of());

		assertFalse(result.succeeded());
		assertTrue(result.output().startsWith("before\n"), result.output());
		assertTrue(result.output().contains("one is not two"), result.output());
	}

	private Path write(String code) throws IOException {
		Path script = folder.resolve("script.lua");
		Files.writeString(script, code);
		return script;
	}
}
