package org.adelheid.oneiro.gradle.tasks;

import java.nio.file.Path;
import javax.inject.Inject;
import org.adelheid.rspc.gradle.StubsSource;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

/**
 * A task that runs Oneiro's bindings generator on the stubs the build names: {@code oneiroBindings}, which writes the
 * RS3 bindings, and {@code oneiroLuaBindings}, which writes the bindings of the plugin's own Lua and reads the stubs
 * for the types that Lua names. The generator runs in a worker on JDK 25, because it compiles the generated sources
 * and writes class files with the JDK's ClassFile API.
 */
@DisableCachingByDefault(because = "The tasks that extend it, which say what they write, are cacheable.")
public abstract class StubsTask extends DefaultTask {

	/** The stubs commit this release of Oneiro was tested against; another builds with a warning. */
	static final String TESTED_COMMIT = "9f151d069fec71348ec19d7f87722d8ff7178e9d";

	/** A local checkout of the stubs. */
	@Optional
	@InputDirectory
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract DirectoryProperty getLocalStubs();

	/** A repository of the stubs on GitHub, Codeberg or GitLab, fetched at {@link #getCommit()}. */
	@Optional
	@Input
	public abstract Property<String> getRemote();

	/** The remote's commit: the pinned one, or the latest of its default branch. */
	@Optional
	@Input
	public abstract Property<String> getCommit();

	/**
	 * The plugin's language, {@code JAVA} or {@code KOTLIN}, which decides how a member that takes a hook types its
	 * callback: Kotlin's bindings take a callback of each kind, so that it can be a trailing lambda.
	 */
	@Input
	public abstract Property<String> getLanguage();

	/** Where fetched repositories are kept, one folder per commit, in Gradle's user home. */
	@Internal
	public abstract DirectoryProperty getStubsCache();

	@Classpath
	public abstract ConfigurableFileCollection getToolClasspath();

	@Nested
	public abstract Property<JavaLauncher> getJavaLauncher();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	/** The worker the generator runs in, on JDK 25. */
	WorkQueue generatorQueue() {
		return getWorkerExecutor().processIsolation(spec -> {
			spec.getClasspath().from(getToolClasspath());
			spec.forkOptions(fork -> {
				fork.setExecutable(getJavaLauncher().get().getExecutablePath().getAsFile().getAbsolutePath());
				fork.setMaxHeapSize("2g");
			});
		});
	}

	/**
	 * The stubs folder: the local checkout, or the remote commit's files, fetched into the cache when missing.
	 *
	 * @param announce whether to say which stubs are used, and warn when they are not those this Oneiro was tested
	 *     against: once a build, by the task that writes the RS3 bindings
	 */
	Path stubs(boolean announce) {
		return StubsSource.resolve(
				getLocalStubs().isPresent() ? getLocalStubs().get().getAsFile().toPath() : null,
				getRemote().getOrNull(), getCommit().getOrNull(), getStubsCache().get().getAsFile().toPath(),
				"oneiro", TESTED_COMMIT, announce ? getLogger() : null);
	}
}
