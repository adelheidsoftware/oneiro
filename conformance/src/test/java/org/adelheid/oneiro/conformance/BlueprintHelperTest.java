package org.adelheid.oneiro.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.adelheid.rspc.runner.LuaRunner;
import org.adelheid.rspc.runner.LuaTests;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Oneiro on a real plugin, Blueprint-Helper. Five of its core modules, {@code row}, {@code permutations},
 * {@code scoring}, {@code prototyping} and {@code search}, are rewritten in Java in
 * {@code src/test/resources/blueprint-helper/input}. They must compile to the hand-written Lua in {@code expected/},
 * and the result must pass all of Blueprint-Helper's own tests in place of the plugin's modules: the tests of those
 * five, and those of the hand-written modules that use them ({@code seeds}, {@code deduce}, {@code session} and the
 * rest). {@code search} calls the hand-written {@code seeds}, whose functions return several values, through the
 * bindings of definition files in {@code types/}, and catches its own error through {@code pcall}.
 *
 * <p>Blueprint-Helper is a separate repository, found through {@code oneiro.blueprintHelper}, which the build points at
 * a checkout next to Oneiro's; without it the test is skipped.
 */
class BlueprintHelperTest {

	/**
	 * The one assertion that tests a call Java's types rule out: {@code indexOf} takes an {@code int[]}, so the Java
	 * has no check for a float such as {@code 5.0}, and Lua finds {@code 12345.0} under the integer key 12345.
	 */
	private static final List<String> TYPED_BOUNDARY = List.of(
			"FAIL permutations: list: indexOf rejects a non-integer (expected nil, got 1)");

	@Test
	void generatedCorePassesBlueprintHelpersOwnTests() throws IOException {
		String location = System.getProperty("oneiro.blueprintHelper", "");
		Path plugin = Path.of(location);
		Assumptions.assumeTrue(!location.isEmpty() && Files.isDirectory(plugin.resolve("tests")),
				"Blueprint-Helper is not checked out next to Oneiro, so its tests cannot run.");

		Map<String, String> modules = ConformanceTest.compileAndCompare(ConformanceTest.resource("blueprint-helper"));
		Path root = Path.of("build", "blueprint-helper").toAbsolutePath();
		copy(plugin.resolve("src"), root.resolve("src"));
		copy(plugin.resolve("tests"), root.resolve("tests"));
		// The manifest test reads these
		for (String file : List.of("main.lua", "plugin.json")) {
			Files.copy(plugin.resolve(file), root.resolve(file), StandardCopyOption.REPLACE_EXISTING);
		}
		for (Map.Entry<String, String> module : modules.entrySet()) {
			Path file = root.resolve(module.getKey());
			Files.createDirectories(file.getParent());
			Files.writeString(file, module.getValue(), StandardCharsets.UTF_8);
		}

		List<String> arguments = new ArrayList<>(List.of(root.toString().replace('\\', '/')));
		LuaTests.clientStandIns(root.resolve("stand-ins")).forEach(standIn -> arguments.add(standIn.toString()));
		try (Stream<Path> tests = Files.list(root.resolve("tests"))) {
			tests.map(test -> test.getFileName().toString())
					.filter(name -> name.endsWith("_test.lua"))
					.sorted()
					.forEach(name -> arguments.add(name.substring(0, name.length() - ".lua".length())));
		}
		LuaRunner.Result result = ConformanceTest.lua().run(ConformanceTest.resource("lua/blueprint-tests.lua"),
				arguments);
		assertTrue(result.succeeded(), () -> "Blueprint-Helper's tests did not run:\n" + result.output());
		List<String> failures = result.output().lines().filter(line -> line.startsWith("FAIL ")).toList();
		assertEquals(TYPED_BOUNDARY, failures, () -> "Blueprint-Helper's tests:\n" + result.output());
	}

	/** Copies a folder, replacing what an earlier run left. */
	private static void copy(Path from, Path to) throws IOException {
		if (Files.exists(to)) {
			try (Stream<Path> old = Files.walk(to)) {
				for (Path file : old.sorted(Comparator.reverseOrder()).toList()) {
					Files.delete(file);
				}
			}
		}
		try (Stream<Path> files = Files.walk(from)) {
			for (Path file : files.toList()) {
				Path target = to.resolve(from.relativize(file).toString());
				if (Files.isDirectory(file)) {
					Files.createDirectories(target);
				} else {
					Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}
}
