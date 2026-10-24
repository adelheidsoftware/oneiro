package plugin

import org.adelheid.oneiro.api.Plugin
import org.adelheid.oneiro.api.Subscribe
import org.adelheid.oneiro.rs3.EntityDrawEventPayload
import org.adelheid.oneiro.rs3.Globals.log
import org.adelheid.oneiro.rs3.InventoryUpdatedEventPayload
import org.adelheid.oneiro.rs3.ServerLogicEventPayload
import org.adelheid.oneiro.rs3.config

private const val REPORT_EVERY = 100

/** Counts game ticks and backpack changes while the plugin runs. */
class HerbPlugin : Plugin() {

	private lateinit var settings: HerbConfig
	private val tally = Tally()
	private var ticks = 0
	private var nextReport = REPORT_EVERY

	override fun startUp() {
		// The greeting is read once, when the plugin starts
		if (settings.greet()) {
			log("Herb tracker started")
		}
	}

	override fun shutDown() {
		settings.setRuns(settings.runs() + 1)
	}

	private fun report() {
		log("Ticks:", ticks, "backpack changes:", tally.sum())
	}

	@Subscribe
	fun onServerLogic(tick: ServerLogicEventPayload) {
		ticks++
		if (ticks == nextReport) {
			report()
			nextReport += REPORT_EVERY
		}
	}

	@Subscribe
	fun onInventoryUpdated(event: InventoryUpdatedEventPayload) {
		if (event.inv == config.Inv.RS3_INV) {
			tally.add(event.slotsUpdated.size)
		}
	}

	@Subscribe
	fun onEntityDraw(event: EntityDrawEventPayload): Boolean {
		val npc = event.npcEntity
		return npc == null || !settings.hideHans() || npc.config != config.NPC.RS3_HANS
	}
}
