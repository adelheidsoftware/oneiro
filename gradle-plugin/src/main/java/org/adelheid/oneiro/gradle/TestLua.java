package org.adelheid.oneiro.gradle;

import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

/**
 * {@code testLua}: runs each {@code *_test.lua} under {@code src/test/lua/} against the plugin as it ships, on the
 * bundled Lua 5.4.8 unless {@code oneiro { lua { executable = file(...) } }} names an installed one. It runs in a
 * worker process, so that the bundled Lua's native library stays out of the Gradle daemon.
 */
@CacheableTask
public abstract class TestLua extends DefaultTask {

	@InputDirectory
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract DirectoryProperty getPluginFolder();

	@InputFiles
	@SkipWhenEmpty
	@IgnoreEmptyDirectories
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getTestFiles();

	@Internal
	public abstract DirectoryProperty getTestsFolder();

	@Optional
	@InputFile
	@PathSensitive(PathSensitivity.ABSOLUTE)
	public abstract RegularFileProperty getLuaExecutable();

	@Classpath
	public abstract ConfigurableFileCollection getToolClasspath();

	@Nested
	public abstract Property<JavaLauncher> getJavaLauncher();

	@Internal
	public abstract DirectoryProperty getWorkFolder();

	/** The outcome of each test file, kept so that the task is up to date while nothing changes. */
	@OutputFile
	public abstract RegularFileProperty getReport();

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@TaskAction
	public void test() {
		WorkQueue queue = getWorkerExecutor().processIsolation(spec -> {
			spec.getClasspath().from(getToolClasspath());
			spec.forkOptions(fork -> {
				fork.setExecutable(getJavaLauncher().get().getExecutablePath().getAsFile().getAbsolutePath());
				// LuaJava loads its native library through System.load, which JDK 25 reports unless it is allowed
				fork.jvmArgs("--enable-native-access=ALL-UNNAMED");
				// LuaJava extracts that library under java.io.tmpdir and looks for it nowhere else, so the worker
				// gets a temporary folder that exists and is writable: this task's own
				fork.systemProperty("java.io.tmpdir", getTemporaryDir().getAbsolutePath());
			});
		});
		queue.submit(TestLuaAction.class, parameters -> {
			parameters.getLuaExecutable().set(getLuaExecutable());
			parameters.getPluginFolder().set(getPluginFolder());
			parameters.getTestsFolder().set(getTestsFolder());
			parameters.getWorkFolder().set(getWorkFolder());
			parameters.getReport().set(getReport());
		});
	}
}
