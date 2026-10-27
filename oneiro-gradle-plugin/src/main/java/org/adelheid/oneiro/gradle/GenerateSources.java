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
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.adelheid.oneiro.plugin.TypedSources;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.jspecify.annotations.Nullable;

/**
 * {@code oneiroSources}: the typed {@code Resources} and {@code Urls} classes, in the plugin's root package, from its
 * resource files and its manifest's base URLs, and the root package itself, which {@code oneiroLuaBindings} puts the
 * bindings of the plugin's own Lua below, with the packages of the plugin's code, whose names those bindings leave to
 * it.
 */
@CacheableTask
public abstract class GenerateSources extends DefaultTask {

	private static final Pattern PLUGIN_CLASS = Pattern.compile("\\bclass\\s+\\w+\\s+extends\\s+Plugin\\b");
	private static final Pattern KOTLIN_PLUGIN_CLASS = Pattern.compile("\\bclass\\s+\\w+\\s*:\\s*Plugin\\s*\\(");
	private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;?\\s*$", Pattern.MULTILINE);

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

	/**
	 * The plugin's Java and Kotlin sources, of both source sets, read for their packages, and for the plugin class's
	 * package when the build names none.
	 */
	@InputFiles
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getPluginSources();

	@OutputDirectory
	public abstract DirectoryProperty getOutputDirectory();

	/** The file the root package goes to, found in the sources when the build names none. */
	@OutputFile
	public abstract RegularFileProperty getRootPackageFile();

	/** The file the packages of the plugin's code go to, one a line. */
	@OutputFile
	public abstract RegularFileProperty getCodePackagesFile();

	@TaskAction
	public void generate() {
		try {
			Path output = getOutputDirectory().get().getAsFile().toPath();
			clean(output);
			List<String> problems = new ArrayList<>();
			String rootPackage = rootPackage();
			Path rootPackageFile = getRootPackageFile().get().getAsFile().toPath();
			Files.createDirectories(rootPackageFile.getParent());
			Files.writeString(rootPackageFile, rootPackage + "\n", StandardCharsets.UTF_8);
			Path codePackagesFile = getCodePackagesFile().get().getAsFile().toPath();
			Files.createDirectories(codePackagesFile.getParent());
			Files.writeString(codePackagesFile, codePackages(), StandardCharsets.UTF_8);
			String resources = TypedSources.resources(rootPackage, getResourcesFolder().get().getAsFile().toPath(),
					problems);
			String urls = TypedSources.urls(rootPackage, getUrls().get());
			if (!problems.isEmpty()) {
				throw new GradleException("The plugin's resources have problems:\n  - "
						+ String.join("\n  - ", problems));
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
		for (File source : getPluginSources().getFiles()) {
			String text = Files.readString(source.toPath(), StandardCharsets.UTF_8);
			Pattern pluginClass = source.getName().endsWith(".kt") ? KOTLIN_PLUGIN_CLASS : PLUGIN_CLASS;
			if (text.contains("Plugin") && pluginClass.matcher(text).find()) {
				String found = packageOf(text);
				if (found != null) {
					return found;
				}
			}
		}
		throw new GradleException("No class extends Plugin, so Oneiro cannot tell the plugin's root package for its"
				+ " Resources and Urls classes; name it in the build: oneiro { rootPackage = \"com.example.herbs\" }.");
	}

	/** The packages the plugin's sources declare, one a line, in order. */
	private String codePackages() throws IOException {
		Set<String> packages = new TreeSet<>();
		for (File source : getPluginSources().getFiles()) {
			String found = packageOf(Files.readString(source.toPath(), StandardCharsets.UTF_8));
			if (found != null) {
				packages.add(found);
			}
		}
		StringBuilder lines = new StringBuilder();
		packages.forEach(name -> lines.append(name).append('\n'));
		return lines.toString();
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
