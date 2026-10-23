package org.adelheid.oneiro.runner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * A plugin's Lua tests, as {@code testLua} runs them: each {@code *_test.lua} file under the tests folder runs in a
 * fresh Lua state, with the plugin folder and the tests folder on {@code package.path} and the test-only
 * {@code formatStr} stand-in defined, and fails when it raises an error, as {@code assert} does.
 */
public final class LuaTests {

	private static final String HARNESS = """
			-- Runs one of a plugin's Lua test files. Arguments: the plugin folder, the tests folder, the formatStr
			-- stand-in, and the test file's path relative to the tests folder.
			local plugin, tests, standIn, test = arg[1], arg[2], arg[3], arg[4]
			package.path = plugin .. "/?.lua;" .. tests .. "/?.lua;" .. package.path
			dofile(standIn)
			dofile(tests .. "/" .. test)
			""";

	private LuaTests() {
	}

	/** Writes the test-only {@code formatStr} stand-in into the folder, for a script to load; returns its path. */
	public static Path formatStrStandIn(Path folder) throws IOException {
		Files.createDirectories(folder);
		Path file = folder.resolve("formatstr.lua");
		try (InputStream in = Objects.requireNonNull(LuaTests.class.getResourceAsStream("formatstr.lua"),
				"formatstr.lua")) {
			Files.write(file, in.readAllBytes());
		}
		return file;
	}

	/**
	 * Runs every test file and describes each outcome: {@code PASS name}, or {@code FAIL name} with what the test
	 * printed and its error.
	 *
	 * @param installed an installed Lua 5.4 interpreter, or null for the bundled Lua 5.4.8
	 * @return the outcomes, and whether every test passed
	 */
	public static Outcome run(@Nullable Path installed, Path plugin, Path tests, Path work) throws IOException {
		Files.createDirectories(work);
		Path harness = work.resolve("run-test.lua");
		Files.writeString(harness, HARNESS, StandardCharsets.UTF_8);
		Path standIn = formatStrStandIn(work);
		List<String> names;
		try (Stream<Path> files = Files.walk(tests)) {
			names = files.filter(file -> file.getFileName().toString().endsWith("_test.lua"))
					.map(file -> tests.relativize(file).toString().replace('\\', '/'))
					.sorted()
					.toList();
		}
		LuaRunner lua = LuaRunner.of(installed);
		List<String> report = new ArrayList<>();
		int failed = 0;
		for (String name : names) {
			LuaRunner.Result result = lua.run(harness,
					List.of(slashes(plugin), slashes(tests), slashes(standIn), name));
			if (result.succeeded()) {
				report.add("PASS " + name);
			} else {
				failed++;
				report.add("FAIL " + name + "\n" + result.output().indent(4).stripTrailing());
			}
		}
		report.add((names.size() - failed) + " of " + names.size() + " test files passed");
		return new Outcome(failed == 0, report);
	}

	/**
	 * Runs the tests for the Gradle plugin's worker, which calls this reflectively because the plugin's own classes are
	 * compiled for an older Java than Oneiro's: an empty {@code installed} means the bundled Lua. The first element is
	 * {@code passed} or {@code failed}, the rest the report.
	 */
	public static List<String> runForBuild(String installed, String plugin, String tests,
			String work) throws IOException {
		Outcome outcome = run(installed.isEmpty() ? null : Path.of(installed), Path.of(plugin), Path.of(tests),
				Path.of(work));
		List<String> lines = new ArrayList<>();
		lines.add(outcome.passed() ? "passed" : "failed");
		lines.addAll(outcome.report());
		return lines;
	}

	/** How the tests ended: whether every one passed, and a line or more per test file. */
	public record Outcome(boolean passed, List<String> report) {

		public Outcome {
			report = List.copyOf(report);
		}
	}

	private static String slashes(Path path) {
		return path.toAbsolutePath().toString().replace('\\', '/');
	}
}
