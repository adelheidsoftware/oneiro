package org.adelheid.oneiro.bindings;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.adelheid.oneiro.api.LuaGlobal;
import org.adelheid.oneiro.plugin.ClientFacts;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Generates the RS3 bindings from a checkout of runescape-api-stubs, and the bindings of the Lua libraries a plugin
 * uses and of its own Lua from their LuaCATS types: reads the types, applies the overrides, writes the Java sources
 * and, for the RS3 API, the constant chunks, compiles the sources, and packs two jars for each, the classes that
 * plugins compile against and the sources that IDEs show as documentation. It runs on the developer's machine, so
 * nothing derived from the stubs or a library is ever published.
 */
public final class BindingsGenerator {

	/** The jar of classes plugins compile against. */
	public static final String CLASSES_JAR = "rs3-bindings.jar";
	/** The jar of the classes' sources, for an IDE's documentation. */
	public static final String SOURCES_JAR = "rs3-bindings-sources.jar";

	/**
	 * What a run produced.
	 *
	 * @param clientFacts the file of the members the bindings mark as needing a permission or for development only,
	 *     which {@code checkPlugin} looks for in the Lua that ships
	 * @param warnings what the stubs or the overrides say that the bindings could not follow, for the build log
	 * @param notes lines of the overrides file that the stubs have caught up with, for Oneiro's own tests: they can be
	 *     deleted, but change nothing while they stay
	 * @param libraries what the run produced for each library, in the order they were given
	 */
	public record Result(Path classesJar, Path sourcesJar, Path clientFacts, List<String> warnings, List<String> notes,
			List<LibraryResult> libraries) {

		public Result {
			warnings = List.copyOf(warnings);
			notes = List.copyOf(notes);
			libraries = List.copyOf(libraries);
		}
	}

	/**
	 * A Lua library to write bindings for.
	 *
	 * @param name the library's name, which its jars are named after, such as {@code zyklib}
	 * @param packageName the package its bindings go in
	 * @param folder the folder of its LuaCATS files: its own code, or definition files written for it
	 * @param modulePrefix what comes before a file's path in the path {@code require} loads it by: empty for a library
	 *     whose folder goes to the plugin's root, as Zyklib's does
	 * @param overrides the plugin's overrides file for the library; null when it keeps none
	 */
	record Library(String name, String packageName, Path folder, String modulePrefix, @Nullable Path overrides) {

		/** The model of every Lua file under the folder, each loaded by its path from there after the prefix. */
		ApiModel read() throws IOException {
			return BindingsGenerator.read(folder, modulePrefix);
		}
	}

	/**
	 * What a run produced for one library.
	 *
	 * @param warnings what its types or its overrides say that the bindings could not follow, each starting with the
	 *     library's name
	 * @param notes lines of its overrides file that its types have caught up with
	 */
	public record LibraryResult(String name, Path classesJar, Path sourcesJar, List<String> warnings,
			List<String> notes) {

		public LibraryResult {
			warnings = List.copyOf(warnings);
			notes = List.copyOf(notes);
		}
	}

	/**
	 * The language of the plugin the bindings are for, which decides how a member that takes a hook types the
	 * callback: each language types a lambda by the hook in the way it can.
	 */
	public enum Language {

		/** One {@code Subscribe(ui.Hook<C, ?> hook, C callback)}, from which javac types a lambda by its hook. */
		JAVA,

		/**
		 * One {@code Subscribe} for each callback a hook takes, such as
		 * {@code Subscribe(ui.Hook<ui.Hook.Pointer, ?> hook, ui.Hook.Pointer callback)}, because Kotlin converts a
		 * lambda to an interface only when the parameter names it, so that a hook's callback can be a trailing lambda.
		 */
		KOTLIN
	}

	private BindingsGenerator() {
	}

	/** Generates the RS3 bindings into {@code output}, replacing what an earlier run left there. */
	public static Result generate(Path stubsRoot, Path output, Language language) throws IOException {
		return generate(stubsRoot, output, Overrides.bundled(), language, List.of());
	}

	/**
	 * {@link #generate(Path, Path, Language)} for the Gradle plugin's worker, returning the warnings. The Gradle plugin
	 * is compiled for an older Java than Oneiro, so it calls this reflectively, with plain strings: the language is
	 * {@code JAVA} or {@code KOTLIN}.
	 */
	public static List<String> generateForBuild(String stubsRoot, String output, String language) throws IOException {
		return generate(Path.of(stubsRoot), Path.of(output), Language.valueOf(language)).warnings();
	}

	/**
	 * Generates the bindings of the plugin's own Lua into {@code output}, replacing what an earlier run left there:
	 * from the LuaCATS files of the main source set, which ship under {@code src/}, {@code main-bindings.jar}, and from
	 * those of the dev source set, which go under {@code dev/}, {@code dev-bindings.jar}, each with a sources jar. A
	 * file's folders below its source set's folder are packages below the root package, so that
	 * {@code core/levels.lua} is the class {@code <root>.core.Levels}. A type the Lua names but does not declare
	 * resolves to the RS3 bindings' class, and the dev set's also to the main set's; the main set's classes compile
	 * against the RS3 jar, and the dev set's against both.
	 *
	 * @param rs3Jar the classes of the RS3 bindings the stubs gave
	 * @param main the folder of the main source set's LuaCATS files, its Lua or definition files for it; a folder that
	 *     does not exist has none
	 * @param dev the folder of the dev source set's, or null for none
	 * @return what each source set's bindings produced, the main set's first
	 */
	public static List<LibraryResult> generatePluginLua(Path stubsRoot, Path rs3Jar, Path output, Language language,
			String rootPackage, Path main, @Nullable Path dev) throws IOException {
		Map<String, ApiModel> models = new LinkedHashMap<>();
		models.put("main", read(main, "src/"));
		models.put("dev", dev == null ? new ApiModel() : read(dev, "dev/"));
		Path work = output.resolve("work");
		delete(work);
		List<LibraryResult> results = new ArrayList<>();
		List<JavaBindings> named = new ArrayList<>();
		if (models.values().stream().anyMatch(model -> !model.classes.isEmpty() || !model.enums.isEmpty())) {
			// The RS3 API's types, which the Lua may name; Lua that declares nothing names none
			named.add(new JavaBindings(Stubs.readTypes(stubsRoot).model(), Map.of(), Overrides.bundled(),
					BindingsTarget.rs3(), language, new ArrayList<>()));
		}
		List<Path> classpath = new ArrayList<>(List.of(rs3Jar));
		for (Map.Entry<String, ApiModel> entry : models.entrySet()) {
			String sourceSet = entry.getKey();
			ApiModel model = entry.getValue();
			Path folder = sourceSet.equals("main") ? main : dev;
			List<String> warnings = new ArrayList<>();
			for (ApiModel.Unparsed unparsed : model.unparsed) {
				warnings.add(Objects.requireNonNull(folder).resolve(unparsed.file()) + ":" + unparsed.line()
						+ ": not understood (" + unparsed.pattern() + "): " + unparsed.text());
			}
			JavaBindings bindings = new JavaBindings(model, Map.of(), Overrides.none(),
					BindingsTarget.plugin(sourceSet, rootPackage), language, warnings);
			// The dev set's Lua names the main set's types before the RS3 API's, as its own come first
			bindings.foreign.addAll(named.reversed());
			Path sources = Files.createDirectories(work.resolve(sourceSet).resolve("sources"));
			Path classes = Files.createDirectories(work.resolve(sourceSet).resolve("classes"));
			List<Path> sourceFiles = write(bindings.sources(), sources);
			if (!sourceFiles.isEmpty()) {
				compile(sourceFiles, classes, classpath);
			}
			Path classesJar = output.resolve(sourceSet + "-bindings.jar");
			Path sourcesJar = output.resolve(sourceSet + "-bindings-sources.jar");
			Jars.write(classes, classesJar, "");
			Jars.write(sources, sourcesJar, "");
			results.add(new LibraryResult(sourceSet, classesJar, sourcesJar, warnings, List.of()));
			named.add(bindings);
			classpath.add(classes);
		}
		delete(work);
		return results;
	}

	/**
	 * {@link #generatePluginLua} for the Gradle plugin's worker, called reflectively with plain strings, returning the
	 * warnings of both source sets.
	 */
	public static List<String> generatePluginLuaForBuild(String stubsRoot, String rs3Jar, String output,
			String language, String rootPackage, String main, String dev) throws IOException {
		return generatePluginLua(Path.of(stubsRoot), Path.of(rs3Jar), Path.of(output), Language.valueOf(language),
				rootPackage, Path.of(main), Path.of(dev)).stream()
				.flatMap(result -> result.warnings().stream())
				.toList();
	}

	/**
	 * The model of every Lua file under a folder, each loaded by its path from there after the prefix; an empty one
	 * when the folder does not exist.
	 *
	 * @param modulePrefix what comes before a file's path in the path {@code require} loads it by
	 */
	private static ApiModel read(Path folder, String modulePrefix) throws IOException {
		ApiModel model = new ApiModel();
		if (!Files.isDirectory(folder)) {
			return model;
		}
		LuaCatsReader reader = new LuaCatsReader(model);
		List<Path> files;
		try (Stream<Path> walk = Files.walk(folder)) {
			files = walk.filter(path -> path.toString().endsWith(".lua")).sorted().toList();
		}
		for (Path file : files) {
			String path = folder.relativize(file).toString().replace('\\', '/');
			reader.read(file, path, modulePrefix + path.substring(0, path.length() - ".lua".length()));
		}
		reader.finish();
		return model;
	}

	/**
	 * Generates the RS3 bindings, and those of each library, into {@code output}, replacing what an earlier run left
	 * there. A library's jars are {@code <name>-bindings.jar} and {@code <name>-bindings-sources.jar}.
	 */
	static Result generate(Path stubsRoot, Path output, Overrides overrides, Language language,
			List<Library> libraries) throws IOException {
		Stubs stubs = Stubs.read(stubsRoot);
		List<String> warnings = new ArrayList<>();
		for (ApiModel.Unparsed unparsed : stubs.model().unparsed) {
			warnings.add(unparsed.file() + ":" + unparsed.line() + ": not understood (" + unparsed.pattern() + "): "
					+ unparsed.text());
		}
		JavaBindings bindings = new JavaBindings(stubs.model(), stubs.namespaces(), overrides, BindingsTarget.rs3(),
				language, warnings);
		Map<String, String> sources = bindings.sources();
		List<String> notes = new ArrayList<>();
		overrideReports(overrides, "these stubs", warnings, notes);

		Path work = output.resolve("work");
		delete(work);
		Path sourceRoot = work.resolve("sources");
		Path classes = work.resolve("classes");
		Files.createDirectories(classes);
		List<Path> sourceFiles = write(sources, sourceRoot);
		for (JavaBindings.ConstantNamespace namespace : bindings.constants()) {
			ConstantChunks.write(namespace, classes);
		}
		compile(sourceFiles, classes, List.of(classes));

		Path classesJar = output.resolve(CLASSES_JAR);
		Path sourcesJar = output.resolve(SOURCES_JAR);
		Jars.write(classes, classesJar, "");
		Jars.write(sourceRoot, sourcesJar, "");
		Path clientFacts = output.resolve(ClientFacts.FILE);
		new ClientFacts(bindings.permissions, bindings.devOnly).write(clientFacts);
		List<LibraryResult> libraryResults = libraries.isEmpty()
				? List.of()
				: generateLibraries(bindings, classesJar, libraries, language, output, work.resolve("libraries"));
		delete(work);
		return new Result(classesJar, sourcesJar, clientFacts, warnings, notes, libraryResults);
	}

	/**
	 * The libraries' bindings. A type a library names but does not declare resolves to the RS3 bindings' class, or else
	 * to another library's, so the libraries' sources compile together, against the RS3 jar; each library's classes and
	 * sources then go into jars of their own.
	 */
	private static List<LibraryResult> generateLibraries(JavaBindings rs3, Path rs3Jar, List<Library> libraries,
			Language language, Path output, Path work) throws IOException {
		List<JavaBindings> all = new ArrayList<>();
		List<List<String>> allWarnings = new ArrayList<>();
		List<Overrides> allOverrides = new ArrayList<>();
		for (Library library : libraries) {
			ApiModel model = library.read();
			List<String> warnings = new ArrayList<>();
			for (ApiModel.Unparsed unparsed : model.unparsed) {
				warnings.add(unparsed.file() + ":" + unparsed.line() + ": not understood (" + unparsed.pattern() + "): "
						+ unparsed.text());
			}
			Path overridesFile = library.overrides();
			Overrides overrides = overridesFile == null ? Overrides.none() : Overrides.library(overridesFile);
			all.add(new JavaBindings(model, Map.of(), overrides,
					BindingsTarget.library(library.name(), library.packageName()), language, warnings));
			allWarnings.add(warnings);
			allOverrides.add(overrides);
		}
		for (JavaBindings bindings : all) {
			bindings.foreign.add(rs3);
			all.stream().filter(other -> other != bindings).forEach(bindings.foreign::add);
		}
		Path classes = work.resolve("classes");
		Files.createDirectories(classes);
		List<Path> sourceFiles = new ArrayList<>();
		List<List<String>> allNotes = new ArrayList<>();
		for (int index = 0; index < libraries.size(); index++) {
			Library library = libraries.get(index);
			sourceFiles.addAll(write(all.get(index).sources(), work.resolve("sources").resolve(library.name())));
			List<String> notes = new ArrayList<>();
			overrideReports(allOverrides.get(index), "these types", allWarnings.get(index), notes);
			allNotes.add(notes);
		}
		compile(sourceFiles, classes, List.of(rs3Jar));

		List<LibraryResult> results = new ArrayList<>();
		for (int index = 0; index < libraries.size(); index++) {
			Library library = libraries.get(index);
			Path classesJar = output.resolve(library.name() + "-bindings.jar");
			Path sourcesJar = output.resolve(library.name() + "-bindings-sources.jar");
			Jars.write(classes, classesJar, library.packageName().replace('.', '/') + "/");
			Jars.write(work.resolve("sources").resolve(library.name()), sourcesJar, "");
			List<String> warnings = allWarnings.get(index).stream()
					.map(warning -> library.name() + ": " + warning)
					.toList();
			results.add(new LibraryResult(library.name(), classesJar, sourcesJar, warnings, allNotes.get(index)));
		}
		return results;
	}

	/**
	 * The overrides' lines that match nothing, as warnings, and those the types have caught up with, as notes.
	 *
	 * @param types what the types are, as the messages name them: {@code these stubs}
	 */
	private static void overrideReports(Overrides overrides, String types, List<String> warnings, List<String> notes) {
		for (Overrides.Entry unused : overrides.unused()) {
			warnings.add(overrides.source() + ":" + unused.line() + ": " + unused.fact() + " " + unused.target()
					+ " matches nothing in " + types + ".");
		}
		for (Overrides.Entry caughtUp : overrides.noLongerNeeded()) {
			String reason = caughtUp.fact().equals("skip") ? "no longer have it." : "now say the same.";
			notes.add(overrides.source() + ":" + caughtUp.line() + ": " + caughtUp.fact() + " " + caughtUp.target()
					+ " is no longer needed: " + types.replace("these ", "the ") + " " + reason);
		}
	}

	/** Writes the sources under {@code root}, each at its path, and returns their files. */
	private static List<Path> write(Map<String, String> sources, Path root) throws IOException {
		List<Path> files = new ArrayList<>();
		for (Map.Entry<String, String> source : sources.entrySet()) {
			Path file = root.resolve(source.getKey());
			Files.createDirectories(file.getParent());
			Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
			files.add(file);
		}
		return files;
	}

	/** Compiles the generated sources into {@code classes}, against the classpath, the annotations and JSpecify. */
	private static void compile(List<Path> sources, Path classes, List<Path> classpath) throws IOException {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			throw new IOException(
					"The bindings are compiled with javac, but this Java runtime has none; run it on a JDK.");
		}
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		List<Path> path = new ArrayList<>(classpath);
		path.add(location(LuaGlobal.class));
		path.add(location(NullMarked.class));
		String joined = path.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
		try (StandardJavaFileManager files =
				compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
			List<String> options = List.of("--release", "25", "-proc:none", "-implicit:none", "-nowarn", "-Xlint:none",
					"-encoding", "UTF-8", "-d", classes.toString(), "-classpath", joined);
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
		CodeSource source = type.getProtectionDomain().getCodeSource();
		if (source == null || source.getLocation() == null) {
			throw new IOException("Cannot find where " + type.getName() + " was loaded from.");
		}
		try {
			return Path.of(source.getLocation().toURI());
		} catch (URISyntaxException e) {
			throw new IOException("Cannot find where " + type.getName() + " was loaded from.", e);
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
