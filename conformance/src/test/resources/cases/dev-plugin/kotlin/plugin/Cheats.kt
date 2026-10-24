package plugin

import org.adelheid.oneiro.api.DevPlugin
import org.adelheid.oneiro.api.Subscribe
import org.adelheid.oneiro.rs3.Globals.ExecuteConsoleCommand
import org.adelheid.oneiro.rs3.Globals.log
import org.adelheid.oneiro.rs3.ServerLogicEventPayload

/** Development helpers: a teleport when the plugin starts, and the ticks it ran for when it stops. */
class Cheats : DevPlugin() {

	private var ticks = 0

	override fun startUp() {
		ExecuteConsoleCommand("!tele 3058 3311 0")
	}

	override fun shutDown() {
		log("Ticks while the plugin ran:", ticks)
	}

	@Subscribe
	fun onServerLogic(tick: ServerLogicEventPayload) {
		ticks++
	}
}
