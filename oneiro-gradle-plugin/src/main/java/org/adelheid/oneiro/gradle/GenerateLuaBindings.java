package org.adelheid.oneiro.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * {@code oneiroLuaBindings}: the bindings of the Lua the plugin uses, generated from its LuaCATS annotations, as jars
 * of classes the plugin compiles against and of their sources for the IDE: a jar for each library it vendors, in
 * {@code org.adelheid.oneiro.lib.<name>}; one for {@code src/main/lua}, on the main source set's classpath; and one for
 * {@code src/dev/lua}, on the dev source set's alone, as are the libraries of the dev source set alone. Each Lua file
 * of the plugin's own is a class of the package of its folder below the root package, so that
 * {@code src/main/lua/core/levels.lua} is the class {@code <root>.core.Levels} and loads as
 * {@code require("src/core/levels")}. The stubs give the RS3 API's types, which the Lua may name, as the plugin's own
 * Lua may name its libraries'.
 */
@CacheableTask
public abstract class GenerateLuaBindings extends StubsTask {

	/** The RS3 bindings, which the classes compile against. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getRs3Jar();

	/** The file that holds the plugin's root package, which {@code oneiroSources} finds. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getRootPackageFile();

	/** The libraries {@code oneiroLibraries} staged, each with its Lua and the build's overrides file for it. */
	@InputDirectory
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract DirectoryProperty getLibraries();

	/** The Lua files of both source sets. */
	@InputFiles
	@IgnoreEmptyDirectories
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getLuaFiles();

	/** {@code src/main/lua}. */
	@Internal
	public abstract DirectoryProperty getMainLua();

	/** {@code src/dev/lua}. */
	@Internal
	public abstract DirectoryProperty getDevLua();

	/** The libraries' jars: {@code main/<name>-bindings.jar}, and {@code dev/} for the dev source set's alone. */
	@OutputDirectory
	public abstract DirectoryProperty getLibrariesFolder();

	@OutputFile
	public abstract RegularFileProperty getMainClassesJar();

	@OutputFile
	public abstract RegularFileProperty getMainSourcesJar();

	@OutputFile
	public abstract RegularFileProperty getDevClassesJar();

	@OutputFile
	public abstract RegularFileProperty getDevSourcesJar();

	@TaskAction
	public void generate() {
		String rootPackage;
		try {
			rootPackage = Files.readString(getRootPackageFile().get().getAsFile().toPath(), StandardCharsets.UTF_8)
					.strip();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		Path stubs = stubs(false);
		File output = getMainClassesJar().get().getAsFile().getParentFile();
		generatorQueue().submit(GenerateLuaBindingsAction.class, parameters -> {
			parameters.getStubs().set(stubs.toFile());
			parameters.getRs3Jar().set(getRs3Jar());
			parameters.getOutput().set(output);
			parameters.getLanguage().set(getLanguage());
			parameters.getRootPackage().set(rootPackage);
			parameters.getLibraries().set(getLibraries());
			parameters.getMainLua().set(getMainLua());
			parameters.getDevLua().set(getDevLua());
		});
	}
}
