package org.adelheid.oneiro.runner;

import java.nio.file.Path;
import java.util.List;
import party.iroiro.luajava.Lua;
import party.iroiro.luajava.LuaException;
import party.iroiro.luajava.lua54.Lua54;

/**
 * Lua 5.4.8 inside the JVM, through LuaJava's native builds of the reference implementation, so that running Lua
 * needs nothing installed. Each run gets a fresh Lua state with the standard libraries. {@code print} is redirected
 * into the result, because the native library would otherwise write past Java's own output. A script must not call
 * {@code os.exit}, which would end the JVM.
 */
final class BundledLua implements LuaRunner {

	/** Replaces print with a function that keeps each printed line, for the result. */
	private static final String CAPTURE_PRINT = """
			local lines = {}
			oneiroPrinted = lines
			print = function(...)
			    local parts = table.pack(...)
			    for index = 1, parts.n do
			        parts[index] = tostring(parts[index])
			    end
			    lines[#lines + 1] = table.concat(parts, "\\t", 1, parts.n)
			end
			""";

	@Override
	public Result run(Path script, List<String> arguments) {
		Lua lua = new Lua54();
		try {
			lua.openLibraries();
			lua.createTable(arguments.size(), 1);
			lua.push(script.toString());
			lua.rawSetI(-2, 0);
			for (int index = 0; index < arguments.size(); index++) {
				lua.push(arguments.get(index));
				lua.rawSetI(-2, index + 1);
			}
			lua.setGlobal("arg");
			lua.run(CAPTURE_PRINT);
			try {
				lua.run("dofile(arg[0])");
				return new Result(true, printed(lua));
			} catch (LuaException error) {
				String output = printed(lua);
				return new Result(false, (output.isEmpty() ? "" : output + "\n") + error.getMessage());
			}
		} catch (LuaException setup) {
			return new Result(false, "Lua could not start: " + setup.getMessage());
		} finally {
			lua.close();
		}
	}

	private static String printed(Lua lua) throws LuaException {
		lua.run("oneiroText = table.concat(oneiroPrinted, \"\\n\")");
		lua.getGlobal("oneiroText");
		String text = lua.toString(-1);
		lua.pop(1);
		return text == null ? "" : text;
	}
}
