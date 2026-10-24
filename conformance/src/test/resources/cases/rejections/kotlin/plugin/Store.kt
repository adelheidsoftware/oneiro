package plugin

import org.adelheid.oneiro.api.LuaModule

/** A hand-written store, declared with mistakes. */
@LuaModule("src/store")
object Store {

	var limit = 3

	fun count(): Int {
		return 0
	}
}
