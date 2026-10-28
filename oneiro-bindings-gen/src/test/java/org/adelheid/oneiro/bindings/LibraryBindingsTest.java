package org.adelheid.oneiro.bindings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.adelheid.rspc.plugin.LuaLibraries;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The bindings of the library fixtures in {@code src/test/libraries}, against the RS3 bindings of the stubs fixture:
 * {@code widgets}, a vendored library's annotated code, whose modules become classes nested as Lua reaches them;
 * {@code gauges}, definition files for an optional library plugin seen as a global, whose types name the RS3 API's;
 * and {@code beacons}, a required library plugin's own code, whose {@code main.lua} returns the table the global is.
 * They generate without a warning, a file that uses each shape compiles against them, and each library's jar holds its
 * own classes.
 */
class LibraryBindingsTest {

	private static final Path STUBS = Path.of("src", "test", "stubs");
	private static final Path LIBRARIES = Path.of("src", "test", "libraries");
	private static BindingsGenerator.Result result;
	private static BindingsGenerator.Result kotlinResult;

	@BeforeAll
	static void generate() throws IOException {
		result = generate(BindingsGenerator.Language.JAVA);
		kotlinResult = generate(BindingsGenerator.Language.KOTLIN);
	}

	private static BindingsGenerator.Result generate(BindingsGenerator.Language language) throws IOException {
		return BindingsGenerator.generate(STUBS,
				Path.of("build", "library-bindings-" + language.name().toLowerCase(Locale.ROOT)), Overrides.bundled(),
				language, List.of(
						new BindingsGenerator.Library("widgets", "org.adelheid.oneiro.lib.widgets",
								LIBRARIES.resolve("widgets"), "", null, LuaLibraries.Use.VENDORED),
						new BindingsGenerator.Library("gauges", "org.adelheid.oneiro.lib.gauges",
								LIBRARIES.resolve("addon").resolve("library"), "", null, LuaLibraries.Use.OPTIONAL),
						new BindingsGenerator.Library("beacons", "org.adelheid.oneiro.lib.beacons",
								LIBRARIES.resolve("beacons"), "", null, LuaLibraries.Use.REQUIRED)));
	}

	@Test
	void librariesGenerateWithoutAWarning() {
		for (BindingsGenerator.LibraryResult library : result.libraries()) {
			assertEquals(List.of(), library.warnings(), library.name());
		}
	}

	/** Java code compiles against either language's bindings, so the forms of one clash nowhere. */
	@ParameterizedTest
	@EnumSource(BindingsGenerator.Language.class)
	void everyShapeCompiles(BindingsGenerator.Language language) throws IOException, URISyntaxException {
		BindingsGenerator.Result bindings = language == BindingsGenerator.Language.JAVA ? result : kotlinResult;
		Path usage = Path.of(Objects.requireNonNull(getClass().getResource("/usage/LibraryUsage.java")).toURI());
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		Path output = Files.createDirectories(
				Path.of("build", "library-usage-" + language.name().toLowerCase(Locale.ROOT)));
		String classpath = Stream.of(Stream.of(bindings.classesJar().toString()),
						bindings.libraries().stream().map(library -> library.classesJar().toString()),
						Stream.of(System.getProperty("java.class.path").split(File.pathSeparator)))
				.flatMap(stream -> stream)
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

	/**
	 * A module is a top-level class, named as its {@code require} binds it, whose annotations say so; the tables it
	 * holds in fields are nested in it, with their Lua paths from the module.
	 */
	@Test
	void modulesAreClassesNestedAsLuaReachesThem() throws IOException {
		String widgets = source("widgets", "widgets.java");
		assertTrue(widgets.contains("@LuaModule(\"widgets\")\n@LuaGlobal(\"widgets\")\npublic final class widgets {"),
				widgets);
		assertTrue(widgets.contains("\t@LuaGlobal(\"widgets.Counter\")\n\tpublic static class Counter {"));
		assertTrue(widgets.contains("<p>Lua: {@code widgets.Counter.new(start)}"));
		assertTrue(widgets.contains("\t@LuaGlobal(\"widgets.text.COLOURS\")\n\t\tpublic static final class COLOURS {"));
		assertTrue(widgets.contains("public static class LoudCounter extends widgets.Counter {"));
	}

	/**
	 * The members follow their annotations: generics, {@code self}, named return values, callbacks of three values,
	 * {@code (key)} enums and {@code @deprecated}; an override without annotations leaves the inherited method to
	 * serve.
	 */
	@Test
	void membersFollowTheirAnnotations() throws IOException {
		String widgets = source("widgets", "widgets.java");
		// Any Lua value, nil among them, can be a type variable
		assertTrue(widgets.contains("public static native <T extends @Nullable Object> @Nullable T find(List<T> list,"
				+ " Predicate<T> test);"));
		assertTrue(widgets.contains("public native widgets.Counter advance(int by);"));
		assertTrue(widgets.contains("public record MeasureResult(int width, boolean cut) {"));
		assertTrue(widgets.contains(
				"public static native <T extends @Nullable Object> String join(List<T> list,"
						+ " Function3<T, Integer, Boolean, String> format);"));
		assertTrue(widgets.contains("public static native void paint(String colour);"));
		assertTrue(widgets.contains("\t\t@Deprecated\n\t\tpublic native void bump();"));
		String loudCounter = widgets.substring(widgets.indexOf("class LoudCounter"), widgets.indexOf("class text"));
		assertFalse(loudCounter.contains("advance"), loudCounter);
		String callback = source("widgets", "Function3.java");
		assertTrue(callback.contains("public interface Function3<T1, T2, T3, R> {"), callback);
		assertTrue(callback.contains("\tR apply(T1 value1, T2 value2, T3 value3);"));
	}

	/**
	 * A library plugin's global is a class like a global table of the RS3 API's, and a type it names from the RS3 API
	 * is the RS3 bindings' class, imported.
	 */
	@Test
	void aGlobalNamesTheRs3ApisTypes() throws IOException {
		String gauges = source("gauges", "gauges.java");
		assertTrue(gauges.contains("@LuaGlobal(\"gauges\")\npublic final class gauges {"), gauges);
		assertTrue(gauges.contains("import org.adelheid.oneiro.rs3.ui;\n"));
		assertTrue(gauges.contains("public Gauge(ui.Component parent, gauges.GaugeOptions options) {"));
		assertTrue(gauges.contains("\t@LuaType(\"gauges.GaugeOptions\")\n\tpublic static class GaugeOptions {"));
		assertTrue(gauges.contains("\t\tpublic GaugeOptions() {"));
		// A table the plugin builds for one type of a union parameter
		assertTrue(gauges.contains("public native void SetLabel(String label);"));
		assertTrue(gauges.contains("public native void SetLabel(gauges.LabelOptions label);"));
		assertTrue(gauges.contains("\t\tpublic LabelOptions() {"));
		// The class such tables extend, which only holds the fields they share
		assertTrue(gauges.contains("<p>Lua: values of the type {@code gauges.Style}: the fields its subclasses share,"
				+ " whose tables the plugin builds and passes to the library."), gauges);
		assertTrue(gauges.contains("\t\tprotected Style() {"));
		assertTrue(gauges.contains("public native gauges.Gauge Highlight(int colour);"));
		// Prompt.new makes a gauge, not a prompt: a function of its table
		assertTrue(gauges.contains(
				"\t\t@LuaName(\"new\")\n\t\tpublic static native gauges.Gauge create(ui.Component parent);"));
		// A type of another library the build declares
		assertTrue(gauges.contains("import org.adelheid.oneiro.lib.widgets.widgets;\n"));
		assertTrue(gauges.contains("public native void Follow(widgets.Counter counter);"));
	}

	/**
	 * A library plugin's global is the plugin's copy of its table, whose fields it cannot change; its class holds the
	 * alias, and for an optional dependency, the check that it is loaded.
	 */
	@Test
	void anOptionalLibraryPluginsGlobalHasItsCheck() throws IOException {
		String gauges = source("gauges", "gauges.java");
		assertTrue(gauges.contains("@LuaOptional\n@LuaGlobal(\"gauges\")\npublic final class gauges {"), gauges);
		assertTrue(gauges.contains("\tpublic static final String ALIAS = \"gauges\";\n"), gauges);
		assertTrue(gauges.contains("\tpublic static native boolean isLoaded();\n"), gauges);
		assertTrue(gauges.contains("\tpublic static final String version = LuaOnly.value();\n"), gauges);
	}

	/**
	 * Read from a library plugin's own code, the global is what its main.lua returns, and the tables that holds are
	 * nested in it; a required dependency needs no check.
	 */
	@Test
	void aLibraryPluginsMainIsItsGlobal() throws IOException {
		String beacons = source("beacons", "beacons.java");
		assertTrue(beacons.contains("@LuaGlobal(\"beacons\")\npublic final class beacons {"), beacons);
		assertFalse(beacons.contains("@LuaModule"), beacons);
		assertTrue(beacons.contains("\tpublic static final String ALIAS = \"beacons\";\n"), beacons);
		assertFalse(beacons.contains("isLoaded"), beacons);
		assertTrue(beacons.contains("\tpublic static final String version = LuaOnly.value();\n"), beacons);
		assertTrue(beacons.contains("\tpublic static native int lit();\n"), beacons);
		assertTrue(beacons.contains("\t@LuaGlobal(\"beacons.Beacon\")\n\tpublic static class Beacon {"), beacons);
		assertTrue(beacons.contains("\t\tpublic Beacon(String label) {"), beacons);
		assertTrue(beacons.contains("\t\tpublic static native int count();\n"), beacons);
		assertTrue(beacons.contains("\t\tpublic boolean lit;\n"), beacons);
	}

	@Test
	void eachLibrarysJarHoldsItsOwnClasses() throws IOException {
		for (BindingsGenerator.LibraryResult library : result.libraries()) {
			try (JarFile jar = new JarFile(library.classesJar().toFile())) {
				List<String> names = Collections.list(jar.entries()).stream()
						.map(JarEntry::getName)
						.filter(name -> name.endsWith(".class"))
						.toList();
				assertFalse(names.isEmpty(), library.name());
				String folder = "org/adelheid/oneiro/lib/" + library.name() + "/";
				assertTrue(names.stream().allMatch(name -> name.startsWith(folder)), names::toString);
			}
		}
	}

	private static String source(String library, String file) throws IOException {
		BindingsGenerator.LibraryResult found = result.libraries().stream()
				.filter(candidate -> candidate.name().equals(library))
				.findFirst()
				.orElseThrow();
		try (JarFile jar = new JarFile(found.sourcesJar().toFile());
				InputStream in = jar.getInputStream(jar.getEntry("org/adelheid/oneiro/lib/" + library + "/" + file))) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
