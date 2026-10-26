package org.adelheid.oneiro.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.adelheid.oneiro.plugin.ClientFacts;
import org.adelheid.oneiro.plugin.LuaLibraries;
import org.adelheid.oneiro.plugin.PackageChecks;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * {@code checkPlugin}: checks that every dependency the manifest names has a Mod.io ID; that the folder that ships
 * holds only what a plugin may, its vendored libraries among it, each of which the plugin's Lua requires; that each Lua
 * file under {@code src/} starts with its path; that every
 * {@code require} finds its file; and that no Lua in it uses a member of the client's that is for development only,
 * nor, but in a library, one that needs a permission the manifest lacks. It runs as part of {@code check}, and before
 * the zip is made.
 */
@CacheableTask
public abstract class CheckPlugin extends DefaultTask {

	@InputDirectory
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract DirectoryProperty getPluginFolder();

	/** The client's members that need a permission or are for development only, which {@code oneiroBindings} writes. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getClientFacts();

	/** The permissions the manifest requests, required or optional. */
	@Input
	public abstract SetProperty<String> getPermissions();

	/** The list of the libraries the plugin uses and their files, which {@code oneiroLibraries} writes. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getLibraries();

	/**
	 * The dependencies that ship but name only a folder on this machine, each by its alias, with its kind,
	 * {@code required} or {@code optional}.
	 */
	@Input
	public abstract MapProperty<String, String> getUnpublishedDependencies();

	/** The outcome, kept so that the check is up to date while the folder does not change. */
	@OutputFile
	public abstract RegularFileProperty getReport();

	@TaskAction
	public void check() {
		try {
			List<String> problems = PackageChecks.check(getPluginFolder().get().getAsFile().toPath(),
					ClientFacts.read(getClientFacts().get().getAsFile().toPath()), getPermissions().get(),
					LuaLibraries.read(getLibraries().get().getAsFile().toPath()).shipped(),
					getUnpublishedDependencies().get());
			Path report = getReport().get().getAsFile().toPath();
			Files.createDirectories(report.getParent());
			String outcome = problems.isEmpty() ? "The plugin may ship.\n" : String.join("\n", problems) + "\n";
			Files.writeString(report, outcome, StandardCharsets.UTF_8);
			if (!problems.isEmpty()) {
				throw new GradleException("The plugin folder " + getPluginFolder().get().getAsFile()
						+ " may not ship:\n  - " + String.join("\n  - ", problems));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
