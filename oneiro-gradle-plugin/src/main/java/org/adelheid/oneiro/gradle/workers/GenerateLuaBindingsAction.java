package org.adelheid.oneiro.gradle.workers;

import java.util.List;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;

/**
 * Runs the bindings generator on the Lua the plugin uses, its libraries and its own, in the worker process, by name, as
 * {@link GenerateBindingsAction} runs it on the stubs.
 */
public abstract class GenerateLuaBindingsAction implements WorkAction<GenerateLuaBindingsAction.Parameters> {

	private static final Logger LOGGER = Logging.getLogger(GenerateLuaBindingsAction.class);

	/**
	 * The stubs, the RS3 bindings, the folder the jars go to, the language, the root package, the file of the packages
	 * of the plugin's code, the staged libraries and the folders of the plugin's own Lua.
	 */
	public interface Parameters extends WorkParameters {

		DirectoryProperty getStubs();

		RegularFileProperty getRs3Jar();

		DirectoryProperty getOutput();

		Property<String> getLanguage();

		Property<String> getRootPackage();

		RegularFileProperty getCodePackages();

		DirectoryProperty getLibraries();

		DirectoryProperty getMainLua();

		DirectoryProperty getDevLua();
	}

	@Override
	public void execute() {
		Parameters parameters = getParameters();
		List<?> warnings = (List<?>) Reflection.call("org.adelheid.oneiro.bindings.BindingsGenerator",
				"generateLuaForBuild", path(parameters.getStubs()),
				parameters.getRs3Jar().get().getAsFile().getAbsolutePath(), path(parameters.getOutput()),
				parameters.getLanguage().get(), parameters.getRootPackage().get(),
				parameters.getCodePackages().get().getAsFile().getAbsolutePath(), path(parameters.getLibraries()),
				path(parameters.getMainLua()), path(parameters.getDevLua()));
		for (Object warning : warnings) {
			LOGGER.warn("Oneiro bindings of the plugin's Lua: {}", warning);
		}
	}

	private static String path(DirectoryProperty folder) {
		return folder.get().getAsFile().getAbsolutePath();
	}
}
