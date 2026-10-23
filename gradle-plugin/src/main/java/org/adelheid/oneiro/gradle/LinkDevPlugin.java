package org.adelheid.oneiro.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

/**
 * {@code linkDevPlugin}: links the plugin into the client's {@code dev_plugins} folder under its identifier, so that
 * the client runs the folder the build assembles: a directory junction on Windows, which needs no administrator
 * rights, and a symbolic link elsewhere. An earlier link is replaced; a real folder in its place is left alone.
 */
@UntrackedTask(because = "It changes the client's dev_plugins folder, outside the build.")
public abstract class LinkDevPlugin extends DefaultTask {

	@Internal
	public abstract DirectoryProperty getDevPlugins();

	@Internal
	public abstract Property<String> getPluginId();

	/** The folder the link points at: the plugin as it ships, or with the dev source set's helpers. */
	@Internal
	public abstract DirectoryProperty getTarget();

	@TaskAction
	public void link() {
		if (!getDevPlugins().isPresent()) {
			throw new GradleException("Oneiro does not know where the client's dev_plugins folder is on this system;"
					+ " name it in the build: oneiro { devPlugins = file(\"/path/to/dev_plugins\") }.");
		}
		Path devPlugins = getDevPlugins().get().getAsFile().toPath();
		Path link = devPlugins.resolve(getPluginId().get());
		Path target = getTarget().get().getAsFile().toPath().toAbsolutePath();
		try {
			if (!Files.isDirectory(devPlugins)) {
				throw new GradleException(devPlugins + " does not exist; start the beta client once, or name the right"
						+ " folder: oneiro { devPlugins = file(...) }.");
			}
			if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
				BasicFileAttributes attributes = Files.readAttributes(link, BasicFileAttributes.class,
						LinkOption.NOFOLLOW_LINKS);
				// A junction is neither a folder nor a symbolic link to Java; removing either leaves its target alone
				if (!attributes.isSymbolicLink() && !attributes.isOther()) {
					throw new GradleException(link + " is a folder, not a link; move it away, and Oneiro links the"
							+ " plugin there.");
				}
				Files.delete(link);
			}
			if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
				junction(link, target);
			} else {
				Files.createSymbolicLink(link, target);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		getLogger().lifecycle("Linked {} to {}. In the client's console: prf, then pon {} (prl {} after each build).",
				link, target, getPluginId().get(), getPluginId().get());
	}

	private static void junction(Path link, Path target) throws IOException {
		Process process = new ProcessBuilder(List.of("cmd", "/c", "mklink", "/J", link.toString(), target.toString()))
				.redirectErrorStream(true)
				.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		try {
			if (process.waitFor() != 0) {
				throw new GradleException("Windows could not create the junction " + link + ": " + output.strip());
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new GradleException("Creating the junction " + link + " was interrupted.", e);
		}
	}
}
