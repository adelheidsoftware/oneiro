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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.adelheid.oneiro.bindings.BindingsGenerator;
import org.adelheid.oneiro.java.JavaFrontend;
import org.adelheid.oneiro.kotlin.KotlinFrontend;
import org.adelheid.oneiro.lowering.CompileResult;
import org.adelheid.oneiro.lowering.Diagnostic;
import org.adelheid.oneiro.lowering.LuaModule;
import org.adelheid.oneiro.lowering.PluginFacts;
import org.adelheid.oneiro.lua.LuaPrinter;
import org.adelheid.oneiro.plugin.LuaLibraries;
import org.adelheid.oneiro.runner.LuaRunner;
import org.adelheid.oneiro.runner.LuaTests;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs every case under {@code src/test/resources/cases}. A case is a folder with the Java sources of a small plugin in
 * {@code input/}, in the root package {@code plugin}, and any of:
 *
 * <ul>
 *   <li>{@code expected/}: the Lua Oneiro must write, at the paths it must write it, written by hand in the house
 *       style;
 *   <li>{@code diagnostics.txt}: the errors and warnings it must report;
 *   <li>{@code check.lua}: assertions run under Lua 5.4 against the generated modules;
 *   <li>{@code lua/}: the plugin's own Lua, laid out as a plugin's {@code src/main/lua/} is, which runs beside the
 *       generated modules under {@code src/}, and whose annotations give the bindings the sources compile against;
 *   <li>{@code types/}: definition files that give those bindings instead, where the Lua is another's, such as
 *       Blueprint-Helper's;
 *   <li>{@code dependencies/required/<alias>/} and {@code dependencies/optional/<alias>/}: definition files of the
 *       library plugins the case depends on, whose bindings it compiles against too;
 *   <li>{@code case.properties}: the plugin's {@code id}, {@code permissions} and {@code flags}, and whether the
 *       sources are the {@code dev} source set;
 *   <li>{@code kotlin/}: the same plugin in Kotlin, its twin, which must give the same Lua, and its own
 *       {@code diagnostics.txt}; with {@code kotlin/expected/} and {@code kotlin/check.lua} where idiomatic Kotlin
 *       reads or behaves differently.
 * </ul>
 *
 * <p>A case for what only Kotlin has keeps its sources in {@code kotlin/} alone, without {@code input/}, and its
 * {@code expected/} and {@code check.lua} are the Kotlin's.
 *
 * <p>Lua is the bundled Lua 5.4.8 unless {@code oneiro.luaExecutable} names an installed one. Every expected file must
 * also be laid out as StyLua lays it out.
 */
class ConformanceTest {

	private static final String ROOT_PACKAGE = "plugin";
	private static final Path OUTPUT = Path.of("build", "conformance");

	@TestFactory
	Stream<DynamicTest> cases() throws IOException {
		try (Stream<Path> folders = Files.list(resource("cases"))) {
			return folders.filter(folder -> Files.isDirectory(folder.resolve("input"))).sorted().toList().stream()
					.map(folder -> DynamicTest.dynamicTest(folder.getFileName().toString(), () -> run(folder)));
		}
	}

	/**
	 * Every case's Kotlin sources, where it has them: a twin of the Java input, which must give the same Lua, or a case
	 * of what only Kotlin has.
	 */
	@TestFactory
	Stream<DynamicTest> kotlinTwins() throws IOException {
		try (Stream<Path> folders = Files.list(resource("cases"))) {
			return folders.filter(folder -> Files.isDirectory(folder.resolve("kotlin"))).sorted().toList().stream()
					.map(folder -> DynamicTest.dynamicTest(folder.getFileName() + " (Kotlin)",
							() -> compileKotlinAndCompare(folder)));
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
			runLua(folder, modules, folder.getFileName().toString(), folder.resolve("check.lua"));
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
		CompileResult result = JavaFrontend.compile(sources(input, ".java"),
				new JavaFrontend.Options(ROOT_PACKAGE, classpath(folder), facts(folder)));
		return compare(folder, input, result, folder.resolve("diagnostics.txt"), null);
	}

	/**
	 * Compiles a case's Kotlin twin, in its {@code kotlin/} folder, compares the diagnostics with
	 * {@code kotlin/diagnostics.txt} and the modules with the same hand-written Lua as the Java input's, or with the
	 * twin's own in {@code kotlin/expected/} where idiomatic Kotlin reads differently, and runs them as the Java
	 * input's are run, against the twin's own {@code check.lua} where Kotlin's library behaves differently from Java's.
	 */
	private static void compileKotlinAndCompare(Path folder) throws IOException {
		Path input = folder.resolve("kotlin");
		CompileResult result = KotlinFrontend.compile(sources(input, ".kt"),
				new KotlinFrontend.Options(ROOT_PACKAGE, kotlinClasspath(folder), facts(folder)));
		Map<String, String> modules = compare(folder, input, result, input.resolve("diagnostics.txt"),
				input.resolve("expected"));
		if (Files.exists(folder.resolve("expected"))) {
			Path own = input.resolve("check.lua");
			Path check = Files.exists(own) ? own : folder.resolve("check.lua");
			runLua(folder, modules, folder.getFileName() + "-kotlin", check);
		}
	}

	private static List<Path> sources(Path input, String extension) throws IOException {
		try (Stream<Path> files = Files.walk(input)) {
			return files.filter(file -> file.toString().endsWith(extension)).sorted().toList();
		}
	}

	/**
	 * Compares what compiling a case produced with what it must: the diagnostics, and when the case has
	 * {@code expected/}, the modules.
	 *
	 * @param overrides a folder of modules that replace those of {@code expected/} at the same paths, or null
	 * @return the printed modules by path
	 */
	private static Map<String, String> compare(Path folder, Path input, CompileResult result,
			Path expectedDiagnostics, @Nullable Path overrides) throws IOException {
		String diagnostics = result.diagnostics().stream()
				.map(diagnostic -> relativeTo(input, diagnostic).format())
				.collect(Collectors.joining("\n\n"));
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
		Map<String, String> wanted = new TreeMap<>(expectedModules(expectedLua));
		if (overrides != null && Files.exists(overrides)) {
			wanted.putAll(expectedModules(overrides));
		}
		assertEquals(wanted.keySet(), actual.keySet(), "generated modules");
		for (String path : wanted.keySet()) {
			assertEquals(wanted.get(path), actual.get(path), path);
		}
		return actual;
	}

	/** The hand-written modules under a folder, by their paths in the plugin. */
	private static Map<String, String> expectedModules(Path folder) throws IOException {
		Map<String, String> modules = new TreeMap<>();
		try (Stream<Path> files = Files.walk(folder)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				modules.put(folder.relativize(file).toString().replace('\\', '/'), read(file));
			}
		}
		return modules;
	}

	/**
	 * Writes the generated modules, with the case's own Lua under {@code src/}, as a plugin's build ships it, to a
	 * plugin folder named {@code output}, and runs them and the check, when there is one, under Lua 5.4.
	 */
	private static void runLua(Path folder, Map<String, String> modules, String output, Path check)
			throws IOException {
		Path root = OUTPUT.resolve(output).toAbsolutePath();
		Path handWritten = folder.resolve("lua");
		if (Files.exists(handWritten)) {
			try (Stream<Path> files = Files.walk(handWritten)) {
				for (Path file : files.filter(Files::isRegularFile).toList()) {
					Path target = root.resolve("src").resolve(handWritten.relativize(file).toString());
					Files.createDirectories(target.getParent());
					Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
		List<String> arguments = new ArrayList<>(List.of(
				root.toString().replace('\\', '/'),
				LuaTests.formatStrStandIn(OUTPUT.toAbsolutePath()).toString(),
				Files.exists(check) ? check.toString() : ""));
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

	/**
	 * What a case's Java compiles against: the test's own classpath, which has the api and JSpecify, the RS3 bindings
	 * generated from the fixture, and the bindings of the case's own Lua.
	 */
	private static List<Path> classpath(Path folder) throws IOException {
		List<Path> classpath = new ArrayList<>(
				Stream.of(System.getProperty("java.class.path").split(File.pathSeparator)).map(Path::of).toList());
		classpath.add(FixtureBindings.JAVA);
		classpath.addAll(luaBindings(folder, BindingsGenerator.Language.JAVA, FixtureBindings.JAVA));
		return classpath;
	}

	/**
	 * What the Kotlin twins compile against: the bindings generated from the fixture for a plugin written in Kotlin,
	 * those of the case's own Lua, and Oneiro's API, JSpecify and the Kotlin standard library, which the build names.
	 * The test's own classpath holds the Analysis API, which plugin code never sees.
	 */
	private static List<Path> kotlinClasspath(Path folder) throws IOException {
		List<Path> classpath = new ArrayList<>(Stream.of(System.getProperty("oneiro.kotlinClasspath")
				.split(File.pathSeparator)).map(Path::of).toList());
		classpath.add(FixtureBindings.KOTLIN);
		classpath.addAll(luaBindings(folder, BindingsGenerator.Language.KOTLIN, FixtureBindings.KOTLIN));
		return classpath;
	}

	/**
	 * The bindings of the Lua a case uses, which must generate without a warning: the library plugins it depends on,
	 * from the definition files in {@code dependencies/required/<alias>/} and {@code dependencies/optional/<alias>/},
	 * and its own Lua, from the definition files in its {@code types/}, or else from its {@code lua/}; none for a case
	 * without either.
	 */
	private static List<Path> luaBindings(Path folder, BindingsGenerator.Language language, Path rs3Jar)
			throws IOException {
		List<BindingsGenerator.Library> dependencies = new ArrayList<>();
		for (LuaLibraries.Use use : List.of(LuaLibraries.Use.REQUIRED, LuaLibraries.Use.OPTIONAL)) {
			Path declared = folder.resolve("dependencies").resolve(use.name().toLowerCase(Locale.ROOT));
			if (Files.isDirectory(declared)) {
				try (Stream<Path> aliases = Files.list(declared)) {
					for (Path types : aliases.sorted().toList()) {
						String alias = types.getFileName().toString();
						dependencies.add(new BindingsGenerator.Library(alias, "org.adelheid.oneiro.lib." + alias, types,
								"", null, use));
					}
				}
			}
		}
		Path types = folder.resolve("types");
		Path lua = Files.isDirectory(types) ? types : folder.resolve("lua");
		if (dependencies.isEmpty() && !Files.isDirectory(lua)) {
			return List.of();
		}
		Path output = Path.of("build", "case-bindings",
				folder.getFileName() + "-" + language.name().toLowerCase(Locale.ROOT));
		BindingsGenerator.LuaResult result = BindingsGenerator.generateLua(
				Path.of(System.getProperty("oneiro.stubsFixture")), rs3Jar, output, language, ROOT_PACKAGE,
				dependencies, List.of(), lua, null);
		assertEquals(List.of(), result.warnings(), "the bindings of the Lua " + folder.getFileName() + " uses");
		List<Path> jars = new ArrayList<>(List.of(result.main().classesJar()));
		result.libraries().forEach(library -> jars.add(library.classesJar()));
		return jars;
	}

	/**
	 * The plugin facts of a case: those its {@code case.properties} gives, {@code id}, comma-separated
	 * {@code permissions} and {@code flags}, and {@code dev}, or the defaults without one; and the files of its own
	 * Lua, from its {@code lua/}.
	 */
	private static PluginFacts facts(Path folder) throws IOException {
		Properties values = new Properties();
		Path properties = folder.resolve("case.properties");
		if (Files.exists(properties)) {
			try (Reader reader = Files.newBufferedReader(properties, StandardCharsets.UTF_8)) {
				values.load(reader);
			}
		}
		Set<String> luaFiles = new TreeSet<>();
		Path lua = folder.resolve("lua");
		if (Files.isDirectory(lua)) {
			try (Stream<Path> files = Files.walk(lua)) {
				files.filter(Files::isRegularFile)
						.forEach(file -> luaFiles.add("src/" + lua.relativize(file).toString().replace('\\', '/')));
			}
		}
		return new PluginFacts(values.getProperty("id", PluginFacts.DEFAULT.id()),
				list(values.getProperty("permissions", "")), list(values.getProperty("flags", "")), luaFiles,
				Boolean.parseBoolean(values.getProperty("dev", "false")));
	}

	private static Set<String> list(String commaSeparated) {
		return Stream.of(commaSeparated.split(",")).map(String::strip).filter(value -> !value.isEmpty())
				.collect(Collectors.toSet());
	}

	/** The jar of the bindings generated from the bindings generator's stubs fixture, once per run. */
	static Path fixtureBindings() {
		return FixtureBindings.JAVA;
	}

	/**
	 * The bindings that the cases compile against, generated once per run from the bindings generator's fixture: for
	 * the Java inputs, and for the Kotlin sources, whose hooks take a callback of each kind.
	 */
	private static final class FixtureBindings {

		static final Path JAVA = generate(BindingsGenerator.Language.JAVA, "conformance-bindings");
		static final Path KOTLIN = generate(BindingsGenerator.Language.KOTLIN, "conformance-bindings-kotlin");

		private FixtureBindings() {
		}

		private static Path generate(BindingsGenerator.Language language, String folder) {
			try {
				return BindingsGenerator.generate(Path.of(System.getProperty("oneiro.stubsFixture")),
						Path.of("build", folder), language).classesJar();
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
