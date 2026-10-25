package plugin;

import org.adelheid.oneiro.api.LuaColon;
import org.adelheid.oneiro.api.LuaModule;

/** A hand-written Lua store, declared in Java, where its annotations should give its bindings. */
@LuaModule("src/store")
public final class Store {

	private Store() {
	}

	@LuaColon
	public static native int count();
}
