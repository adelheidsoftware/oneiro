package org.adelheid.oneiro.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;

/**
 * Runs the plugin's Lua tests in the worker process, through Oneiro's Lua runner, which it calls by name because the
 * runner is compiled for JDK 25 and this plugin for an older Java.
 */
public abstract class TestLuaAction implements WorkAction<TestLuaAction.Parameters> {

	private static final Logger LOGGER = Logging.getLogger(TestLuaAction.class);

	/** The Lua to use, the folders, and the report. */
	public interface Parameters extends WorkParameters {

		RegularFileProperty getLuaExecutable();

		DirectoryProperty getPluginFolder();

		DirectoryProperty getTestsFolder();

		DirectoryProperty getWorkFolder();

		RegularFileProperty getReport();
	}

	@Override
	public void execute() {
		Parameters parameters = getParameters();
		String installed = parameters.getLuaExecutable().isPresent()
				? parameters.getLuaExecutable().get().getAsFile().getAbsolutePath()
				: "";
		List<?> lines = (List<?>) Reflection.call("org.adelheid.oneiro.runner.LuaTests", "runForBuild", installed,
				parameters.getPluginFolder().get().getAsFile().getAbsolutePath(),
				parameters.getTestsFolder().get().getAsFile().getAbsolutePath(),
				parameters.getWorkFolder().get().getAsFile().getAbsolutePath());
		boolean passed = "passed".equals(lines.get(0));
		StringBuilder report = new StringBuilder();
		for (Object line : lines.subList(1, lines.size())) {
			report.append(line).append('\n');
		}
		Path file = parameters.getReport().get().getAsFile().toPath();
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, report.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (!passed) {
			throw new GradleException("The plugin's Lua tests failed:\n" + report.toString().stripTrailing());
		}
		LOGGER.lifecycle(report.toString().stripTrailing());
	}
}
