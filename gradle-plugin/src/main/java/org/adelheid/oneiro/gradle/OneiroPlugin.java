package org.adelheid.oneiro.gradle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.file.Directory;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;

/**
 * The Gradle plugin {@code org.adelheid.oneiro}. It makes the project a plugin for the RS3 client: the RS3 bindings on
 * the compile classpath ({@code oneiroBindings}), the typed {@code Resources} and {@code Urls}
 * ({@code oneiroSources}), Oneiro as a javac plugin in {@code compileJava}, which writes the Lua, the manifest
 * ({@code pluginManifest}), the folder that ships ({@code assemblePlugin}) with its checks ({@code checkPlugin}) and
 * zip ({@code packagePlugin}), the {@code dev} source set and its folder ({@code assembleDevPlugin}), the link into
 * the client ({@code linkDevPlugin}) and the Lua tests ({@code testLua}).
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

		JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
		// Oneiro reads plugins at the Java 25 language level, and its javac plugin runs on JDK 25
		java.getToolchain().getLanguageVersion().convention(JavaLanguageVersion.of(25));
		Provider<JavaLauncher> launcher = project.getExtensions().getByType(JavaToolchainService.class)
				.launcherFor(spec -> spec.getLanguageVersion().set(JavaLanguageVersion.of(25)));

		Configuration tool = project.getConfigurations().create("oneiroTool", configuration -> {
			configuration.setDescription("Oneiro's bindings generator and Lua runner, which run in worker processes.");
			configuration.setCanBeConsumed(false);
		});
		DependencyHandler dependencies = project.getDependencies();
		dependencies.add(tool.getName(), "org.adelheid:bindings-gen:" + version);
		dependencies.add(tool.getName(), "org.adelheid:lua-runner:" + version);
		dependencies.add(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, "org.adelheid:api:" + version);
		dependencies.add(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME,
				"org.jspecify:jspecify:" + versions.getProperty("jspecify"));
		dependencies.add(JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME, "org.adelheid:frontend-java:" + version);

		TaskProvider<GenerateBindings> bindings = bindings(project, extension, tool, launcher);
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
			task.getJavaSources().from(layout.getProjectDirectory().dir("src/main/java").getAsFileTree()
					.matching(pattern -> pattern.include("**/*.java")));
			task.getOutputDirectory().set(layout.getBuildDirectory().dir("generated/sources/oneiro/java/main"));
		});
		main.getJava().srcDir(sources.flatMap(GenerateSources::getOutputDirectory));

		Provider<Directory> mainLua = layout.getBuildDirectory().dir("oneiro/lua/main");
		compileWithOneiro(project, extension, JavaPlugin.COMPILE_JAVA_TASK_NAME, "main", false, mainLua);

		TaskProvider<WriteManifest> manifest = tasks.register("pluginManifest", WriteManifest.class, task -> {
			task.setGroup(GROUP);
			task.setDescription("Writes plugin.json from the oneiro { plugin { } } block.");
			OneiroExtension.PluginManifest plugin = extension.getPlugin();
			task.getPluginId().set(plugin.getId());
			task.getPluginName().set(plugin.getName());
			task.getVersion().set(project.provider(() -> String.valueOf(project.getVersion())));
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
			sync.from(tasks.named(JavaPlugin.COMPILE_JAVA_TASK_NAME).map(compile -> mainLua.get()));
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
			zip.getArchiveFileName().set(extension.getPlugin().getId()
					.zip(project.provider(() -> String.valueOf(project.getVersion())), (id, number) -> id + "-" + number
							+ ".zip"));
			zip.getDestinationDirectory().set(layout.getBuildDirectory().dir("distributions"));
			zip.setPreserveFileTimestamps(false);
			zip.setReproducibleFileOrder(true);
			zip.dirPermissions(permissions -> permissions.unix("rwxr-xr-x"));
			zip.filePermissions(permissions -> permissions.unix("rw-r--r--"));
		});
		tasks.named("assemble", assemble -> assemble.dependsOn(packagePlugin));

		TaskProvider<Sync> assembleDevPlugin = devSourceSet(project, extension, java, main, assemblePlugin);
		boolean hasDev = layout.getProjectDirectory().dir("src/dev/java").getAsFile().isDirectory();
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
			extension.getDevPlugins().convention(project.getLayout().getProjectDirectory().dir(
					windows.getAbsolutePath()));
		}
	}

	/** {@code oneiroBindings}, with the stubs the build names: a local checkout, or a remote at a resolved commit. */
	private static TaskProvider<GenerateBindings> bindings(Project project, OneiroExtension extension,
			Configuration tool, Provider<JavaLauncher> launcher) {
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
			task.getToolClasspath().from(tool);
			task.getJavaLauncher().set(launcher);
			task.getClassesJar().set(output.map(folder -> folder.file("rs3-bindings.jar")));
			task.getSourcesJar().set(output.map(folder -> folder.file("rs3-bindings-sources.jar")));
		});
	}

	/** Runs Oneiro inside a compile task, with its settings written first, and declares the Lua it writes. */
	private static void compileWithOneiro(Project project, OneiroExtension extension, String compileTask, String name,
			boolean dev, Provider<Directory> lua) {
		ProjectLayout layout = project.getLayout();
		TaskProvider<WriteCompilerSettings> settings = project.getTasks().register(
				dev ? "oneiroDevCompilerSettings" : "oneiroCompilerSettings", WriteCompilerSettings.class, task -> {
					task.setDescription("Writes the settings of Oneiro's javac plugin for " + compileTask + ".");
					OneiroExtension.PluginManifest plugin = extension.getPlugin();
					task.getRootPackage().set(extension.getRootPackage());
					task.getPluginId().set(plugin.getId());
					task.getPermissions().addAll(plugin.getRequiredPermissions());
					task.getPermissions().addAll(plugin.getOptionalPermissions());
					task.getFlags().set(plugin.getSettingsIcon().zip(plugin.getRibbonIcon(), OneiroPlugin::flags));
					task.getDev().set(dev);
					task.getWarningsAsErrors().set(extension.getWarningsAsErrors());
					task.getLuaOutput().set(lua.map(folder -> folder.getAsFile().getAbsolutePath()));
					task.getSettingsFile().set(layout.getBuildDirectory().file("oneiro/compiler/" + name
							+ ".properties"));
				});
		project.getTasks().named(compileTask, JavaCompile.class, compile -> {
			// The Lua of every file depends on the others, so every build compiles them all
			compile.getOptions().setIncremental(false);
			compile.getOptions().getCompilerArgumentProviders()
					.add(new OneiroJavacArguments(settings.flatMap(WriteCompilerSettings::getSettingsFile)));
			compile.getOutputs().dir(lua).withPropertyName("oneiroLua");
		});
	}

	private static Set<String> flags(boolean settingsIcon, boolean ribbonIcon) {
		Set<String> flags = new TreeSet<>();
		if (settingsIcon) {
			flags.add("hasSettingsIcon");
		}
		if (ribbonIcon) {
			flags.add("hasRibbonIcon");
		}
		return flags;
	}

	/**
	 * The {@code dev} source set, compiled against the main one, and the development folder: the plugin as it ships,
	 * with the dev set's Lua under {@code dev/} and a {@code main.lua} that loads its {@code DevPlugin} class.
	 */
	private static TaskProvider<Sync> devSourceSet(Project project, OneiroExtension extension, JavaPluginExtension java,
			SourceSet main, TaskProvider<Sync> assemblePlugin) {
		ProjectLayout layout = project.getLayout();
		SourceSet dev = java.getSourceSets().create("dev", set -> set.setCompileClasspath(
				set.getCompileClasspath().plus(main.getOutput()).plus(main.getCompileClasspath())));
		project.getConfigurations().getByName(dev.getAnnotationProcessorConfigurationName())
				.extendsFrom(project.getConfigurations().getByName(JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME));
		Provider<Directory> devLua = layout.getBuildDirectory().dir("oneiro/lua/dev");
		compileWithOneiro(project, extension, dev.getCompileJavaTaskName(), "dev", true, devLua);
		TaskProvider<WriteDevMain> devMain = project.getTasks().register("oneiroDevMain", WriteDevMain.class, task -> {
			task.setDescription("Writes the development folder's main.lua, which loads the DevPlugin class.");
			task.getShippedMain().set(layout.dir(assemblePlugin.map(Sync::getDestinationDir)).map(folder ->
					folder.file("main.lua")));
			task.getDevLua().from(project.getTasks().named(dev.getCompileJavaTaskName()).map(compile -> devLua.get()));
			task.getDevLuaFolder().set(devLua);
			task.getDevMain().set(layout.getBuildDirectory().file("oneiro/dev-main/main.lua"));
		});
		return project.getTasks().register("assembleDevPlugin", Sync.class, sync -> {
			sync.setGroup(GROUP);
			sync.setDescription("Assembles the development folder: the plugin as it ships and the dev source set.");
			sync.from(assemblePlugin, spec -> spec.exclude("main.lua"));
			sync.from(project.getTasks().named(dev.getCompileJavaTaskName()).map(compile -> devLua.get()));
			sync.from(devMain.flatMap(WriteDevMain::getDevMain));
			sync.into(layout.getBuildDirectory().dir("oneiro/dev-plugin"));
		});
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
