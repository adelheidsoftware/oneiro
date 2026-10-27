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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.adelheid.oneiro.api.binding.LuaGlobal;
import org.adelheid.oneiro.plugin.ClientFacts;
import org.adelheid.oneiro.plugin.LuaLibraries;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Generates the RS3 bindings from a checkout of rs3-api-types, and the bindings of the Lua libraries a plugin uses
 * and of its own Lua from their LuaCATS types: reads the types, applies the overrides, writes the Java sources
 * and, for the RS3 API, the constant chunks, compiles the sources, and packs two jars for each, the classes that
 * plugins compile against and the sources that IDEs show as documentation. It runs on the developer's machine, so
 * nothing derived from the stubs or a library is ever published.
 */
public final class BindingsGenerator {

	/** The jar of classes plugins compile against. */
	public static final String CLASSES_JAR = "rs3-bindings.jar";
	/** The jar of the classes' sources, for an IDE's documentation. */
	public static final String SOURCES_JAR = "rs3-bindings-sources.jar";

	/** The package below which each library a plugin vendors has a package of its own, named after it. */
	static final String LIBRARIES_PACKAGE = "org.adelheid.oneiro.lib";

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
	 * @param name the library's name, which its jars are named after, such as {@code widgets}
	 * @param packageName the package its bindings go in
	 * @param folder the folder of its LuaCATS files: its own code, or definition files written for it
	 * @param modulePrefix what comes before a file's path in the path {@code require} loads it by: empty for a library
	 *     whose folder goes to the plugin's root
	 * @param overrides the plugin's overrides file for the library; null when it keeps none
	 * @param use what the library is to the plugin
	 */
	public record Library(String name, String packageName, Path folder, String modulePrefix,
			@Nullable Path overrides, LuaLibraries.Use use) {

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
	 * What generating the bindings of the Lua a plugin uses produced: those of each library it vendors, the main
	 * source set's and then the dev source set's, and those of its own Lua.
	 *
	 * @param main what the main source set's own Lua gave
	 * @param dev what the dev source set's own Lua gave
	 */
	public record LuaResult(List<LibraryResult> libraries, LibraryResult main, LibraryResult dev) {

		public LuaResult {
			libraries = List.copyOf(libraries);
		}

		/** Every warning, the libraries' first. */
		public List<String> warnings() {
			List<String> warnings = new ArrayList<>();
			libraries.forEach(library -> warnings.addAll(library.warnings()));
			warnings.addAll(main.warnings());
			warnings.addAll(dev.warnings());
			return warnings;
		}
	}

	/** One set of bindings compiled together: what each gave, the bindings, and the folder of their classes. */
	private record Generated(List<LibraryResult> results, List<JavaBindings> bindings, List<Path> classes) {
	}

	/**
	 * Generates the bindings of the Lua a plugin uses into {@code output}, replacing what an earlier run left there:
	 * for each library it vendors, {@code libraries/main/<name>-bindings.jar}, or {@code libraries/dev/} for one the
	 * dev source set alone uses, in the package {@code org.adelheid.oneiro.lib.<name>}; for its own Lua, from the
	 * LuaCATS files of the main source set, which ship under {@code src/}, {@code main-bindings.jar}, and from those of
	 * the dev source set, which go under {@code dev/}, {@code dev-bindings.jar}; each with a sources jar. A file of the
	 * plugin's own Lua is a class of the package of its folder below the root package, so that
	 * {@code core/levels.lua} is {@code <root>.core.Levels}. Names Java cannot give those classes are refused before
	 * anything compiles, with the file each comes from.
	 *
	 * <p>A type the Lua names but does not declare resolves to the RS3 bindings' class, then to a library's; the dev
	 * source set's own Lua names the main set's types before those. Each set compiles against the sets it may name: the
	 * main libraries against the RS3 jar, the dev libraries against those, the main set's own Lua against the RS3 jar
	 * and the main libraries, and the dev set's against everything.
	 *
	 * @param rs3Jar the classes of the RS3 bindings the stubs gave
	 * @param codePackages the packages of the plugin's Java or Kotlin code, whose names its own Lua's classes cannot
	 *     take
	 * @param main the folder of the main source set's LuaCATS files, its Lua or definition files for it; a folder that
	 *     does not exist has none
	 * @param dev the folder of the dev source set's, or null for none
	 */
	public static LuaResult generateLua(Path stubsRoot, Path rs3Jar, Path output, Language language, String rootPackage,
			Set<String> codePackages, List<Library> mainLibraries, List<Library> devLibraries, Path main,
			@Nullable Path dev) throws IOException {
		ApiModel mainModel = read(main, "src/");
		ApiModel devModel = dev == null ? new ApiModel() : read(dev, "dev/");
		Path work = output.resolve("work");
		Path libraries = output.resolve("libraries");
		delete(work);
		delete(libraries);
		List<JavaBindings> rs3 = new ArrayList<>();
		if (!mainLibraries.isEmpty() || !devLibraries.isEmpty() || declares(mainModel) || declares(devModel)) {
			// The RS3 API's types, which the Lua may name; Lua that declares nothing names none
			rs3.add(new JavaBindings(Stubs.readTypes(stubsRoot).model(), Map.of(), Overrides.bundled(),
					BindingsTarget.rs3(), language, new ArrayList<>()));
		}
		Generated mainSet = generateLibraries(rs3, List.of(rs3Jar), mainLibraries, language, libraries.resolve("main"),
				work.resolve("libraries-main"));
		Generated devSet = generateLibraries(concat(rs3, mainSet.bindings()),
				concat(List.of(rs3Jar), mainSet.classes()), devLibraries, language, libraries.resolve("dev"),
				work.resolve("libraries-dev"));
		JavaBindings mainLua = ownBindings("main", mainModel, main, rootPackage, language,
				concat(rs3, mainSet.bindings()));
		JavaBindings devLua = ownBindings("dev", devModel, dev, rootPackage, language,
				concat(concat(List.of(mainLua), rs3), concat(mainSet.bindings(), devSet.bindings())));
		Map<JavaBindings, Path> ownSets = new LinkedHashMap<>(Map.of(mainLua, main));
		if (dev != null) {
			ownSets.put(devLua, dev);
		}
		checkNames(rootPackage, codePackages, ownSets);
		Generated ownMain = writeOwn(mainLua, concat(List.of(rs3Jar), mainSet.classes()), output, work);
		Generated ownDev = writeOwn(devLua,
				concat(concat(List.of(rs3Jar), mainSet.classes()), concat(devSet.classes(), ownMain.classes())), output,
				work);
		delete(work);
		return new LuaResult(concat(mainSet.results(), devSet.results()), ownMain.results().getFirst(),
				ownDev.results().getFirst());
	}

	/**
	 * {@link #generateLua} for the Gradle plugin's worker, called reflectively with plain strings, returning every
	 * warning. The libraries are those {@code oneiroLibraries} staged in the folder {@code libraries}
	 * ({@link LuaLibraries}), each from its LuaCATS files there, a vendored library's own Lua or the types the build
	 * names for a library plugin, with the overrides file the build keeps for it.
	 *
	 * @param codePackages the file that holds the packages of the plugin's code, one a line
	 */
	public static List<String> generateLuaForBuild(String stubsRoot, String rs3Jar, String output, String language,
			String rootPackage, String codePackages, String libraries, String main, String dev) throws IOException {
		Path staged = Path.of(libraries);
		List<Library> mainLibraries = new ArrayList<>();
		List<Library> devLibraries = new ArrayList<>();
		for (LuaLibraries.Library library : LuaLibraries.read(staged.resolve(LuaLibraries.FILE)).libraries()) {
			Path overrides = LuaLibraries.overridesFile(staged, library.name());
			// A vendored library's files go to the plugin's root, so Lua loads them by their paths from there; a
			// library plugin's types are laid out as its own plugin folder is, main.lua at the top
			(library.development() ? devLibraries : mainLibraries).add(new Library(library.name(),
					LIBRARIES_PACKAGE + "." + library.name(), LuaLibraries.typesFolder(staged, library.name()), "",
					Files.isRegularFile(overrides) ? overrides : null, library.use()));
		}
		Set<String> packages = new HashSet<>(Files.readAllLines(Path.of(codePackages), StandardCharsets.UTF_8));
		packages.remove("");
		return generateLua(Path.of(stubsRoot), Path.of(rs3Jar), Path.of(output), Language.valueOf(language),
				rootPackage, packages, mainLibraries, devLibraries, Path.of(main), Path.of(dev)).warnings();
	}

	/** Whether a model declares any type, which needs the RS3 API's to name. */
	private static boolean declares(ApiModel model) {
		return !model.classes.isEmpty() || !model.enums.isEmpty();
	}

	/**
	 * The bindings of a source set's own Lua.
	 *
	 * @param folder the folder of its LuaCATS files, which the warnings name the files by; null for none
	 * @param named the bindings its types may name, in the order a name resolves in
	 */
	private static JavaBindings ownBindings(String sourceSet, ApiModel model, @Nullable Path folder,
			String rootPackage, Language language, List<JavaBindings> named) {
		List<String> warnings = new ArrayList<>();
		if (folder != null) {
			for (ApiModel.Unparsed unparsed : model.unparsed) {
				warnings.add(folder.resolve(unparsed.file()) + ":" + unparsed.line() + ": not understood ("
						+ unparsed.pattern() + "): " + unparsed.text());
			}
		}
		JavaBindings bindings = new JavaBindings(model, Map.of(), Overrides.none(),
				BindingsTarget.plugin(sourceSet, rootPackage), language, warnings);
		bindings.foreign.addAll(named);
		return bindings;
	}

	/**
	 * Refuses, all at once, the names Java cannot give the classes of the plugin's own Lua: a folder named as no
	 * package can be, such as {@code ui-parts}; a class with the name of a package, as the class {@code core} of
	 * {@code core.lua} beside the folder {@code core/} or beside the plugin's code in {@code <root>.core}, since Java
	 * has no class and package of one name; and a class both source sets have, as they share the root package.
	 *
	 * @param codePackages the packages of the plugin's Java or Kotlin code
	 * @param sets the bindings of each source set's own Lua, the main set's first, with the folder of its files
	 */
	private static void checkNames(String rootPackage, Set<String> codePackages, Map<JavaBindings, Path> sets)
			throws IOException {
		List<String> problems = new ArrayList<>();
		// The packages the folders make, each with the first folder that makes it; core/deep/ makes core one too
		Map<String, Path> folderPackages = new HashMap<>();
		for (Map.Entry<JavaBindings, Path> set : sets.entrySet()) {
			for (JavaBindings.Node top : set.getKey().tops().values()) {
				List<String> parts = List.of(top.file.split("/"));
				for (int depth = 1; depth < parts.size(); depth++) {
					String folder = String.join("/", parts.subList(0, depth));
					String name = parts.get(depth - 1);
					String packageName = rootPackage + "." + folder.replace('/', '.');
					Path path = set.getValue().resolve(folder);
					if (folderPackages.putIfAbsent(packageName, path) == null
							&& (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name))) {
						problems.add(path + ": the classes of its Lua would be in the package " + packageName
								+ ", which Java cannot name; rename the folder, such as to "
								+ JavaText.javaIdentifier(name) + ".");
					}
				}
			}
		}
		Set<String> codeMade = new HashSet<>();
		for (String packageName : codePackages) {
			for (String name = packageName; name.startsWith(rootPackage + ".");
					name = name.substring(0, name.lastIndexOf('.'))) {
				codeMade.add(name);
			}
		}
		Map<String, Path> classFiles = new HashMap<>();
		for (Map.Entry<JavaBindings, Path> set : sets.entrySet()) {
			for (Map.Entry<String, JavaBindings.Node> top : set.getKey().tops().entrySet()) {
				String name = top.getKey();
				Path file = set.getValue().resolve(top.getValue().file);
				String described = file + ": the class " + top.getValue().luaSegment + " would be " + name;
				Path folder = folderPackages.get(name);
				if (folder != null) {
					problems.add(described + ", but the folder " + folder + " makes that a package, and Java cannot"
							+ " have a class and a package of one name; rename the class or the folder.");
				} else if (codeMade.contains(name)) {
					problems.add(described + ", but the plugin's code makes that a package, and Java cannot have a"
							+ " class and a package of one name; rename the class or that package.");
				}
				Path other = classFiles.putIfAbsent(name, file);
				if (other != null) {
					problems.add(described + ", as " + other + " already is; rename one of them.");
				}
			}
		}
		if (!problems.isEmpty()) {
			throw new IOException("The plugin's own Lua has names Java cannot give its bindings:\n  - "
					+ String.join("\n  - ", problems));
		}
	}

	/**
	 * Writes the bindings of a source set's own Lua, {@code <sourceSet>-bindings.jar} and its sources jar in
	 * {@code output}.
	 *
	 * @param classpath the classes of the bindings its types may name
	 */
	private static Generated writeOwn(JavaBindings bindings, List<Path> classpath, Path output, Path work)
			throws IOException {
		String sourceSet = bindings.target.library();
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
		return new Generated(
				List.of(new LibraryResult(sourceSet, classesJar, sourcesJar, bindings.warnings, List.of())),
				List.of(bindings), List.of(classes));
	}

	private static <T> List<T> concat(List<? extends T> first, List<? extends T> second) {
		List<T> both = new ArrayList<>(first);
		both.addAll(second);
		return both;
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
		new ClientFacts(bindings.permissions, bindings.devOnly, bindings.globals()).write(clientFacts);
		List<LibraryResult> libraryResults = generateLibraries(List.of(bindings), List.of(classesJar), libraries,
				language, output, work.resolve("libraries")).results();
		delete(work);
		return new Result(classesJar, sourcesJar, clientFacts, warnings, notes, libraryResults);
	}

	/**
	 * The bindings of a set of libraries. A type a library names but does not declare resolves to a class of the
	 * bindings it may name, the RS3 API's first, or else to another library's of the set, so the set's sources compile
	 * together, against those bindings' classes; each library's classes and sources then go into jars of their own, in
	 * {@code output}: {@code <name>-bindings.jar} and {@code <name>-bindings-sources.jar}.
	 *
	 * @param named the bindings the libraries may name, in the order a name resolves in
	 * @param classpath their classes
	 */
	private static Generated generateLibraries(List<JavaBindings> named, List<Path> classpath, List<Library> libraries,
			Language language, Path output, Path work) throws IOException {
		if (libraries.isEmpty()) {
			return new Generated(List.of(), List.of(), List.of());
		}
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
			BindingsTarget target = library.use() == LuaLibraries.Use.VENDORED
					? BindingsTarget.library(library.name(), library.packageName())
					: BindingsTarget.dependency(library.name(), library.packageName(),
							library.use() == LuaLibraries.Use.OPTIONAL);
			all.add(new JavaBindings(model, Map.of(), overrides, target, language, warnings));
			allWarnings.add(warnings);
			allOverrides.add(overrides);
		}
		for (JavaBindings bindings : all) {
			bindings.foreign.addAll(named);
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
		compile(sourceFiles, classes, classpath);

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
		return new Generated(results, all, List.of(classes));
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
