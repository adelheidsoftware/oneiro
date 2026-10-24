package org.adelheid.oneiro.gradle;

import java.util.List;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;

/**
 * Runs Oneiro's bindings generator in the worker process. It is called by name, because the generator is compiled for
 * JDK 25 and this plugin for an older Java, so this class cannot link against it.
 */
public abstract class GenerateBindingsAction implements WorkAction<GenerateBindingsAction.Parameters> {

	private static final Logger LOGGER = Logging.getLogger(GenerateBindingsAction.class);

	/** The stubs folder, the folder the jars go to, and the plugin's language, {@code JAVA} or {@code KOTLIN}. */
	public interface Parameters extends WorkParameters {

		DirectoryProperty getStubs();

		DirectoryProperty getOutput();

		Property<String> getLanguage();
	}

	@Override
	public void execute() {
		List<?> warnings = (List<?>) Reflection.call("org.adelheid.oneiro.bindings.BindingsGenerator",
				"generateForBuild", getParameters().getStubs().get().getAsFile().getAbsolutePath(),
				getParameters().getOutput().get().getAsFile().getAbsolutePath(), getParameters().getLanguage().get());
		for (Object warning : warnings) {
			LOGGER.warn("Oneiro bindings: {}", warning);
		}
	}
}
