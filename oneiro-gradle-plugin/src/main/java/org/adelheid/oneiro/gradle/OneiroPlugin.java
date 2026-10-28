package org.adelheid.oneiro.gradle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import org.adelheid.oneiro.gradle.remote.LatestStubsCommit;
import org.adelheid.oneiro.gradle.tasks.CheckPlugin;
import org.adelheid.oneiro.gradle.tasks.ExplainError;
import org.adelheid.oneiro.gradle.tasks.GenerateBindings;
import org.adelheid.oneiro.gradle.tasks.GenerateLuaBindings;
import org.adelheid.oneiro.gradle.tasks.GenerateSources;
import org.adelheid.oneiro.gradle.tasks.LinkDevPlugin;
import org.adelheid.oneiro.gradle.tasks.StageLibraries;
import org.adelheid.oneiro.gradle.tasks.StubsTask;
import org.adelheid.oneiro.gradle.tasks.TestLua;
import org.adelheid.oneiro.gradle.tasks.WriteDevMain;
import org.adelheid.oneiro.gradle.tasks.WriteManifest;
import org.adelheid.oneiro.plugin.ClientFacts;
import org.adelheid.oneiro.plugin.LuaLibraries;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;

/**
 * The Gradle plugin {@code org.adelheid.oneiro}, which makes the project a plugin for the RS3 client. It applies the
 * {@code java} plugin and adds:
 *
 * <ul>
 *   <li>{@code oneiroBindings}, the RS3 bindings on the compile classpath;
 *   <li>{@code oneiroLibraries}, the Lua libraries the plugin vendors, staged as they ship, and the types of the
 *       library plugins it depends on;
 *   <li>{@code oneiroLuaBindings}, the bindings of those libraries and of the plugin's own Lua, from
 *       {@code src/main/lua} and {@code src/dev/lua}, on the compile classpaths of the main and dev source sets;
 *   <li>{@code oneiroSources}, the typed {@code Resources} and {@code Urls} classes;
 *   <li>Oneiro as a javac plugin in {@code compileJava}, which writes the Lua of a plugin written in Java, or
 *       {@code oneiroKotlin}, which writes the Lua of one written in Kotlin, with the Kotlin JVM plugin applied;
 *   <li>{@code pluginManifest}, the {@code plugin.json};
 *   <li>{@code assemblePlugin}, {@code checkPlugin} and {@code packagePlugin}: the folder that ships, with the plugin's
 *       own Lua under {@code src/}, the helpers Oneiro writes under {@code utils/} and its libraries in its root, its
 *       checks and its zip;
 *   <li>the {@code dev} source set and {@code assembleDevPlugin}, the development folder, with the dev source set's own
 *       Lua under {@code dev/}, its libraries in its root, and a manifest of its own, which names a dependency's folder
 *       on this machine where it has one;
 *   <li>{@code linkDevPlugin}, the link into the client's {@code dev_plugins} folder;
 *   <li>{@code testLua}, the plugin's Lua tests.
 * </ul>
 */
public final class OneiroPlugin implements Plugin<Project> {

	private static final String GROUP = "oneiro";
	/** The JetBrains repositories of the Kotlin Analysis API and the IntelliJ platform, which Maven Central lacks. */
	private static final String INTELLIJ_DEPENDENCIES = "https://cache-redirector.jetbrains.com/intellij-dependencies";
	private static final String INTELLIJ_RELEASES =
			"https://cache-redirector.jetbrains.com/intellij-repository/releases";
	private static final String WINDOWS_DEV_PLUGINS = "C:\\ProgramData\\Jagex\\RuneScape-BETA\\dev_plugins";

	@Override
	public void apply(Project project) {
		project.getPluginManager().apply(JavaPlugin.class);
		OneiroExtension extension = project.getExtensions().create("oneiro", OneiroExtension.class);
		conventions(project, extension);
		Properties versions = versions();
		String version = versions.getProperty("version");
		ProjectLayout layout = project.getLayout();
		TaskContainer tasks = project.getTasks();
		Provider<String> pluginVersion = project.provider(() -> String.valueOf(project.getVersion()));

		JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
		// Oneiro reads plugins at the Java 25 language level, and its javac plugin runs on JDK 25
		java.getToolchain().getLanguageVersion().convention(JavaLanguageVersion.of(25));
		Provider<JavaLauncher> launcher = project.getExtensions().getByType(JavaToolchainService.class)
				.launcherFor(spec -> spec.getLanguageVersion().set(JavaLanguageVersion.of(25)));

		Configuration tool = project.getConfigurations().create("oneiroTool", configuration -> {
			configuration.setDescription("Oneiro's bindings generator and Lua runner, which run in worker processes.");
			configuration.setCanBeConsumed(false);
		});
		Configuration kotlinTool = project.getConfigurations().create("oneiroKotlinTool", configuration -> {
			configuration.setDescription("Oneiro's Kotlin front end, which runs in a worker process of its own.");
			configuration.setCanBeConsumed(false);
		});
		DependencyHandler dependencies = project.getDependencies();
		dependencies.add(tool.getName(), "org.adelheid:oneiro-bindings-gen:" + version);
		dependencies.add(tool.getName(), "org.adelheid:oneiro-lua-runner:" + version);
		dependencies.add(kotlinTool.getName(), "org.adelheid:oneiro-frontend-kotlin:" + version);
		// A plugin is written in Kotlin when the Kotlin JVM plugin is applied, before or after this one
		Property<Boolean> kotlin = project.getObjects().property(Boolean.class).convention(false);
		project.getPluginManager().withPlugin("org.jetbrains.kotlin.jvm", applied -> {
			kotlin.set(true);
			// Only to a project that declares repositories of its own: one that declared none would then ignore those
			// its settings declare, where the JetBrains repositories go instead
			project.afterEvaluate(evaluated -> {
				if (!evaluated.getRepositories().isEmpty()) {
					jetBrainsRepositories(evaluated.getRepositories());
				}
			});
		});
		dependencies.add(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, "org.adelheid:oneiro-api:" + version);
		dependencies.add(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME,
				"org.jspecify:jspecify:" + versions.getProperty("jspecify"));
		dependencies.add(JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME,
				"org.adelheid:oneiro-frontend-java:" + version);

		Action<StubsTask> stubs = stubs(project, extension, tool, launcher, kotlin);
		Provider<Directory> bindingsFolder = layout.getBuildDirectory().dir("oneiro/bindings");
		TaskProvider<GenerateBindings> bindings = tasks.register("oneiroBindings", GenerateBindings.class, task -> {
			task.setGroup(GROUP);
			task.setDescription("Generates the RS3 bindings from the stubs the build names.");
			stubs.execute(task);
			task.getClassesJar().set(bindingsFolder.map(folder -> folder.file("rs3-bindings.jar")));
			task.getSourcesJar().set(bindingsFolder.map(folder -> folder.file("rs3-bindings-sources.jar")));
			task.getClientFacts().set(bindingsFolder.map(folder -> folder.file(ClientFacts.FILE)));
		});
		dependencies.add(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME,
				project.files(bindings.flatMap(GenerateBindings::getClassesJar)));

		SourceSet main = java.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
		TaskProvider<GenerateSources> sources = tasks.register("oneiroSources", GenerateSources.class, task -> {
			task.setGroup(GROUP);
			task.setDescription("Writes the typed Resources and Urls classes of the plugin's files and base URLs.");
			Directory resources = layout.getProjectDirectory().dir("src/main/resources");
			task.getResourceFiles().from(resources.getAsFileTree());
			task.getResourcesFolder().set(resources);
			task.getUrls().set(extension.getPlugin().getUrls());
			task.getRootPackage().set(extension.getRootPackage());
			task.getPluginSources().from(sourceFiles(layout, "main", "java"), sourceFiles(layout, "main", "kt"),
					sourceFiles(layout, "dev", "java"), sourceFiles(layout, "dev", "kt"));
			task.getOutputDirectory().set(layout.getBuildDirectory().dir("generated/sources/oneiro/java/main"));
			task.getRootPackageFile().set(layout.getBuildDirectory().file("oneiro/root-package.txt"));
			task.getCodePackagesFile().set(layout.getBuildDirectory().file("oneiro/code-packages.txt"));
		});
		main.getJava().srcDir(sources.flatMap(GenerateSources::getOutputDirectory));

		TaskProvider<StageLibraries> libraries = tasks.register("oneiroLibraries", StageLibraries.class, task -> {
			task.setGroup(GROUP);
			task.setDescription("Stages the Lua libraries the plugin vendors as they ship, with their licences, and the"
					+ " types of the library plugins it depends on.");
			for (OneiroExtension.VendoredLibrary library : extension.getLibraries().declared()) {
				task.getLibraries().add(library);
				localFiles(task, library);
				if (library.getLocal().isPresent()) {
					// A library's root's files, among them its licence
					task.getLocalFiles().from(library.getLocal().get().getAsFileTree()
							.matching(pattern -> pattern.include("*")));
				}
			}
			for (OneiroExtension.PluginDependency dependency : extension.getPlugin().declaredDependencies()) {
				OneiroExtension.LibraryTypes types = dependency.getTypes();
				if (types != null) {
					task.getTypes().add(types);
					localFiles(task, types);
				}
			}
			task.getCache().set(repositoryCache(project));
			task.getOutputFolder().set(layout.getBuildDirectory().dir("oneiro/libraries"));
		});
		Provider<Directory> stagedLibraries = libraries.flatMap(StageLibraries::getOutputFolder);

		Directory mainLuaSources = layout.getProjectDirectory().dir("src/main/lua");
		Directory devLuaSources = layout.getProjectDirectory().dir("src/dev/lua");
		Provider<Directory> luaBindingsFolder = layout.getBuildDirectory().dir("oneiro/lua-bindings");
		TaskProvider<GenerateLuaBindings> luaBindings = tasks.register("oneiroLuaBindings", GenerateLuaBindings.class,
				task -> {
					task.setGroup(GROUP);
					task.setDescription("Generates the bindings of the plugin's own Lua from its annotations.");
					stubs.execute(task);
					task.getRs3Jar().set(bindings.flatMap(GenerateBindings::getClassesJar));
					task.getRootPackageFile().set(sources.flatMap(GenerateSources::getRootPackageFile));
					task.getCodePackagesFile().set(sources.flatMap(GenerateSources::getCodePackagesFile));
					task.getLibraries().set(stagedLibraries);
					task.getLibrariesFolder().set(luaBindingsFolder.map(folder -> folder.dir("libraries")));
					task.getLuaFiles().from(mainLuaSources.getAsFileTree(), devLuaSources.getAsFileTree());
					task.getMainLua().set(mainLuaSources);
					task.getDevLua().set(devLuaSources);
					task.getMainClassesJar().set(luaBindingsFolder.map(folder -> folder.file("main-bindings.jar")));
					task.getMainSourcesJar()
							.set(luaBindingsFolder.map(folder -> folder.file("main-bindings-sources.jar")));
					task.getDevClassesJar().set(luaBindingsFolder.map(folder -> folder.file("dev-bindings.jar")));
					task.getDevSourcesJar()
							.set(luaBindingsFolder.map(folder -> folder.file("dev-bindings-sources.jar")));
				});
		dependencies.add(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME,
				project.files(luaBindings.flatMap(GenerateLuaBindings::getMainClassesJar)));
		dependencies.add(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, libraryJars(project, luaBindings, "main"));

		Provider<Directory> mainLua = layout.getBuildDirectory().dir("oneiro/lua/main");
		Compilers compilers = new Compilers(project, extension, kotlin, kotlinTool, launcher);
		Provider<Directory> mainLuaBuilt = compilers.lua(JavaPlugin.COMPILE_JAVA_TASK_NAME, "oneiroKotlin", "main",
				false, mainLua, main, sources.flatMap(GenerateSources::getOutputDirectory), null);

		Action<WriteManifest> manifestInputs = task -> {
			OneiroExtension.PluginManifest plugin = extension.getPlugin();
			task.getPluginId().set(plugin.getId());
			task.getPluginName().set(plugin.getName());
			task.getVersion().set(pluginVersion);
			task.getContactEmail().set(plugin.getContactEmail());
			task.getDependencies().addAll(plugin.declaredDependencies());
			task.getRequiredPermissions().set(plugin.getRequiredPermissions());
			task.getOptionalPermissions().set(plugin.getOptionalPermissions());
			task.getUrls().set(plugin.getUrls());
			task.getSettingsIcon().set(plugin.getSettingsIcon());
			task.getRibbonIcon().set(plugin.getRibbonIcon());
			task.getClientFacts().set(bindings.flatMap(GenerateBindings::getClientFacts));
		};
		TaskProvider<WriteManifest> manifest = tasks.register("pluginManifest", WriteManifest.class, task -> {
			task.setGroup(GROUP);
			task.setDescription("Writes plugin.json from the oneiro { plugin { } } block.");
			manifestInputs.execute(task);
			task.getDevelopment().set(false);
			task.getManifestFile().set(layout.getBuildDirectory().file("oneiro/manifest/plugin.json"));
		});
		TaskProvider<WriteManifest> devManifest = tasks.register("oneiroDevManifest", WriteManifest.class, task -> {
			task.setDescription("Writes the development folder's plugin.json, which names each dependency's folder on"
					+ " this machine where it has one, and requires the dev source set's dependencies too.");
			manifestInputs.execute(task);
			task.getDevelopment().set(true);
			task.getManifestFile().set(layout.getBuildDirectory().file("oneiro/dev-manifest/plugin.json"));
		});

		Provider<Directory> pluginFolder = layout.getBuildDirectory().dir("oneiro/plugin");
		TaskProvider<Sync> assemblePlugin = tasks.register("assemblePlugin", Sync.class, sync -> {
			sync.setGroup(GROUP);
			sync.setDescription(
					"Assembles the plugin as it ships: main.lua, plugin.json, src/, utils/ and resources/.");
			sync.from(mainLuaBuilt);
			sync.from(mainLuaSources, spec -> spec.into("src"));
			sync.from(stagedLibraries.map(folder -> folder.dir("main")));
			sync.from(manifest.flatMap(WriteManifest::getManifestFile));
			sync.from(layout.getProjectDirectory().dir("src/main/resources"), spec -> spec.into("resources"));
			sync.into(pluginFolder);
		});

		TaskProvider<CheckPlugin> checkPlugin = tasks.register("checkPlugin", CheckPlugin.class, task -> {
			task.setGroup(GROUP);
			task.setDescription("Checks that the plugin folder holds only what may ship.");
			task.getPluginFolder().set(layout.dir(assemblePlugin.map(Sync::getDestinationDir)));
			task.getClientFacts().set(bindings.flatMap(GenerateBindings::getClientFacts));
			task.getPermissions().addAll(extension.getPlugin().getRequiredPermissions());
			task.getPermissions().addAll(extension.getPlugin().getOptionalPermissions());
			task.getLibraries().set(stagedLibraries.map(folder -> folder.file(LuaLibraries.FILE)));
			task.getUnpublishedDependencies()
					.set(project.provider(() -> extension.getPlugin().unpublishedDependencies()));
			task.getReport().set(layout.getBuildDirectory().file("oneiro/check-plugin.txt"));
		});
		tasks.named("check", check -> check.dependsOn(checkPlugin));

		TaskProvider<Zip> packagePlugin = tasks.register("packagePlugin", Zip.class, zip -> {
			zip.setGroup(GROUP);
			zip.setDescription("Zips the plugin folder for Mod.io, the same bytes for the same sources.");
			zip.dependsOn(checkPlugin);
			zip.from(assemblePlugin);
			zip.getArchiveFileName()
					.set(extension.getPlugin().getId().zip(pluginVersion, (id, number) -> id + "-" + number + ".zip"));
			zip.getDestinationDirectory().set(layout.getBuildDirectory().dir("distributions"));
			zip.setPreserveFileTimestamps(false);
			zip.setReproducibleFileOrder(true);
			zip.dirPermissions(permissions -> permissions.unix("rwxr-xr-x"));
			zip.filePermissions(permissions -> permissions.unix("rw-r--r--"));
		});
		tasks.named("assemble", assemble -> assemble.dependsOn(packagePlugin));

		TaskProvider<Sync> assembleDevPlugin = devSourceSet(project, compilers, java, main, assemblePlugin,
				luaBindings, stagedLibraries, devManifest);
		boolean hasDev = layout.getProjectDirectory().dir("src/dev/java").getAsFile().isDirectory()
				|| layout.getProjectDirectory().dir("src/dev/kotlin").getAsFile().isDirectory()
				|| devLuaSources.getAsFile().isDirectory();
		// The development folder where it differs from the shipped one, by its dev source set or its manifest, which
		// the build's dependencies decide once it is evaluated
		Provider<TaskProvider<Sync>> linked = project.provider(
				() -> hasDev || extension.getPlugin().developsDependencies() ? assembleDevPlugin : assemblePlugin);
		// Each build writes the folder linkDevPlugin links, so that the client loads it after prl; the zip is still
		// made from the shipped folder alone
		tasks.named("assemble", assemble -> assemble.dependsOn(linked));
		tasks.register("linkDevPlugin", LinkDevPlugin.class, task -> {
			task.setGroup(GROUP);
			task.setDescription("Links the plugin into the client's dev_plugins folder under its id.");
			task.dependsOn(linked);
			task.getDevPlugins().set(extension.getDevPlugins());
			task.getPluginId().set(extension.getPlugin().getId());
			task.getTarget().set(layout.dir(linked.flatMap(folder -> folder.map(Sync::getDestinationDir))));
		});

		TaskProvider<TestLua> testLua = tasks.register("testLua", TestLua.class, task -> {
			task.setGroup(GROUP);
			task.setDescription("Runs the Lua tests under src/test/lua against the plugin as it ships.");
			task.getPluginFolder().set(layout.dir(assemblePlugin.map(Sync::getDestinationDir)));
			Directory tests = layout.getProjectDirectory().dir("src/test/lua");
			task.getTestFiles().from(tests.getAsFileTree());
			task.getTestsFolder().set(tests);
			task.getLuaExecutable().set(extension.getLua().getExecutable());
			task.getToolClasspath().from(tool);
			task.getJavaLauncher().set(launcher);
			task.getWorkFolder().set(layout.getBuildDirectory().dir("oneiro/test-lua"));
			task.getReport().set(layout.getBuildDirectory().file("oneiro/test-lua.txt"));
		});
		tasks.named("check", check -> check.dependsOn(testLua));

		SourceSet dev = java.getSourceSets().getByName("dev");
		tasks.register("explainError", ExplainError.class, task -> {
			task.setGroup(GROUP);
			task.setDescription("Shows the plugin's errors in text copied from the client's console as source lines.");
			task.getPluginName().set(extension.getPlugin().getName());
			task.getLineMaps().from(lineMap(layout, "main"), lineMap(layout, "dev"));
			task.getSourceFolders().from(main.getJava().getSourceDirectories(), dev.getJava().getSourceDirectories(),
					layout.getProjectDirectory().dir("src/main/kotlin"),
					layout.getProjectDirectory().dir("src/dev/kotlin"));
			task.getMainLua().set(mainLuaSources);
			task.getDevLua().set(devLuaSources);
			task.getProjectFolder().set(layout.getProjectDirectory());
		});
	}

	private static void conventions(Project project, OneiroExtension extension) {
		extension.getRootPackage().convention("");
		extension.getWarningsAsErrors().convention(false);
		OneiroExtension.PluginManifest plugin = extension.getPlugin();
		plugin.getId().convention(project.getName().toLowerCase(Locale.ROOT));
		plugin.getName().convention("");
		plugin.getContactEmail().convention("");
		plugin.getSettingsIcon().convention(false);
		plugin.getRibbonIcon().convention(false);
		File windows = new File(WINDOWS_DEV_PLUGINS);
		if (windows.isDirectory()) {
			extension.getDevPlugins()
					.convention(project.getLayout().getProjectDirectory().dir(windows.getAbsolutePath()));
		}
	}

	/**
	 * What {@code oneiroBindings} and {@code oneiroLuaBindings} share: the stubs the build names, a local checkout or a
	 * remote at a resolved commit, the plugin's language, and the generator's worker.
	 */
	private static Action<StubsTask> stubs(Project project, OneiroExtension extension, Configuration tool,
			Provider<JavaLauncher> launcher, Provider<Boolean> kotlin) {
		File cache = repositoryCache(project);
		boolean refresh = project.getGradle().getStartParameter().getTaskNames().stream()
				.anyMatch(name -> name.endsWith("oneiroBindings"));
		boolean offline = project.getGradle().getStartParameter().isOffline();
		OneiroExtension.Stubs stubs = extension.getStubs();
		Provider<String> latest = project.getProviders().of(LatestStubsCommit.class, spec -> {
			spec.getParameters().getRemote().set(stubs.getRemote());
			spec.getParameters().getCache().set(cache);
			spec.getParameters().getOffline().set(offline);
			spec.getParameters().getRefresh().set(refresh);
		});
		return task -> {
			task.getLocalStubs().set(stubs.getLocal());
			task.getRemote().set(stubs.getRemote());
			task.getCommit().set(stubs.getRemote().flatMap(remote -> stubs.getCommit().orElse(latest)));
			task.getStubsCache().set(cache);
			task.getLanguage().set(kotlin.map(isKotlin -> isKotlin ? "KOTLIN" : "JAVA"));
			task.getToolClasspath().from(tool);
			task.getJavaLauncher().set(launcher);
		};
	}

	/**
	 * Adds what {@code oneiroLibraries} reads from some Lua's checkout on this machine, the Lua of the folder it names,
	 * to the task's inputs.
	 */
	private static void localFiles(StageLibraries task, OneiroExtension.LuaCode code) {
		if (code.getLocal().isPresent()) {
			Directory local = code.getLocal().get();
			String from = code.getFrom().get();
			task.getLocalFiles().from((from.isEmpty() ? local : local.dir(from)).getAsFileTree()
					.matching(pattern -> pattern.include("**/*.lua")));
		}
	}

	/** Where fetched repositories, the stubs and the libraries, are kept, by commit, in Gradle's user home. */
	private static File repositoryCache(Project project) {
		return new File(project.getGradle().getGradleUserHomeDir(), "caches/oneiro/repositories");
	}

	/**
	 * The classes jars of the libraries a source set compiles against, which {@code oneiroLuaBindings} writes:
	 * {@code main} for those that ship, {@code dev} for those of the dev source set alone.
	 */
	private static FileCollection libraryJars(Project project, TaskProvider<GenerateLuaBindings> luaBindings,
			String sourceSet) {
		return project.files(luaBindings.flatMap(GenerateLuaBindings::getLibrariesFolder).map(folder -> folder
				.dir(sourceSet).getAsFileTree().matching(pattern -> pattern.include("*-bindings.jar"))));
	}

	/** The line map a source set's compilation writes, which {@code explainError} reads. */
	static Provider<RegularFile> lineMap(ProjectLayout layout, String sourceSet) {
		return layout.getBuildDirectory().file("oneiro/line-maps/" + sourceSet + ".txt");
	}

	/**
	 * The {@code dev} source set, compiled against the main one and the bindings of its own Lua, and the development
	 * folder: the plugin as it ships, with the dev set's Lua, compiled and its own, under {@code dev/}, a
	 * {@code main.lua} that loads its {@code DevPlugin} class, and the development folder's manifest.
	 */
	private static TaskProvider<Sync> devSourceSet(Project project, Compilers compilers, JavaPluginExtension java,
			SourceSet main, TaskProvider<Sync> assemblePlugin, TaskProvider<GenerateLuaBindings> luaBindings,
			Provider<Directory> stagedLibraries, TaskProvider<WriteManifest> devManifest) {
		ProjectLayout layout = project.getLayout();
		SourceSet dev = java.getSourceSets().create("dev", set -> set.setCompileClasspath(
				set.getCompileClasspath().plus(main.getOutput()).plus(main.getCompileClasspath())));
		project.getDependencies().add(dev.getImplementationConfigurationName(),
				project.files(luaBindings.flatMap(GenerateLuaBindings::getDevClassesJar)));
		project.getDependencies().add(dev.getImplementationConfigurationName(),
				libraryJars(project, luaBindings, "dev"));
		project.getConfigurations().getByName(dev.getAnnotationProcessorConfigurationName())
				.extendsFrom(project.getConfigurations().getByName(JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME));
		Provider<Directory> devLua = layout.getBuildDirectory().dir("oneiro/lua/dev");
		Provider<Directory> devLuaBuilt = compilers.lua(dev.getCompileJavaTaskName(), "oneiroDevKotlin", "dev", true,
				devLua, dev, null, main);
		TaskProvider<WriteDevMain> devMain = project.getTasks().register("oneiroDevMain", WriteDevMain.class, task -> {
			task.setDescription("Writes the development folder's main.lua, which loads the DevPlugin class.");
			task.getShippedMain().set(layout.dir(assemblePlugin.map(Sync::getDestinationDir))
					.map(folder -> folder.file("main.lua")));
			task.getDevLua().from(devLuaBuilt);
			task.getDevLuaFolder().set(devLua);
			task.getDevMain().set(layout.getBuildDirectory().file("oneiro/dev-main/main.lua"));
		});
		return project.getTasks().register("assembleDevPlugin", Sync.class, sync -> {
			sync.setGroup(GROUP);
			sync.setDescription("Assembles the development folder: the plugin as it ships and the dev source set.");
			sync.from(assemblePlugin, spec -> spec.exclude("main.lua", "plugin.json"));
			// A helper both source sets use is the same file in each, which the shipped folder brings already
			sync.from(devLuaBuilt, spec -> spec.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE));
			sync.from(layout.getProjectDirectory().dir("src/dev/lua"), spec -> spec.into("dev"));
			sync.from(stagedLibraries.map(folder -> folder.dir("dev")));
			sync.from(devMain.flatMap(WriteDevMain::getDevMain));
			sync.from(devManifest.flatMap(WriteManifest::getManifestFile));
			sync.into(layout.getBuildDirectory().dir("oneiro/dev-plugin"));
		});
	}

	/** A source set's files with one extension, in its Java or Kotlin folder: {@code src/main/java/**}{@code /*.kt}. */
	static FileCollection sourceFiles(ProjectLayout layout, String sourceSet, String extension) {
		return layout.getProjectDirectory().dir("src/" + sourceSet + "/java").getAsFileTree()
				.plus(layout.getProjectDirectory().dir("src/" + sourceSet + "/kotlin").getAsFileTree())
				.matching(pattern -> pattern.include("**/*." + extension));
	}

	/**
	 * Adds the JetBrains repositories that host the Kotlin Analysis API and the IntelliJ platform modules it runs on,
	 * which the Kotlin front end needs and Maven Central does not have. Each is asked only for those artifacts, and
	 * those artifacts only from it, so every other dependency of the build resolves as before.
	 */
	private static void jetBrainsRepositories(RepositoryHandler repositories) {
		MavenArtifactRepository dependencies = repositories.maven(repository -> {
			repository.setName("JetBrainsIntelliJDependencies");
			repository.setUrl(INTELLIJ_DEPENDENCIES);
		});
		repositories.exclusiveContent(exclusive -> exclusive.forRepositories(dependencies).filter(filter -> {
			filter.includeModuleByRegex("org\\.jetbrains\\.kotlin", ".*-for-ide");
			filter.includeGroup("com.intellij.platform");
			filter.includeGroupByRegex("org\\.jetbrains\\.intellij\\.deps.*");
		}));
		MavenArtifactRepository releases = repositories.maven(repository -> {
			repository.setName("JetBrainsIntelliJReleases");
			repository.setUrl(INTELLIJ_RELEASES);
		});
		repositories.exclusiveContent(exclusive -> exclusive.forRepositories(releases)
				.filter(filter -> filter.includeGroupByRegex("com\\.jetbrains\\.intellij\\..*")));
	}

	/** The versions of Oneiro and JSpecify this plugin was built with. */
	private static Properties versions() {
		Properties properties = new Properties();
		try (InputStream in = Objects.requireNonNull(OneiroPlugin.class.getResourceAsStream("oneiro.properties"),
				"oneiro.properties")) {
			properties.load(in);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return properties;
	}
}
