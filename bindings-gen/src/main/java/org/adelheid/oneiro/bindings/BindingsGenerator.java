package org.adelheid.oneiro.bindings;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.adelheid.oneiro.api.LuaGlobal;
import org.jspecify.annotations.NullMarked;

/**
 * Generates the RS3 bindings from a checkout of runescape-api-stubs: reads the stubs, applies the bundled overrides,
 * writes the Java sources and the constant chunks, compiles the sources, and packs two jars, the classes that plugins
 * compile against and the sources that IDEs show as documentation. It runs on the developer's machine, so nothing
 * derived from the stubs is ever published.
 */
public final class BindingsGenerator {

	/** The jar of classes plugins compile against. */
	public static final String CLASSES_JAR = "rs3-bindings.jar";
	/** The jar of the classes' sources, for an IDE's documentation. */
	public static final String SOURCES_JAR = "rs3-bindings-sources.jar";

	/**
	 * What a run produced.
	 *
	 * @param warnings what the stubs or the overrides say that the bindings could not follow, for the build log
	 * @param notes lines of the overrides file that the stubs have caught up with, for Oneiro's own tests: they can be
	 *     deleted, but change nothing while they stay
	 */
	public record Result(Path classesJar, Path sourcesJar, List<String> warnings, List<String> notes) {

		public Result {
			warnings = List.copyOf(warnings);
			notes = List.copyOf(notes);
		}
	}

	private BindingsGenerator() {
	}

	/** Generates the bindings into {@code output}, replacing what an earlier run left there. */
	public static Result generate(Path stubsRoot, Path output) throws IOException {
		return generate(stubsRoot, output, Overrides.bundled());
	}

	/**
	 * Generates the bindings and returns the warnings, for the Gradle plugin's worker, which calls this reflectively
	 * because the plugin's own classes are compiled for an older Java than Oneiro's.
	 */
	public static List<String> generateForBuild(String stubsRoot, String output) throws IOException {
		return generate(Path.of(stubsRoot), Path.of(output)).warnings();
	}

	static Result generate(Path stubsRoot, Path output, Overrides overrides) throws IOException {
		Stubs stubs = Stubs.read(stubsRoot);
		List<String> warnings = new ArrayList<>();
		for (ApiModel.Unparsed unparsed : stubs.model().unparsed) {
			warnings.add(unparsed.file() + ":" + unparsed.line() + ": not understood (" + unparsed.pattern() + "): "
					+ unparsed.text());
		}
		JavaBindings bindings = new JavaBindings(stubs, overrides, warnings);
		Map<String, String> sources = bindings.sources();
		for (Overrides.Entry unused : overrides.unused()) {
			warnings.add("overrides.txt:" + unused.line() + ": " + unused.fact() + " " + unused.target()
					+ " matches nothing in these stubs.");
		}
		List<String> notes = new ArrayList<>();
		for (Overrides.Entry caughtUp : overrides.noLongerNeeded()) {
			notes.add("overrides.txt:" + caughtUp.line() + ": " + caughtUp.fact() + " " + caughtUp.target()
					+ " is no longer needed: the stubs " + (caughtUp.fact().equals("skip")
							? "no longer have it." : "now say the same."));
		}

		Path work = output.resolve("work");
		delete(work);
		Path sourceRoot = work.resolve("sources");
		Path classes = work.resolve("classes");
		Files.createDirectories(classes);
		List<Path> sourceFiles = new ArrayList<>();
		for (Map.Entry<String, String> source : sources.entrySet()) {
			Path file = sourceRoot.resolve(source.getKey());
			Files.createDirectories(file.getParent());
			Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
			sourceFiles.add(file);
		}
		for (JavaBindings.ConstantNamespace namespace : bindings.constants()) {
			ConstantChunks.write(namespace, classes);
		}
		compile(sourceFiles, classes);

		Path classesJar = output.resolve(CLASSES_JAR);
		Path sourcesJar = output.resolve(SOURCES_JAR);
		Jars.write(classes, classesJar);
		Jars.write(sourceRoot, sourcesJar);
		delete(work);
		return new Result(classesJar, sourcesJar, warnings, notes);
	}

	/** Compiles the generated sources against the chunks, the binding annotations and JSpecify. */
	private static void compile(List<Path> sources, Path classes) throws IOException {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			throw new IOException("The bindings are compiled with javac, but this Java runtime has none; run it"
					+ " on a JDK.");
		}
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		String classpath = Stream.of(classes, location(LuaGlobal.class), location(NullMarked.class))
				.map(Path::toString)
				.collect(Collectors.joining(File.pathSeparator));
		try (StandardJavaFileManager files =
				compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
			List<String> options = List.of("--release", "25", "-proc:none", "-implicit:none", "-nowarn", "-Xlint:none",
					"-encoding", "UTF-8", "-d", classes.toString(), "-classpath", classpath);
			boolean compiled = compiler.getTask(null, files, diagnostics, options, null,
					files.getJavaFileObjectsFromPaths(sources)).call();
			if (!compiled) {
				String errors = diagnostics.getDiagnostics().stream()
						.filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
						.limit(20)
						.map(diagnostic -> diagnostic.getSource() == null
								? diagnostic.getMessage(Locale.ROOT)
								: diagnostic.getSource().getName() + ":" + diagnostic.getLineNumber() + ": "
										+ diagnostic.getMessage(Locale.ROOT))
						.collect(Collectors.joining("\n"));
				throw new IOException("The generated bindings did not compile, which is a bug in Oneiro:\n" + errors);
			}
		}
	}

	/** The jar or folder a class was loaded from. */
	private static Path location(Class<?> type) throws IOException {
		try {
			return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
		} catch (URISyntaxException | NullPointerException e) {
			throw new IOException("Cannot find where " + type.getName() + " was loaded from", e);
		}
	}

	private static void delete(Path folder) throws IOException {
		if (!Files.exists(folder)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(folder)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		}
	}
}
