package org.adelheid.oneiro.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.adelheid.oneiro.plugin.CompilerSettings;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/**
 * Writes the settings Oneiro compiles a source set with, which its javac plugin reads inside {@code compileJava} and
 * its Kotlin front end in {@code oneiroKotlin}: the root package, the plugin's identifier, the permissions and flags
 * its manifest declares, and where the Lua and its line map go.
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
					getPermissions().get(), getFlags().get(), getDev().get(), Path.of(getLuaOutput().get()),
					Path.of(getLineMap().get()), getWarningsAsErrors().get());
			settings.write(getSettingsFile().get().getAsFile().toPath());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
