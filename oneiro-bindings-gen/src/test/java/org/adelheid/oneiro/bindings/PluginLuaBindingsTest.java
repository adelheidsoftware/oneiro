package org.adelheid.oneiro.bindings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The bindings of a plugin's own Lua, from the fixtures in {@code src/test/plugin-lua}: {@code main}, which ships under
 * {@code src/}, and {@code dev}, which goes under {@code dev/} and names the main set's types. Each module is a class
 * of the plugin's package of its folder, and each source set's classes are in a jar of their own.
 */
class PluginLuaBindingsTest {

	private static final Path STUBS = Path.of("src", "test", "stubs");
	private static final Path LUA = Path.of("src", "test", "plugin-lua");
	private static final String ROOT = "com.example.herbs";
	private static BindingsGenerator.LibraryResult main;
	private static BindingsGenerator.LibraryResult dev;

	@BeforeAll
	static void generate() throws IOException {
		Path output = Path.of("build", "plugin-lua-bindings");
		BindingsGenerator.Result rs3 = BindingsGenerator.generate(STUBS, output.resolve("rs3"),
				BindingsGenerator.Language.JAVA);
		List<BindingsGenerator.LibraryResult> results = BindingsGenerator.generatePluginLua(STUBS, rs3.classesJar(),
				output, BindingsGenerator.Language.JAVA, ROOT, LUA.resolve("main"), LUA.resolve("dev"));
		main = results.get(0);
		dev = results.get(1);
	}

	@Test
	void generateWithoutAWarning() {
		assertEquals(List.of(), main.warnings());
		assertEquals(List.of(), dev.warnings());
	}

	/** A module is a class of its folder's package, which code there names without an import. */
	@Test
	void modulesAreClassesOfThePackageOfTheirFolder() throws IOException {
		String levels = source(main, "core/Levels.java");
		assertTrue(levels.contains("package com.example.herbs.core;\n"), levels);
		assertTrue(levels.contains("@LuaModule(\"src/core/levels\")\n@LuaGlobal(\"Levels\")\n@NullMarked\n"
				+ "public final class Levels {"), levels);
		assertTrue(levels.contains("public static native int fromPenalty(int penalty);"), levels);
		String tally = source(main, "core/Tally.java");
		assertTrue(tally.contains("@LuaGlobal(\"Tally\")\n@NullMarked\npublic class Tally {"), tally);
		assertTrue(tally.contains("public Tally(int start) {"), tally);
	}

	/**
	 * A type of a module in another folder, of the RS3 API, or the callback interface of the root package is imported.
	 */
	@Test
	void typesOfOtherPackagesAreImported() throws IOException {
		String badge = source(main, "ui/Badge.java");
		assertTrue(badge.contains("package com.example.herbs.ui;\n"), badge);
		assertTrue(badge.contains("import com.example.herbs.Function3;\n"), badge);
		assertTrue(badge.contains("import com.example.herbs.core.Tally;\n"), badge);
		assertTrue(badge.contains("import org.adelheid.oneiro.rs3.ui;\n"), badge);
		assertTrue(badge.contains("public static native void show(ui.Component parent, Tally tally,"
				+ " Function3<Integer, Integer, Boolean, String> format);"), badge);
		String cheats = source(dev, "Cheats.java");
		assertTrue(cheats.contains("@LuaModule(\"dev/cheats\")"), cheats);
		assertTrue(cheats.contains("import com.example.herbs.core.Tally;\n"), cheats);
	}

	/** Each source set's jar holds its own classes, the dev set's none of the main set's. */
	@Test
	void eachSourceSetsJarHoldsItsOwnClasses() throws IOException {
		assertEquals(List.of("com/example/herbs/Function3.class", "com/example/herbs/LuaOnly.class",
				"com/example/herbs/core/Levels.class", "com/example/herbs/core/LuaOnly.class",
				"com/example/herbs/core/Tally.class", "com/example/herbs/ui/Badge.class",
				"com/example/herbs/ui/LuaOnly.class"), classes(main));
		assertEquals(List.of("com/example/herbs/Cheats.class", "com/example/herbs/LuaOnly.class"), classes(dev));
	}

	/** The plugin's code compiles against the jars, naming a module of its own package without an import. */
	@Test
	void pluginCodeCompilesAgainstThem() throws IOException, URISyntaxException {
		Path usage = Path.of(Objects.requireNonNull(getClass().getResource("/usage/PluginLuaUsage.java")).toURI());
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		Path output = Files.createDirectories(Path.of("build", "plugin-lua-usage"));
		String classpath = Stream.concat(
						Stream.of(main.classesJar(), dev.classesJar(),
										Path.of("build", "plugin-lua-bindings", "rs3", BindingsGenerator.CLASSES_JAR))
								.map(Path::toString),
						Stream.of(System.getProperty("java.class.path").split(File.pathSeparator)))
				.collect(Collectors.joining(File.pathSeparator));
		try (StandardJavaFileManager files =
				compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
			boolean compiled = compiler.getTask(null, files, diagnostics,
					List.of("--release", "25", "-proc:none", "-d", output.toString(), "-classpath", classpath), null,
					files.getJavaFileObjectsFromPaths(List.of(usage))).call();
			assertTrue(compiled, () -> diagnostics.getDiagnostics().stream()
					.map(diagnostic -> diagnostic.getLineNumber() + ": " + diagnostic.getMessage(Locale.ROOT))
					.collect(Collectors.joining("\n")));
		}
	}

	private static List<String> classes(BindingsGenerator.LibraryResult result) throws IOException {
		try (JarFile jar = new JarFile(result.classesJar().toFile())) {
			return Collections.list(jar.entries()).stream()
					.map(JarEntry::getName)
					.filter(name -> name.endsWith(".class"))
					.sorted()
					.toList();
		}
	}

	private static String source(BindingsGenerator.LibraryResult result, String file) throws IOException {
		try (JarFile jar = new JarFile(result.sourcesJar().toFile());
				InputStream in = jar.getInputStream(jar.getEntry(ROOT.replace('.', '/') + "/" + file))) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
