package org.adelheid.oneiro.gradle.tasks;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.adelheid.oneiro.plugin.CompilerSettings;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Writes the settings Oneiro compiles a source set with, which its javac plugin reads inside {@code compileJava} and
 * its Kotlin front end in {@code oneiroKotlin}: the root package, the plugin's identifier, the permissions and flags
 * its manifest declares, the files of the source set's own Lua, and where the Lua and its line map go.
 */
@CacheableTask
public abstract class WriteCompilerSettings extends DefaultTask {

	/** The root package, or empty to take the plugin class's. */
	@Input
	public abstract Property<String> getRootPackage();

	@Input
	public abstract Property<String> getPluginId();

	@Input
	public abstract SetProperty<String> getPermissions();

	@Input
	public abstract SetProperty<String> getFlags();

	@Input
	public abstract Property<Boolean> getDev();

	/** The files of the source set's own Lua, under {@link #getLuaFolder()}. */
	@InputFiles
	@IgnoreEmptyDirectories
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getLuaFiles();

	/** The folder of the source set's own Lua, {@code src/main/lua} or {@code src/dev/lua}. */
	@Internal
	public abstract DirectoryProperty getLuaFolder();

	@Input
	public abstract Property<Boolean> getWarningsAsErrors();

	/** The absolute path of the folder the Lua goes to. */
	@Input
	public abstract Property<String> getLuaOutput();

	/** The absolute path of the file the line map goes to. */
	@Input
	public abstract Property<String> getLineMap();

	@OutputFile
	public abstract RegularFileProperty getSettingsFile();

	@TaskAction
	public void write() {
		try {
			CompilerSettings settings = new CompilerSettings(getRootPackage().get(), getPluginId().get(),
					getPermissions().get(), getFlags().get(), luaFiles(), getDev().get(), Path.of(getLuaOutput().get()),
					Path.of(getLineMap().get()), getWarningsAsErrors().get());
			settings.write(getSettingsFile().get().getAsFile().toPath());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The own Lua files' paths in the plugin: {@code src/main/lua/core/levels.lua} is {@code src/core/levels.lua}. */
	private Set<String> luaFiles() {
		Path folder = getLuaFolder().get().getAsFile().toPath();
		String shipped = getDev().get() ? "dev/" : "src/";
		Set<String> paths = new TreeSet<>();
		for (File file : getLuaFiles().getFiles()) {
			paths.add(shipped + folder.relativize(file.toPath()).toString().replace('\\', '/'));
		}
		return paths;
	}
}
