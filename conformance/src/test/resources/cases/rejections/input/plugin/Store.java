package plugin;

import org.adelheid.oneiro.api.binding.LuaColon;
import org.adelheid.oneiro.api.binding.LuaModule;

/** A hand-written Lua store, declared in Java, where its annotations should give its bindings. */
@LuaModule("src/store")
public final class Store {

	private Store() {
	}

	@LuaColon
	public static native int count();
}
