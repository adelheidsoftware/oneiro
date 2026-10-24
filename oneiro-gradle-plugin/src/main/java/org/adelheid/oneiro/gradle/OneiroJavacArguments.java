package org.adelheid.oneiro.gradle;

import java.util.List;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.process.CommandLineArgumentProvider;

/**
 * The javac options that run Oneiro inside {@code compileJava}. The javac plugin gets the URI of its settings file
 * rather than a path, because javac splits a plugin's arguments at spaces and paths may hold them. The simple compile
 * policy makes javac analyse every class before it desugars any, so that Oneiro sees the trees as written.
 */
final class OneiroJavacArguments implements CommandLineArgumentProvider {

	private final Provider<RegularFile> settings;

	OneiroJavacArguments(Provider<RegularFile> settings) {
		this.settings = settings;
	}

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public Provider<RegularFile> getSettings() {
		return settings;
	}

	@Override
	public Iterable<String> asArguments() {
		return List.of("-XDcompilePolicy=simple", "-Xplugin:Oneiro " + settings.get().getAsFile().toURI());
	}
}
