package org.adelheid.oneiro.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.adelheid.oneiro.plugin.CompilerSettings;
import org.adelheid.oneiro.plugin.LuaLineMap;
import org.junit.jupiter.api.Test;

/**
 * Oneiro as a javac plugin, as a plugin's compileJava runs it: the plugin-class case compiles to the same Lua as the
 * conformance suite expects, and a rejection fails the compilation as a javac error at its place.
 */
class JavacPluginTest {

	private static final Path OUTPUT = Path.of("build", "javac-plugin").toAbsolutePath();

	private record Compilation(boolean succeeded, List<Diagnostic<? extends JavaFileObject>> diagnostics) {
	}

	private static Compilation javac(Path input, String id, Path lua) throws IOException {
		List<Path> sources;
		try (Stream<Path> files = Files.walk(input)) {
			sources = files.filter(file -> file.toString().endsWith(".java")).sorted().toList();
		}
		Path settings = OUTPUT.resolve(id + ".properties");
		new CompilerSettings("", id, Set.of(), Set.of(), Set.of(), false, List.of(), lua, lineMap(id), false)
				.write(settings);
		List<String> classpath =
				new ArrayList<>(List.of(System.getProperty("java.class.path").split(File.pathSeparator)));
		classpath.add(ConformanceTest.fixtureBindings().toString());
		String path = String.join(File.pathSeparator, classpath);
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager files =
				compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
			List<String> options = List.of("--release", "25", "-XDcompilePolicy=simple",
					"-Xplugin:Oneiro " + settings.toUri(), "-classpath", path, "-processorpath", path,
					"-d", OUTPUT.resolve(id + "-classes").toString());
			boolean succeeded = compiler.getTask(null, files, diagnostics, options, null,
					files.getJavaFileObjectsFromPaths(sources)).call();
			return new Compilation(succeeded, diagnostics.getDiagnostics());
		}
	}

	private static Path lineMap(String id) {
		return OUTPUT.resolve(id + "-line-map.txt");
	}

	@Test
	void compileJavaWritesTheLua() throws IOException {
		Path lua = OUTPUT.resolve("plugin-class-lua");
		Path folder = ConformanceTest.resource("cases/plugin-class");
		Compilation compilation = javac(folder.resolve("input"), "herbs", lua);
		assertTrue(compilation.succeeded(), () -> compilation.diagnostics().toString());
		Map<String, String> written = new TreeMap<>();
		Map<String, String> expected = new TreeMap<>();
		try (Stream<Path> files = Files.walk(lua)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				written.put(lua.relativize(file).toString().replace('\\', '/'), Files.readString(file));
			}
		}
		Path expectedFolder = folder.resolve("expected");
		try (Stream<Path> files = Files.walk(expectedFolder)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				expected.put(expectedFolder.relativize(file).toString().replace('\\', '/'), Files.readString(file));
			}
		}
		assertEquals(expected, written);
		// The line map names each Lua line's Java line, by the Java file's path in its source folder
		LuaLineMap lines = LuaLineMap.read(lineMap("herbs"));
		List<String> main = Files.readAllLines(lua.resolve("main.lua"));
		int subscription = main.indexOf("Event.ServerLogic.Subscribe(\"herbs.onServerLogic\", onServerLogic)") + 1;
		assertEquals(new LuaLineMap.Source("plugin/HerbPlugin.java", 39), lines.sourceOf("main.lua", subscription));
		assertEquals(null, lines.sourceOf("main.lua", 1), "the file's path, which no Java line wrote");
	}

	@Test
	void aRejectionFailsTheCompilationAtItsPlace() throws IOException {
		Path lua = OUTPUT.resolve("rejections-lua");
		Compilation compilation = javac(ConformanceTest.resource("cases/plugin-rejections/input"), "bad", lua);
		assertFalse(compilation.succeeded());
		List<String> errors = compilation.diagnostics().stream()
				.filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
				.map(diagnostic -> diagnostic.getSource().getName().replaceAll(".*[\\\\/]", "") + ":"
						+ diagnostic.getLineNumber() + ": " + diagnostic.getMessage(Locale.ROOT))
				.collect(Collectors.toList());
		assertEquals(22, errors.size(), errors::toString);
		assertTrue(errors.contains("BadPlugin.java:57: startUp() runs once, when the plugin starts; move what it shares"
				+ " into a method of its own and call that."), errors::toString);
		assertFalse(Files.exists(lua.resolve("main.lua")), "no Lua is written while an error remains");
	}
}
