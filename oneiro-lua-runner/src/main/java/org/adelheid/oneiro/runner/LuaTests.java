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
			-- Runs one of a plugin's Lua test files. Arguments: the plugin folder, the tests folder, the test file's
			-- path relative to the tests folder, then the stand-ins for the client's formatStr and require.
			local plugin, tests, test = arg[1], arg[2], arg[3]
			package.path = plugin .. "/?.lua;" .. tests .. "/?.lua;" .. package.path
			for index = 4, #arg do
			    dofile(arg[index])
			end
			dofile(tests .. "/" .. test)
			""";
	// The test-only stand-ins for what the client gives a plugin's Lua, in the order a script loads them
	private static final List<String> STAND_INS = List.of("formatstr.lua", "require.lua");

	private LuaTests() {
	}

	/**
	 * Writes the test-only stand-ins for the client's {@code formatStr} and {@code require} into the folder, for a
	 * script to load in the order given; returns their paths. The client's {@code require} passes on only a table, so
	 * a module that returns anything else fails the test that requires it.
	 */
	public static List<Path> clientStandIns(Path folder) throws IOException {
		Files.createDirectories(folder);
		List<Path> files = new ArrayList<>();
		for (String name : STAND_INS) {
			Path file = folder.resolve(name);
			try (InputStream in = Objects.requireNonNull(LuaTests.class.getResourceAsStream(name), name)) {
				Files.write(file, in.readAllBytes());
			}
			files.add(file);
		}
		return files;
	}

	/**
	 * Runs every test file and describes each outcome: {@code PASS name}, or {@code FAIL name} with what the test
	 * printed and its error.
	 *
	 * @param installed an installed Lua 5.4 interpreter, or null for the bundled Lua 5.4.8
	 * @param plugin the plugin folder, the root of its modules on {@code package.path}
	 * @param tests the folder of the test files, also on {@code package.path}
	 * @param work a folder for the harness and the client stand-ins
	 * @return the outcomes, and whether every test passed
	 */
	public static Outcome run(@Nullable Path installed, Path plugin, Path tests, Path work) throws IOException {
		Files.createDirectories(work);
		Path harness = work.resolve("run-test.lua");
		Files.writeString(harness, HARNESS, StandardCharsets.UTF_8);
		List<String> standIns = clientStandIns(work).stream().map(LuaTests::slashes).toList();
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
			List<String> arguments = new ArrayList<>(List.of(slashes(plugin), slashes(tests), name));
			arguments.addAll(standIns);
			LuaRunner.Result result = lua.run(harness, arguments);
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
	 * {@link #run} for the Gradle plugin's worker. The Gradle plugin is compiled for an older Java than Oneiro, so it
	 * calls this reflectively, with plain strings: an empty {@code installed} means the bundled Lua. The first element
	 * of the result is {@code passed} or {@code failed}, and the rest is the report.
	 */
	public static List<String> runForBuild(String installed, String plugin, String tests, String work)
			throws IOException {
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
