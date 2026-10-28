package org.adelheid.oneiro.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A plugin's Lua tests see the client's require: a module that returns a table loads, and one that returns anything
 * else fails the test that requires it, where the client's require would give nil.
 */
class LuaTestsTest {

	@TempDir
	Path folder;

	@Test
	void aModuleThatReturnsATableLoads() throws IOException {
		write("plugin/src/tally.lua", "local Tally = {}\nfunction Tally.total(a, b) return a + b end\nreturn Tally\n");
		write("tests/tally_test.lua", "local Tally = require(\"src/tally\")\nassert(Tally.total(2, 3) == 5)\n");

		LuaTests.Outcome outcome = run();

		assertTrue(outcome.passed(), String.join("\n", outcome.report()));
	}

	@Test
	void aModuleThatReturnsAFunctionFailsTheTestThatRequiresIt() throws IOException {
		write("plugin/utils/total.lua", "local function total(a, b) return a + b end\nreturn total\n");
		write("tests/total_test.lua", "local total = require(\"utils/total\")\nassert(total(2, 3) == 5)\n");

		LuaTests.Outcome outcome = run();

		assertFalse(outcome.passed());
		String report = String.join("\n", outcome.report());
		assertTrue(report.contains("require(\"utils/total\") returns a function, but the client's require passes on"
				+ " only a table, and gives nil for any other value"), report);
	}

	@Test
	void aModuleThatReturnsNothingGivesNil() throws IOException {
		write("plugin/dev/main.lua", "loaded = true\n");
		write("tests/dev_test.lua", "assert(require(\"dev/main\") == nil and loaded)\n");

		LuaTests.Outcome outcome = run();

		assertTrue(outcome.passed(), String.join("\n", outcome.report()));
		assertEquals("1 of 1 test files passed", outcome.report().getLast());
	}

	private LuaTests.Outcome run() throws IOException {
		return LuaTests.run(null, folder.resolve("plugin"), folder.resolve("tests"), folder.resolve("work"));
	}

	private void write(String path, String code) throws IOException {
		Path file = folder.resolve(path);
		Files.createDirectories(file.getParent());
		Files.writeString(file, code);
	}
}
