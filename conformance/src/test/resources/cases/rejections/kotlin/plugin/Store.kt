package plugin

import org.adelheid.oneiro.api.LuaColon
import org.adelheid.oneiro.api.LuaModule

/** A hand-written Lua store, declared in Kotlin, where its annotations should give its bindings. */
@LuaModule("src/store")
object Store {

	val limit: Int
		external get

	@LuaColon
	external fun count(): Int
}
