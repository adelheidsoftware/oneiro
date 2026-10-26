package org.adelheid.oneiro.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.adelheid.oneiro.plugin.ClientFacts;
import org.adelheid.oneiro.plugin.LuaLibraries;
import org.adelheid.oneiro.plugin.Manifest;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * {@code pluginManifest}: the {@code plugin.json} of the {@code oneiro { plugin { } }} block, after checking it for
 * what the client or Mod.io would refuse; and, as {@code oneiroDevManifest}, the development folder's, which names each
 * dependency's folder on this machine where it has one and requires the dev source set's dependencies too.
 */
@CacheableTask
public abstract class WriteManifest extends DefaultTask {

	private final List<OneiroExtension.PluginDependency> dependencies = new ArrayList<>();

	@Input
	public abstract Property<String> getPluginId();

	@Input
	public abstract Property<String> getPluginName();

	@Input
	public abstract Property<String> getVersion();

	@Input
	public abstract Property<String> getContactEmail();

	/** The plugins this one depends on, which the build declares. */
	@Nested
	public List<OneiroExtension.PluginDependency> getDependencies() {
		return dependencies;
	}

	@Input
	public abstract SetProperty<String> getRequiredPermissions();

	@Input
	public abstract SetProperty<String> getOptionalPermissions();

	@Input
	public abstract MapProperty<String, String> getUrls();

	@Input
	public abstract Property<Boolean> getSettingsIcon();

	@Input
	public abstract Property<Boolean> getRibbonIcon();

	/** Whether this is the development folder's manifest. */
	@Input
	public abstract Property<Boolean> getDevelopment();

	/** The client's globals, among what {@code oneiroBindings} writes, which an alias may not take. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getClientFacts();

	@OutputFile
	public abstract RegularFileProperty getManifestFile();

	@TaskAction
	public void write() {
		Map<String, Manifest.Dependency> required = new LinkedHashMap<>();
		Map<String, Manifest.Dependency> optional = new LinkedHashMap<>();
		Map<String, Manifest.Dependency> development = new LinkedHashMap<>();
		for (OneiroExtension.PluginDependency dependency : dependencies) {
			Map<String, Manifest.Dependency> kind = dependency.getDevelopment().get() ? development
					: dependency.getUse().get() == LuaLibraries.Use.OPTIONAL ? optional : required;
			kind.put(dependency.getName(), new Manifest.Dependency(dependency.getModId().getOrNull(),
					dependency.getDevFolder().getOrNull()));
		}
		Manifest manifest = new Manifest(getPluginId().get(), getPluginName().get(), getVersion().get(),
				getContactEmail().get(), required, optional, development, sorted(getRequiredPermissions().get()),
				sorted(getOptionalPermissions().get()), getUrls().get(), getSettingsIcon().get(),
				getRibbonIcon().get());
		try {
			List<String> problems =
					manifest.problems(ClientFacts.read(getClientFacts().get().getAsFile().toPath()).globals());
			if (!problems.isEmpty()) {
				throw new GradleException("The plugin's manifest has problems:\n  - "
						+ String.join("\n  - ", problems));
			}
			Path file = getManifestFile().get().getAsFile().toPath();
			Files.createDirectories(file.getParent());
			Files.writeString(file, (getDevelopment().get() ? manifest.development() : manifest).toJson(),
					StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static List<String> sorted(Iterable<String> values) {
		List<String> list = new ArrayList<>();
		values.forEach(list::add);
		list.sort(null);
		return list;
	}
}
