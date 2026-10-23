package org.adelheid.oneiro.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.adelheid.oneiro.plugin.TypedSources;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.jspecify.annotations.Nullable;

/**
 * {@code oneiroSources}: the typed {@code Resources} and {@code Urls} classes, in the plugin's root package, from its
 * resource files and its manifest's base URLs.
 */
@CacheableTask
public abstract class GenerateSources extends DefaultTask {

	private static final Pattern PLUGIN_CLASS = Pattern.compile("\\bclass\\s+\\w+\\s+extends\\s+Plugin\\b");
	private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

	/** The files under {@code src/main/resources/}. */
	@InputFiles
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getResourceFiles();

	@Internal
	public abstract DirectoryProperty getResourcesFolder();

	@Input
	public abstract MapProperty<String, String> getUrls();

	/** The root package, or empty to take the plugin class's from the sources. */
	@Input
	public abstract Property<String> getRootPackage();

	/** The plugin's Java sources, read to find the plugin class's package when the build does not name one. */
	@InputFiles
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getJavaSources();

	@OutputDirectory
	public abstract DirectoryProperty getOutputDirectory();

	@TaskAction
	public void generate() {
		try {
			Path output = getOutputDirectory().get().getAsFile().toPath();
			clean(output);
			List<String> problems = new ArrayList<>();
			String rootPackage = rootPackage();
			String resources = TypedSources.resources(rootPackage, getResourcesFolder().get().getAsFile().toPath(),
					problems);
			String urls = TypedSources.urls(rootPackage, getUrls().get());
			if (!problems.isEmpty()) {
				throw new GradleException("The plugin's resources have problems:\n  - " + String.join("\n  - ", problems));
			}
			Path folder = output.resolve(rootPackage.replace('.', '/'));
			if (resources != null) {
				Files.createDirectories(folder);
				Files.writeString(folder.resolve("Resources.java"), resources, StandardCharsets.UTF_8);
			}
			if (urls != null) {
				Files.createDirectories(folder);
				Files.writeString(folder.resolve("Urls.java"), urls, StandardCharsets.UTF_8);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private String rootPackage() throws IOException {
		String configured = getRootPackage().get();
		if (!configured.isEmpty()) {
			return configured;
		}
		for (File source : getJavaSources().getFiles()) {
			String text = Files.readString(source.toPath(), StandardCharsets.UTF_8);
			String found = text.contains("extends Plugin") && PLUGIN_CLASS.matcher(text).find() ? packageOf(text) : null;
			if (found != null) {
				return found;
			}
		}
		throw new GradleException("No class extends Plugin, so Oneiro cannot tell the plugin's root package for its"
				+ " Resources and Urls classes; name it in the build: oneiro { rootPackage = \"com.example.herbs\" }.");
	}

	private static @Nullable String packageOf(String source) {
		Matcher matcher = PACKAGE.matcher(source);
		return matcher.find() ? matcher.group(1) : null;
	}

	private static void clean(Path folder) throws IOException {
		if (!Files.exists(folder)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(folder)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				if (!path.equals(folder)) {
					Files.delete(path);
				}
			}
		}
	}
}
