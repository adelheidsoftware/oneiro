package org.adelheid.oneiro.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.adelheid.oneiro.plugin.PackageChecks;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * {@code checkPlugin}: checks that the folder that ships holds only what a plugin may, that every {@code require} finds
 * its file, and that no development-only code is in it. It runs as part of {@code check}, and before the zip is made.
 */
@CacheableTask
public abstract class CheckPlugin extends DefaultTask {

	@InputDirectory
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract DirectoryProperty getPluginFolder();

	/** The outcome, kept so that the check is up to date while the folder does not change. */
	@OutputFile
	public abstract RegularFileProperty getReport();

	@TaskAction
	public void check() {
		try {
			List<String> problems = PackageChecks.check(getPluginFolder().get().getAsFile().toPath());
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
