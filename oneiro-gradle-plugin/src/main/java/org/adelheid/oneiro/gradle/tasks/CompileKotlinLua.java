package org.adelheid.oneiro.gradle.tasks;

import java.io.File;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.adelheid.oneiro.gradle.workers.CompileKotlinAction;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.CompileClasspath;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

/**
 * {@code oneiroKotlin} and {@code oneiroDevKotlin}: compiles a plugin written in Kotlin to Lua with Oneiro's Kotlin
 * front end, which runs in a worker process of its own on JDK 25, because the Kotlin Analysis API it runs on brings the
 * IntelliJ platform with it and keeps global state. It reads the same settings the Java path's javac plugin reads, and
 * writes the Lua and its line map where they say. Each problem is reported as kotlinc reports its own.
 */
@CacheableTask
public abstract class CompileKotlinLua extends DefaultTask {

	/** The source set's Kotlin files. */
	@InputFiles
	@SkipWhenEmpty
	@IgnoreEmptyDirectories
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getKotlinSources();

	/** Java files the Kotlin uses, such as the generated {@code Resources} and {@code Urls}, read but not compiled. */
	@InputFiles
	@IgnoreEmptyDirectories
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getJavaSources();

	/** Java files of the plugin's own, which a plugin written in Kotlin must not have. */
	@InputFiles
	@IgnoreEmptyDirectories
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getOwnJavaSources();

	/** What the sources compile against: the RS3 bindings, Oneiro's API, JSpecify and the Kotlin standard library. */
	@CompileClasspath
	public abstract ConfigurableFileCollection getClasspath();

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getSettingsFile();

	@Classpath
	public abstract ConfigurableFileCollection getToolClasspath();

	@Nested
	public abstract Property<JavaLauncher> getJavaLauncher();

	@OutputDirectory
	public abstract DirectoryProperty getLuaOutput();

	@OutputFile
	public abstract RegularFileProperty getLineMap();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@TaskAction
	public void compile() {
		if (!getOwnJavaSources().isEmpty()) {
			throw new GradleException("A plugin is written in one language, and this one has Kotlin sources as well as"
					+ " Java ones: " + getOwnJavaSources().getFiles().stream().map(File::getName).sorted()
							.collect(Collectors.joining(", ")) + ". Write it in one of them.");
		}
		WorkQueue queue = getWorkerExecutor().processIsolation(spec -> {
			spec.getClasspath().from(getToolClasspath());
			spec.forkOptions(fork -> {
				fork.setExecutable(getJavaLauncher().get().getExecutablePath().getAsFile().getAbsolutePath());
				// The Analysis API holds the plugin's sources, the bindings and the JDK in memory while it compiles
				fork.setMaxHeapSize("2g");
				// A compiler has no window; IntelliJ's platform starts AWT threads unless told so
				fork.systemProperty("java.awt.headless", "true");
			});
		});
		queue.submit(CompileKotlinAction.class, parameters -> {
			parameters.getSettingsFile().set(getSettingsFile());
			parameters.getSources().from(getKotlinSources(), getJavaSources());
			parameters.getClasspath().from(getClasspath());
		});
	}
}
