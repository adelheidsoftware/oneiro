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
import org.adelheid.oneiro.plugin.Manifest;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/**
 * {@code pluginManifest}: the {@code plugin.json} of the {@code oneiro { plugin { } }} block, after checking it for
 * what the client or Mod.io would refuse.
 */
@CacheableTask
public abstract class WriteManifest extends DefaultTask {

	@Input
	public abstract Property<String> getPluginId();

	@Input
	public abstract Property<String> getPluginName();

	@Input
	public abstract Property<String> getVersion();

	@Input
	public abstract Property<String> getContactEmail();

	@Input
	public abstract MapProperty<String, String> getRequiredDependencies();

	@Input
	public abstract MapProperty<String, String> getOptionalDependencies();

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

	@OutputFile
	public abstract RegularFileProperty getManifestFile();

	@TaskAction
	public void write() {
		Manifest manifest = new Manifest(getPluginId().get(), getPluginName().get(), getVersion().get(),
				getContactEmail().get(), dependencies(getRequiredDependencies().get()),
				dependencies(getOptionalDependencies().get()), sorted(getRequiredPermissions().get()),
				sorted(getOptionalPermissions().get()), getUrls().get(), getSettingsIcon().get(), getRibbonIcon().get());
		List<String> problems = manifest.problems();
		if (!problems.isEmpty()) {
			throw new GradleException("The plugin's manifest has problems:\n  - " + String.join("\n  - ", problems));
		}
		Path file = getManifestFile().get().getAsFile().toPath();
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, manifest.toJson(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The block's {@code mod:<id>} and {@code folder:<name>} values as the manifest's dependencies. */
	private static Map<String, Manifest.Dependency> dependencies(Map<String, String> encoded) {
		Map<String, Manifest.Dependency> dependencies = new LinkedHashMap<>();
		encoded.forEach((alias, value) -> dependencies.put(alias, value.startsWith("mod:")
				? Manifest.Dependency.modIo(Integer.parseInt(value.substring("mod:".length())))
				: Manifest.Dependency.localFolder(value.substring("folder:".length()))));
		return dependencies;
	}

	private static List<String> sorted(Iterable<String> values) {
		List<String> list = new ArrayList<>();
		values.forEach(list::add);
		list.sort(null);
		return list;
	}
}
