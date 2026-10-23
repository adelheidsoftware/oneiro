package org.adelheid.oneiro.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * The {@code main.lua} of the development folder: the plugin's own, then, when the dev source set has a
 * {@code DevPlugin} class, a {@code require} of its module, which runs after the plugin has started. The zip never
 * holds it.
 */
@CacheableTask
public abstract class WriteDevMain extends DefaultTask {

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getShippedMain();

	/** The dev source set's Lua. */
	@InputFiles
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getDevLua();

	@Internal
	public abstract DirectoryProperty getDevLuaFolder();

	@OutputFile
	public abstract RegularFileProperty getDevMain();

	@TaskAction
	public void write() {
		try {
			String main = Files.readString(getShippedMain().get().getAsFile().toPath(), StandardCharsets.UTF_8);
			if (Files.isRegularFile(getDevLuaFolder().get().getAsFile().toPath().resolve("dev/main.lua"))) {
				main += "\n-- Development only: the dev source set's helpers, which the plugin as it ships does not"
						+ " contain.\nrequire(\"dev/main\")\n";
			}
			Path devMain = getDevMain().get().getAsFile().toPath();
			Files.createDirectories(devMain.getParent());
			Files.writeString(devMain, main, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
