package org.adelheid.oneiro.gradle;

import java.util.List;
import org.adelheid.oneiro.gradle.tasks.CompileKotlinLua;
import org.adelheid.oneiro.gradle.tasks.WriteCompilerSettings;
import org.adelheid.rspc.plugin.Manifest;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.jspecify.annotations.Nullable;

/**
 * How each source set's Lua is compiled: by Oneiro's javac plugin inside {@code compileJava} for a plugin written in
 * Java, or by {@code oneiroKotlin} for one written in Kotlin. Both read the settings that
 * {@code oneiroCompilerSettings} writes, and both write the Lua and its line map to the same places, from where the
 * rest of the build takes them.
 */
final class Compilers {

	private final Project project;
	private final OneiroExtension extension;
	private final Provider<Boolean> kotlin;
	private final Configuration kotlinTool;
	private final Provider<JavaLauncher> launcher;

	/**
	 * @param kotlin whether the plugin is written in Kotlin, known once the build has applied its plugins
	 * @param kotlinTool the Kotlin front end and what it needs
	 * @param launcher the JDK 25 the front end's worker runs on
	 */
	Compilers(Project project, OneiroExtension extension, Provider<Boolean> kotlin, Configuration kotlinTool,
			Provider<JavaLauncher> launcher) {
		this.project = project;
		this.extension = extension;
		this.kotlin = kotlin;
		this.kotlinTool = kotlinTool;
		this.launcher = launcher;
	}

	/**
	 * Compiles a source set to Lua, with the settings written first, and returns the Lua's folder, which carries the
	 * task that writes it.
	 *
	 * @param compileJava the source set's javac task, which runs Oneiro for a plugin written in Java
	 * @param compileKotlin the name of the task that runs Oneiro for a plugin written in Kotlin
	 * @param generated the folder of Java sources Oneiro generates for the source set, such as {@code Resources}, which
	 *     the Kotlin reads; null for none
	 * @param main for the dev source set, the main one, whose sources its compile reads for what their classes are, in
	 *     place of their compiled classes; null for the main set itself
	 */
	Provider<Directory> lua(String compileJava, String compileKotlin, String name, boolean dev, Provider<Directory> lua,
			SourceSet sourceSet, @Nullable Provider<Directory> generated, @Nullable SourceSet main) {
		ProjectLayout layout = project.getLayout();
		Provider<RegularFile> lineMap = OneiroPlugin.lineMap(layout, name);
		// The main set's Kotlin, and its Java, which for a plugin written in Kotlin is the generated Resources and Urls
		FileCollection mainSources = main == null
				? project.files()
				: OneiroPlugin.sourceFiles(layout, "main", "kt").plus(main.getJava());
		TaskProvider<WriteCompilerSettings> settings = settings(name, dev, lua, lineMap, mainSources);
		Provider<RegularFile> settingsFile = settings.flatMap(WriteCompilerSettings::getSettingsFile);
		project.getTasks().named(compileJava, JavaCompile.class, compile -> {
			// The Lua of every file depends on the others, so every build compiles them all
			compile.getOptions().setIncremental(false);
			// A plugin written in Java has its dev set compiled with the main set's sources, which Oneiro reads
			compile.source(kotlin.map(isKotlin -> isKotlin ? project.files() : mainSources));
			compile.getOptions().getCompilerArgumentProviders()
					.add(new OneiroJavacArguments(settingsFile, kotlin.map(isKotlin -> !isKotlin)));
			// Only a plugin written in Java writes its Lua here
			compile.getOutputs().dirs(kotlin.map(isKotlin -> isKotlin ? List.<Object>of() : List.<Object>of(lua)))
					.withPropertyName("oneiroLua");
			compile.getOutputs().files(kotlin.map(isKotlin -> isKotlin ? List.<Object>of() : List.<Object>of(lineMap)))
					.withPropertyName("oneiroLineMap");
		});
		TaskProvider<CompileKotlinLua> kotlinTask = project.getTasks().register(compileKotlin, CompileKotlinLua.class,
				task -> {
					task.setGroup("oneiro");
					task.setDescription("Compiles the " + name + " source set's Kotlin to Lua with Oneiro.");
					task.getKotlinSources().from(OneiroPlugin.sourceFiles(layout, name, "kt"));
					if (generated != null) {
						task.getJavaSources().from(generated);
					}
					task.getOwnJavaSources().from(OneiroPlugin.sourceFiles(layout, name, "java"));
					task.getMainSources().from(mainSources);
					// The main set's compiled classes would be its sources a second time
					task.getClasspath().from(main == null
							? sourceSet.getCompileClasspath()
							: sourceSet.getCompileClasspath().minus(main.getOutput()));
					task.getSettingsFile().set(settingsFile);
					task.getToolClasspath().from(kotlinTool);
					task.getJavaLauncher().set(launcher);
					task.getLuaOutput().set(lua);
					task.getLineMap().set(lineMap);
				});
		// Kotlin's own compiler runs first, so that a type error is reported as it reports it, before Oneiro looks
		project.getPluginManager().withPlugin("org.jetbrains.kotlin.jvm", applied -> kotlinTask.configure(
				task -> task.dependsOn(sourceSet.getCompileTaskName("kotlin"))));
		TaskProvider<Task> javaTask = project.getTasks().named(compileJava);
		return kotlin.flatMap(isKotlin -> isKotlin
				? kotlinTask.map(task -> lua.get())
				: javaTask.map(task -> lua.get()));
	}

	/** The settings Oneiro reads when it compiles a source set, in either language. */
	private TaskProvider<WriteCompilerSettings> settings(String name, boolean dev, Provider<Directory> lua,
			Provider<RegularFile> lineMap, FileCollection mainSources) {
		ProjectLayout layout = project.getLayout();
		return project.getTasks().register(dev ? "oneiroDevCompilerSettings" : "oneiroCompilerSettings",
				WriteCompilerSettings.class, task -> {
					task.setDescription("Writes the settings Oneiro compiles the " + name + " source set with.");
					OneiroExtension.PluginManifest plugin = extension.getPlugin();
					task.getRootPackage().set(extension.getRootPackage());
					task.getPluginId().set(plugin.getId());
					task.getPermissions().addAll(plugin.getRequiredPermissions());
					task.getPermissions().addAll(plugin.getOptionalPermissions());
					task.getFlags().set(plugin.getSettingsIcon().zip(plugin.getRibbonIcon(), Manifest::flags));
					task.getDev().set(dev);
					task.getMainSources().from(mainSources);
					Directory luaFolder = layout.getProjectDirectory().dir("src/" + name + "/lua");
					task.getLuaFolder().set(luaFolder);
					task.getLuaFiles().from(luaFolder.getAsFileTree());
					task.getWarningsAsErrors().set(extension.getWarningsAsErrors());
					task.getLuaOutput().set(lua.map(folder -> folder.getAsFile().getAbsolutePath()));
					task.getLineMap().set(lineMap.map(file -> file.getAsFile().getAbsolutePath()));
					task.getSettingsFile()
							.set(layout.getBuildDirectory().file("oneiro/compiler/" + name + ".properties"));
				});
	}
}
