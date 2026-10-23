package plugin;

import org.adelheid.oneiro.api.LuaModule;

/** A hand-written store, declared with mistakes. */
@LuaModule("src/store")
public final class Store {

	public static int LIMIT = 3;

	public static int count() {
		return 0;
	}
}
