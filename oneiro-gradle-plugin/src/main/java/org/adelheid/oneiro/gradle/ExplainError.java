package org.adelheid.oneiro.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.adelheid.oneiro.plugin.ConsoleErrors;
import org.adelheid.oneiro.plugin.LuaLineMap;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.api.tasks.options.Option;
import org.jspecify.annotations.Nullable;

/**
 * {@code explainError}: shows the errors of this plugin in text copied from the client's console, with each place in
 * the Lua they name as the Java file and line it came from, and that line's code. It reads the line maps of the latest
 * build, so it explains the Lua that build wrote: after a change, build and reload the plugin before reproducing the
 * error.
 */
@UntrackedTask(because = "It explains the text it is given, each time it runs.")
public abstract class ExplainError extends DefaultTask {

	/** The file that holds the console's text, relative to the project folder. */
	@Internal
	@Option(option = "input", description = "The file that holds text copied from the client's console.")
	public abstract Property<String> getInput();

	/** The plugin's name, which the console puts in front of each of its errors. */
	@Internal
	public abstract Property<String> getPluginName();

	/** The line maps that compiling the main and dev source sets writes; a set not compiled yet has none. */
	@Internal
	public abstract ConfigurableFileCollection getLineMaps();

	/** The folders of the plugin's Java sources, where each line's code is read. */
	@Internal
	public abstract ConfigurableFileCollection getSourceFolders();

	@Internal
	public abstract DirectoryProperty getProjectFolder();

	@TaskAction
	public void explain() {
		Path project = getProjectFolder().get().getAsFile().toPath();
		if (!getInput().isPresent()) {
			throw new GradleException("Copy the client console's text into a file, and name it:"
					+ " ./gradlew explainError --input=console.txt");
		}
		Path input = project.resolve(getInput().get());
		if (!Files.isRegularFile(input)) {
			throw new GradleException(input + " does not exist; copy the client console's text into it.");
		}
		LuaLineMap lines = lineMaps();
		if (lines == null) {
			throw new GradleException("The plugin has not been compiled yet, so there is no line map to explain its"
					+ " errors with; build it, reload it in the client, and reproduce the error.");
		}
		List<Path> sourceFolders = new ArrayList<>();
		getSourceFolders().getFiles().forEach(folder -> sourceFolders.add(folder.toPath()));
		String name = getPluginName().get();
		ConsoleErrors.Explanation explanation;
		try {
			explanation = ConsoleErrors.explain(Files.readString(input, StandardCharsets.UTF_8), name, lines,
					sourceFolders, project);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (!explanation.text().isEmpty()) {
			getLogger().quiet(explanation.text().stripTrailing() + "\n");
		}
		getLogger().quiet(explanation.errors() == 0
				? "No errors of " + name + " in " + input.getFileName() + "."
				: count(explanation.errors()) + " of " + name + ", newest first, as the console lists them.");
		if (explanation.otherPlugins() > 0) {
			getLogger().quiet("Left out " + count(explanation.otherPlugins()) + " of other plugins.");
		}
	}

	private @Nullable LuaLineMap lineMaps() {
		LuaLineMap lines = null;
		for (File file : getLineMaps().getFiles()) {
			if (file.isFile()) {
				try {
					LuaLineMap read = LuaLineMap.read(file.toPath());
					lines = lines == null ? read : lines.with(read);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
		}
		return lines;
	}

	private static String count(int errors) {
		return errors == 1 ? "1 error" : errors + " errors";
	}
}
