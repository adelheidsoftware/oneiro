package org.adelheid.oneiro.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.adelheid.oneiro.bindings.BindingsGenerator;
import org.adelheid.oneiro.java.CompileResult;
import org.adelheid.oneiro.java.Diagnostic;
import org.adelheid.oneiro.java.JavaFrontend;
import org.adelheid.oneiro.java.LuaModule;
import org.adelheid.oneiro.java.PluginFacts;
import org.adelheid.oneiro.lua.LuaPrinter;
import org.adelheid.oneiro.runner.LuaRunner;
import org.adelheid.oneiro.runner.LuaTests;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs every case under {@code src/test/resources/cases}. A case is a folder with the Java sources of a small plugin in
 * {@code input/} (root package {@code plugin}) and any of: {@code expected/}, the Lua Oneiro must write, at the paths
 * it must write it, by hand in the house style; {@code diagnostics.txt}, the errors and warnings it must report; and
 * {@code check.lua}, assertions run under Lua 5.4 against the generated modules; and {@code lua/}, hand-written Lua the
 * generated modules use, such as a module the Java declares with {@code @LuaModule}. Lua is the bundled Lua 5.4.8
 * unless {@code oneiro.luaExecutable} names an installed one. Every expected file must also be StyLua-formatted.
 */
class ConformanceTest {

	private static final String ROOT_PACKAGE = "plugin";
	private static final Path OUTPUT = Path.of("build", "conformance");

	@TestFactory
	Stream<DynamicTest> cases() throws IOException {
		try (Stream<Path> folders = Files.list(resource("cases"))) {
			return folders.filter(Files::isDirectory).sorted().toList().stream()
					.map(folder -> DynamicTest.dynamicTest(folder.getFileName().toString(), () -> run(folder)));
		}
	}

	@Test
	void expectedLuaIsStyLuaFormatted() throws IOException {
		String stylua = System.getProperty("oneiro.styluaExecutable");
		if (stylua == null || stylua.isBlank()) {
			if (System.getProperty("oneiro.ci") != null) {
				fail("CI must check the expected Lua with StyLua; set oneiro.styluaExecutable.");
			}
			Assumptions.abort("StyLua is not configured, so the expected Lua is not checked; set"
					+ " oneiro.styluaExecutable in ~/.gradle/gradle.properties to check it.");
			return;
		}
		List<String> command = new ArrayList<>(List.of(stylua, "--check", "--config-path",
				System.getProperty("oneiro.styluaConfig")));
		for (String folder : List.of("cases", "blueprint-helper")) {
			try (Stream<Path> files = Files.walk(resource(folder))) {
				files.filter(file -> file.toString().endsWith(".lua") && file.toString().contains("expected"))
						.sorted()
						.forEach(file -> command.add(file.toString()));
			}
		}
		ProcessResult result = execute(command);
		if (result.exitCode() != 0) {
			fail("Expected Lua that StyLua would format differently:\n" + result.output());
		}
	}

	private static void run(Path folder) throws IOException {
		Map<String, String> modules = compileAndCompare(folder);
		if (Files.exists(folder.resolve("expected"))) {
			runLua(folder, modules);
		}
	}

	/**
	 * Compiles a case's {@code input/} and compares the diagnostics with its {@code diagnostics.txt} and, when it has
	 * {@code expected/}, the modules with the hand-written Lua there.
	 *
	 * @return the printed modules by path
	 */
	static Map<String, String> compileAndCompare(Path folder) throws IOException {
		Path input = folder.resolve("input");
		List<Path> sources;
		try (Stream<Path> files = Files.walk(input)) {
			sources = files.filter(file -> file.toString().endsWith(".java")).sorted().toList();
		}
		CompileResult result = JavaFrontend.compile(sources,
				new JavaFrontend.Options(ROOT_PACKAGE, classpath(), facts(folder.resolve("case.properties"))));

		String diagnostics = result.diagnostics().stream()
				.map(diagnostic -> relativeTo(input, diagnostic).format())
				.collect(Collectors.joining("\n\n"));
		Path expectedDiagnostics = folder.resolve("diagnostics.txt");
		String expected = Files.exists(expectedDiagnostics)
				? read(expectedDiagnostics).replace("\r\n", "\n").strip()
				: "";
		assertEquals(expected, diagnostics.strip(), "diagnostics");

		Map<String, String> actual = new TreeMap<>();
		for (LuaModule module : result.modules()) {
			actual.put(module.path(), LuaPrinter.print(module.chunk()).text());
		}
		Path expectedLua = folder.resolve("expected");
		if (!Files.exists(expectedLua)) {
			return actual;
		}
		Map<String, String> wanted = new TreeMap<>();
		try (Stream<Path> files = Files.walk(expectedLua)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				wanted.put(expectedLua.relativize(file).toString().replace('\\', '/'), read(file));
			}
		}
		assertEquals(wanted.keySet(), actual.keySet(), "generated modules");
		for (String path : wanted.keySet()) {
			assertEquals(wanted.get(path), actual.get(path), path);
		}
		return actual;
	}

	/**
	 * Writes the generated modules, with any hand-written Lua the case brings, to a plugin folder, and runs them and
	 * the case's check.lua under Lua 5.4.
	 */
	private static void runLua(Path folder, Map<String, String> modules) throws IOException {
		Path root = OUTPUT.resolve(folder.getFileName().toString()).toAbsolutePath();
		Path handWritten = folder.resolve("lua");
		if (Files.exists(handWritten)) {
			try (Stream<Path> files = Files.walk(handWritten)) {
				for (Path file : files.filter(Files::isRegularFile).toList()) {
					Path target = root.resolve(handWritten.relativize(file).toString());
					Files.createDirectories(target.getParent());
					Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
		List<String> arguments = new ArrayList<>(List.of(
				root.toString().replace('\\', '/'),
				LuaTests.formatStrStandIn(OUTPUT.toAbsolutePath()).toString(),
				Files.exists(folder.resolve("check.lua")) ? folder.resolve("check.lua").toString() : ""));
		for (Map.Entry<String, String> module : modules.entrySet()) {
			Path file = root.resolve(module.getKey());
			Files.createDirectories(file.getParent());
			Files.writeString(file, module.getValue(), StandardCharsets.UTF_8);
			arguments.add(file.toString());
		}
		LuaRunner.Result result = lua().run(resource("lua/run-case.lua"), arguments);
		if (!result.succeeded()) {
			fail("Lua 5.4 failed on " + folder.getFileName() + ":\n" + result.output());
		}
	}

	/** The bundled Lua 5.4.8, or the installed interpreter that {@code oneiro.luaExecutable} names. */
	static LuaRunner lua() {
		String installed = System.getProperty("oneiro.luaExecutable");
		return LuaRunner.of(installed == null || installed.isBlank() ? null : Path.of(installed));
	}

	/** The diagnostic with its file named relative to the case's input folder, as diagnostics.txt names it. */
	private static Diagnostic relativeTo(Path input, Diagnostic diagnostic) {
		String file = input.toAbsolutePath().relativize(Path.of(diagnostic.file()).toAbsolutePath()).toString()
				.replace('\\', '/');
		return new Diagnostic(diagnostic.severity(), file, diagnostic.line(), diagnostic.column(),
				diagnostic.message(), diagnostic.sourceLine(), diagnostic.position());
	}

	/** The test's own classpath, which has the api and JSpecify, and the RS3 bindings generated from the fixture. */
	private static List<Path> classpath() {
		List<Path> classpath = new ArrayList<>(
				Stream.of(System.getProperty("java.class.path").split(File.pathSeparator)).map(Path::of).toList());
		classpath.add(FixtureBindings.JAR);
		return classpath;
	}

	/**
	 * The plugin facts a case's {@code case.properties} gives: {@code id}, and comma-separated {@code permissions} and
	 * {@code flags}; the defaults without one.
	 */
	private static PluginFacts facts(Path properties) throws IOException {
		if (!Files.exists(properties)) {
			return PluginFacts.DEFAULT;
		}
		Properties values = new Properties();
		try (Reader reader = Files.newBufferedReader(properties, StandardCharsets.UTF_8)) {
			values.load(reader);
		}
		return new PluginFacts(values.getProperty("id", PluginFacts.DEFAULT.id()),
				list(values.getProperty("permissions", "")), list(values.getProperty("flags", "")),
				Boolean.parseBoolean(values.getProperty("dev", "false")));
	}

	private static Set<String> list(String commaSeparated) {
		return Stream.of(commaSeparated.split(",")).map(String::strip).filter(value -> !value.isEmpty())
				.collect(Collectors.toSet());
	}

	/** The jar of the bindings generated from bindings-gen's stubs fixture, once per run. */
	static Path fixtureBindings() {
		return FixtureBindings.JAR;
	}

	/** The bindings generated once per run from bindings-gen's stubs fixture, which the cases compile against. */
	private static final class FixtureBindings {

		static final Path JAR = generate();

		private FixtureBindings() {
		}

		private static Path generate() {
			try {
				return BindingsGenerator.generate(Path.of(System.getProperty("oneiro.stubsFixture")),
						Path.of("build", "conformance-bindings")).classesJar();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}

	private record ProcessResult(int exitCode, String output) {
	}

	private static ProcessResult execute(List<String> command) {
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			return new ProcessResult(process.waitFor(), output);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	static Path resource(String name) {
		try {
			return Path.of(Objects.requireNonNull(ConformanceTest.class.getResource("/" + name), name).toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
