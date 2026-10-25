package org.adelheid.oneiro.gradle;

import java.io.File;
import java.nio.file.Path;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/**
 * {@code oneiroBindings}: the RS3 bindings, generated on this machine from the stubs the build names, as a jar of
 * classes the plugin compiles against and a jar of their sources for the IDE, and the facts of the client's members
 * that {@code checkPlugin} looks for in the Lua that ships.
 */
@CacheableTask
public abstract class GenerateBindings extends StubsTask {

	@OutputFile
	public abstract RegularFileProperty getClassesJar();

	@OutputFile
	public abstract RegularFileProperty getSourcesJar();

	/** The members that need a permission or are for development only, which {@code checkPlugin} reads. */
	@OutputFile
	public abstract RegularFileProperty getClientFacts();

	@TaskAction
	public void generate() {
		Path stubs = stubs(true);
		File output = getClassesJar().get().getAsFile().getParentFile();
		generatorQueue().submit(GenerateBindingsAction.class, parameters -> {
			parameters.getStubs().set(stubs.toFile());
			parameters.getOutput().set(output);
			parameters.getLanguage().set(getLanguage());
		});
	}
}
