package org.adelheid.oneiro.runner;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Runs a Lua 5.4 script: by default with the Lua 5.4.8 that ships with Oneiro, the version the RS3 client embeds, or
 * with an interpreter the developer has installed and names instead.
 */
public interface LuaRunner {

	/**
	 * Runs a script with its arguments in Lua's {@code arg} table.
	 *
	 * @param script the script; {@code arg[0]} in Lua
	 * @param arguments {@code arg[1]} onwards
	 */
	Result run(Path script, List<String> arguments);

	/**
	 * How a run ended.
	 *
	 * @param succeeded whether the script ran to its end without an error
	 * @param output what the script printed, followed by the error when it failed
	 */
	record Result(boolean succeeded, String output) {
	}

	/** The bundled Lua 5.4.8, unless an installed interpreter is named. */
	static LuaRunner of(@Nullable Path installedExecutable) {
		return installedExecutable == null ? new BundledLua() : new InstalledLua(installedExecutable);
	}
}
