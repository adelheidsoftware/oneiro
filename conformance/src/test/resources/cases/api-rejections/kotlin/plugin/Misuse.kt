package plugin

import org.adelheid.oneiro.rs3.Chat
import org.adelheid.oneiro.rs3.Globals.ExecuteConsoleCommand
import org.adelheid.oneiro.rs3.PersistentDB
import org.adelheid.oneiro.rs3.World

/** Uses of the API, of errors and of return values that Lua cannot follow or the build does not allow. */
object Misuse {

	fun chat() {
		Chat.AddMessage("hello")
	}

	fun cheat() {
		ExecuteConsoleCommand("!tele 3200 3200")
	}

	fun twoCatches(text: String): Int {
		try {
			return text.length
		} catch (e: IllegalArgumentException) {
			return 1
		} catch (e: RuntimeException) {
			return 2
		}
	}

	fun specific(text: String): Int {
		try {
			return text.length
		} catch (e: IllegalStateException) {
			return 0
		}
	}

	fun sometimes(text: String): Int {
		try {
			if (text.isEmpty()) {
				return 0
			}
			PersistentDB.SetString("last", text)
		} catch (e: RuntimeException) {
			return -1
		}
		return 1
	}

	fun leave(texts: List<String>) {
		for (text in texts) {
			try {
				if (text.isEmpty()) {
					break
				}
			} catch (e: RuntimeException) {
				continue
			}
		}
	}

	fun passed(): Int {
		val ground = World.GetGroundHeight(0, 1, 2)
		return kept(ground)
	}

	private fun kept(ground: World.GroundHeight): Int {
		return 0
	}

	fun made(): World.GroundHeight {
		val stored = World.GroundHeight(1, true)
		return stored
	}
}
