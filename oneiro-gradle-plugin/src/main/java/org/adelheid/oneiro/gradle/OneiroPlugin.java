package org.adelheid.oneiro.gradle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.file.Directory;
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
 *   <li>{@code oneiroSources}, the typed {@code Resources} and {@code Urls} classes;
 *   <li>Oneiro as a javac plugin in {@code compileJava}, which writes the Lua of a plugin written in Java, or
 *       {@code oneiroKotlin}, which writes the Lua of one written in Kotlin, with the Kotlin JVM plugin applied;
 *   <li>{@code pluginManifest}, the {@code plugin.json};
 *   <li>{@code assemblePlugin}, {@code checkPlugin} and {@code packagePlugin}: the folder that ships, its checks and
 *       its zip;
 *   <li>the {@code dev} source set and {@code assembleDevPlugin}, the development folder;
 *   <li>{@code linkDevPlugin}, the link into the client's {@code dev_plugins} folder;
 *   <li>{@code testLua}, the plugin's Lua tests.
 * </ul>
 */
public final class OneiroPlugin implements Plugin<Project> {

	private static final String GROUP = "oneiro";
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
		project.getPluginManager().withPlugin("org.jetbrains.kotlin.jvm", applied -> kotlin.set(true));
		dependencies.add(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, "org.adelheid:oneiro-api:" + version);
		dependencies.add(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME,
				"org.jspecify:jspecify:" + versions.getProperty("jspecify"));
		dependencies.add(JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME,
				"org.adelheid:oneiro-frontend-java:" + version);

		TaskProvider<GenerateBindings> bindings = bindings(project, extension, tool, launcher, kotlin);
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
			task.getPluginSources().from(sourceFiles(layout, "main", "java"), sourceFiles(layout, "main", "kt"));
			task.getOutputDirectory().set(layout.getBuildDirectory().dir("generated/sources/oneiro/java/main"));
		});
		main.getJava().srcDir(sources.flatMap(GenerateSources::getOutputDirectory));

		Provider<Directory> mainLua = layout.getBuildDirectory().dir("oneiro/lua/main");
		Compilers compilers = new Compilers(project, extension, kotlin, kotlinTool, launcher);
		Provider<Directory> mainLuaBuilt = compilers.lua(JavaPlugin.COMPILE_JAVA_TASK_NAME, "oneiroKotlin", "main",
				false, mainLua, main, sources.flatMap(GenerateSources::getOutputDirectory));

		TaskProvider<WriteManifest> manifest = tasks.register("pluginManifest", WriteManifest.class, task -> {
			task.setGroup(GROUP);
			task.setDescription("Writes plugin.json from the oneiro { plugin { } } block.");
			OneiroExtension.PluginManifest plugin = extension.getPlugin();
			task.getPluginId().set(plugin.getId());
			task.getPluginName().set(plugin.getName());
			task.getVersion().set(pluginVersion);
			task.getContactEmail().set(plugin.getContactEmail());
			task.getRequiredDependencies().set(plugin.getRequiredDependencies());
			task.getOptionalDependencies().set(plugin.getOptionalDependencies());
			task.getRequiredPermissions().set(plugin.getRequiredPermissions());
			task.getOptionalPermissions().set(plugin.getOptionalPermissions());
			task.getUrls().set(plugin.getUrls());
			task.getSettingsIcon().set(plugin.getSettingsIcon());
			task.getRibbonIcon().set(plugin.getRibbonIcon());
			task.getManifestFile().set(layout.getBuildDirectory().file("oneiro/manifest/plugin.json"));
		});

		Provider<Directory> pluginFolder = layout.getBuildDirectory().dir("oneiro/plugin");
		TaskProvider<Sync> assemblePlugin = tasks.register("assemblePlugin", Sync.class, sync -> {
			sync.setGroup(GROUP);
			sync.setDescription("Assembles the plugin as it ships: main.lua, plugin.json, src/ and resources/.");
			sync.from(mainLuaBuilt);
			sync.from(manifest.flatMap(WriteManifest::getManifestFile));
			sync.from(layout.getProjectDirectory().dir("src/main/resources"), spec -> spec.into("resources"));
			sync.into(pluginFolder);
		});

		TaskProvider<CheckPlugin> checkPlugin = tasks.register("checkPlugin", CheckPlugin.class, task -> {
			task.setGroup(GROUP);
			task.setDescription("Checks that the plugin folder holds only what may ship.");
			task.getPluginFolder().set(layout.dir(assemblePlugin.map(Sync::getDestinationDir)));
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

		TaskProvider<Sync> assembleDevPlugin = devSourceSet(project, compilers, java, main, assemblePlugin);
		boolean hasDev = layout.getProjectDirectory().dir("src/dev/java").getAsFile().isDirectory()
				|| layout.getProjectDirectory().dir("src/dev/kotlin").getAsFile().isDirectory();
		TaskProvider<Sync> linked = hasDev ? assembleDevPlugin : assemblePlugin;
		tasks.register("linkDevPlugin", LinkDevPlugin.class, task -> {
			task.setGroup(GROUP);
			task.setDescription("Links the plugin into the client's dev_plugins folder under its id.");
			task.dependsOn(linked);
			task.getDevPlugins().set(extension.getDevPlugins());
			task.getPluginId().set(extension.getPlugin().getId());
			task.getTarget().set(layout.dir(linked.map(Sync::getDestinationDir)));
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
			task.setDescription("Shows the plugin's errors in text copied from the client's console as Java lines.");
			task.getPluginName().set(extension.getPlugin().getName());
			task.getLineMaps().from(lineMap(layout, "main"), lineMap(layout, "dev"));
			task.getSourceFolders().from(main.getJava().getSourceDirectories(), dev.getJava().getSourceDirectories(),
					layout.getProjectDirectory().dir("src/main/kotlin"),
					layout.getProjectDirectory().dir("src/dev/kotlin"));
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

	/** {@code oneiroBindings}, with the stubs the build names: a local checkout, or a remote at a resolved commit. */
	private static TaskProvider<GenerateBindings> bindings(Project project, OneiroExtension extension,
			Configuration tool, Provider<JavaLauncher> launcher, Provider<Boolean> kotlin) {
		File cache = new File(project.getGradle().getGradleUserHomeDir(), "caches/oneiro/stubs");
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
		Provider<Directory> output = project.getLayout().getBuildDirectory().dir("oneiro/bindings");
		return project.getTasks().register("oneiroBindings", GenerateBindings.class, task -> {
			task.setGroup(GROUP);
			task.setDescription("Generates the RS3 bindings from the stubs the build names.");
			task.getLocalStubs().set(stubs.getLocal());
			task.getRemote().set(stubs.getRemote());
			task.getCommit().set(stubs.getRemote().flatMap(remote -> stubs.getCommit().orElse(latest)));
			task.getStubsCache().set(cache);
			task.getLanguage().set(kotlin.map(isKotlin -> isKotlin ? "KOTLIN" : "JAVA"));
			task.getToolClasspath().from(tool);
			task.getJavaLauncher().set(launcher);
			task.getClassesJar().set(output.map(folder -> folder.file("rs3-bindings.jar")));
			task.getSourcesJar().set(output.map(folder -> folder.file("rs3-bindings-sources.jar")));
		});
	}

	/** The line map a source set's compilation writes, which {@code explainError} reads. */
	static Provider<RegularFile> lineMap(ProjectLayout layout, String sourceSet) {
		return layout.getBuildDirectory().file("oneiro/line-maps/" + sourceSet + ".txt");
	}

	/**
	 * The {@code dev} source set, compiled against the main one, and the development folder: the plugin as it ships,
	 * with the dev set's Lua under {@code dev/} and a {@code main.lua} that loads its {@code DevPlugin} class.
	 */
	private static TaskProvider<Sync> devSourceSet(Project project, Compilers compilers, JavaPluginExtension java,
			SourceSet main, TaskProvider<Sync> assemblePlugin) {
		ProjectLayout layout = project.getLayout();
		SourceSet dev = java.getSourceSets().create("dev", set -> set.setCompileClasspath(
				set.getCompileClasspath().plus(main.getOutput()).plus(main.getCompileClasspath())));
		project.getConfigurations().getByName(dev.getAnnotationProcessorConfigurationName())
				.extendsFrom(project.getConfigurations().getByName(JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME));
		Provider<Directory> devLua = layout.getBuildDirectory().dir("oneiro/lua/dev");
		Provider<Directory> devLuaBuilt = compilers.lua(dev.getCompileJavaTaskName(), "oneiroDevKotlin", "dev", true,
				devLua, dev, null);
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
			sync.from(assemblePlugin, spec -> spec.exclude("main.lua"));
			sync.from(devLuaBuilt);
			sync.from(devMain.flatMap(WriteDevMain::getDevMain));
			sync.into(layout.getBuildDirectory().dir("oneiro/dev-plugin"));
		});
	}

	/** A source set's files with one extension, in its Java or Kotlin folder: {@code src/main/java/**}{@code /*.kt}. */
	static FileCollection sourceFiles(ProjectLayout layout, String sourceSet, String extension) {
		return layout.getProjectDirectory().dir("src/" + sourceSet + "/java").getAsFileTree()
				.plus(layout.getProjectDirectory().dir("src/" + sourceSet + "/kotlin").getAsFileTree())
				.matching(pattern -> pattern.include("**/*." + extension));
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
