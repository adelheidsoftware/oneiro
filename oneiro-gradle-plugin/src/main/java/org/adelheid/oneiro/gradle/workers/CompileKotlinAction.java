package org.adelheid.oneiro.gradle.workers;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;

/**
 * Runs Oneiro's Kotlin front end in the worker process and reports its diagnostics, as kotlinc reports its own:
 * {@code e: file:///.../HerbPlugin.kt:12:5 message} for an error and {@code w:} for a warning. It is called by name,
 * because the front end is compiled for JDK 25 and this plugin for an older Java, so this class cannot link against
 * it.
 */
public abstract class CompileKotlinAction implements WorkAction<CompileKotlinAction.Parameters> {

	private static final Logger LOGGER = Logging.getLogger(CompileKotlinAction.class);

	/** The compiler's settings, the files to compile and what they compile against. */
	public interface Parameters extends WorkParameters {

		RegularFileProperty getSettingsFile();

		ConfigurableFileCollection getSources();

		ConfigurableFileCollection getClasspath();
	}

	@Override
	public void execute() {
		List<?> diagnostics = (List<?>) Reflection.call("org.adelheid.oneiro.kotlin.KotlinCompiler", "compileForBuild",
				getParameters().getSettingsFile().get().getAsFile().getAbsolutePath(),
				joined(getParameters().getSources()), joined(getParameters().getClasspath()));
		boolean failed = false;
		for (Object diagnostic : diagnostics) {
			String line = String.valueOf(diagnostic);
			if (line.startsWith("e: ")) {
				failed = true;
				LOGGER.error(line);
			} else {
				LOGGER.warn(line);
			}
		}
		if (failed) {
			throw new GradleException("Oneiro could not compile the plugin's Kotlin to Lua; the errors are above.");
		}
	}

	private static String joined(ConfigurableFileCollection files) {
		return files.getFiles().stream().map(File::getAbsolutePath).collect(Collectors.joining(File.pathSeparator));
	}
}
