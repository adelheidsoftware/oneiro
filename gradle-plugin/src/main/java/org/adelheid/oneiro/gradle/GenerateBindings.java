package org.adelheid.oneiro.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;
import org.jspecify.annotations.Nullable;

/**
 * {@code oneiroBindings}: the RS3 bindings, generated on this machine from the stubs the build names, as a jar of
 * classes the plugin compiles against and a jar of their sources for the IDE. The generator runs in a worker on JDK
 * 25, because it compiles the generated sources and writes class files with the JDK's ClassFile API.
 */
@CacheableTask
public abstract class GenerateBindings extends DefaultTask {

	/** A local checkout of the stubs. */
	@Optional
	@InputDirectory
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract DirectoryProperty getLocalStubs();

	/** A GitHub repository of the stubs, fetched at {@link #getCommit()}. */
	@Optional
	@Input
	public abstract Property<String> getRemote();

	/** The remote's commit: the pinned one, or the latest of its default branch. */
	@Optional
	@Input
	public abstract Property<String> getCommit();

	/** Where fetched stubs are kept, one folder per commit, in Gradle's user home. */
	@Internal
	public abstract DirectoryProperty getStubsCache();

	@Classpath
	public abstract ConfigurableFileCollection getToolClasspath();

	@Nested
	public abstract Property<JavaLauncher> getJavaLauncher();

	@OutputFile
	public abstract RegularFileProperty getClassesJar();

	@OutputFile
	public abstract RegularFileProperty getSourcesJar();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@TaskAction
	public void generate() {
		Path stubs = stubs();
		WorkQueue queue = getWorkerExecutor().processIsolation(spec -> {
			spec.getClasspath().from(getToolClasspath());
			spec.forkOptions(fork -> {
				fork.setExecutable(getJavaLauncher().get().getExecutablePath().getAsFile().getAbsolutePath());
				fork.setMaxHeapSize("2g");
			});
		});
		File output = getClassesJar().get().getAsFile().getParentFile();
		queue.submit(GenerateBindingsAction.class, parameters -> {
			parameters.getStubs().set(stubs.toFile());
			parameters.getOutput().set(output);
		});
	}

	/** The stubs folder: the local checkout, or the remote commit's files, fetched into the cache when missing. */
	private Path stubs() {
		String remote = getRemote().getOrNull();
		if (getLocalStubs().isPresent()) {
			Path local = getLocalStubs().get().getAsFile().toPath();
			warnIfUntested(RemoteStubs.localCommit(local), local.toString());
			return local;
		}
		if (remote == null) {
			throw new GradleException("""
					Oneiro generates the RS3 bindings from runescape-api-stubs, and fetches nothing the build does not \
					name. Name where the stubs come from:

					    oneiro {
					        stubs {
					            local(file("../runescape-api-stubs"))
					            // or, fetched from GitHub at the commit this Oneiro was tested against:
					            // remote("%s", "%s")
					        }
					    }""".formatted(RemoteStubs.DEFAULT_REMOTE, RemoteStubs.TESTED_COMMIT));
		}
		String commit = getCommit().get();
		getLogger().lifecycle("Oneiro uses runescape-api-stubs from {} at commit {}.", remote, commit);
		warnIfUntested(commit, remote);
		try {
			return RemoteStubs.checkout(RemoteStubs.Repository.parse(remote), commit,
					getStubsCache().get().getAsFile().toPath());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void warnIfUntested(@Nullable String commit, String source) {
		if (commit != null && !commit.equals(RemoteStubs.TESTED_COMMIT)) {
			getLogger().warn("The stubs in {} are at commit {}, but this Oneiro was tested against {}; the overrides and"
					+ " bindings may not match them. Pin the tested commit to be sure.", source, commit,
					RemoteStubs.TESTED_COMMIT);
		}
	}
}
