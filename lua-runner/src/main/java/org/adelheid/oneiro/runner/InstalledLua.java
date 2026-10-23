package org.adelheid.oneiro.runner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** An installed Lua interpreter the developer named instead of the bundled one, run as a separate process. */
final class InstalledLua implements LuaRunner {

	private final Path executable;

	InstalledLua(Path executable) {
		this.executable = executable;
	}

	@Override
	public Result run(Path script, List<String> arguments) {
		List<String> command = new ArrayList<>();
		command.add(executable.toString());
		command.add(script.toString());
		command.addAll(arguments);
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
			return new Result(process.waitFor() == 0, output);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not run " + executable, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while running " + executable, e);
		}
	}
}
