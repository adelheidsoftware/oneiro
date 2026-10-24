package plugin

import org.adelheid.oneiro.api.Plugin
import org.adelheid.oneiro.api.Subscribe
import org.adelheid.oneiro.rs3.ChatMessageEventPayload
import org.adelheid.oneiro.rs3.EntityDrawEventPayload
import org.adelheid.oneiro.rs3.LogicEventPayload
import org.adelheid.oneiro.rs3.ServerLogicEventPayload
import org.adelheid.oneiro.rs3.SettingsLayerReadyEventPayload

/** A plugin class that breaks the rules of the plugin model. */
class BadPlugin : Plugin() {

	private var settings: BadConfig? = null
	private val helper = Helper()

	init {
		helper.count()
	}

	override fun startUp() {
		helper.remember(this)
	}

	@Subscribe
	fun onTwo(event: LogicEventPayload, extra: Int) {
	}

	@Subscribe
	fun onText(text: String) {
	}

	@Subscribe
	fun onDraw(event: EntityDrawEventPayload) {
	}

	@Subscribe
	fun onLogic(event: LogicEventPayload): Boolean {
		return true
	}

	@Subscribe
	fun onChat(event: ChatMessageEventPayload) {
	}

	@Subscribe
	fun onSettings(event: SettingsLayerReadyEventPayload) {
	}

	@Subscribe
	fun onTick(tick: ServerLogicEventPayload) {
		val kept: Any = tick
	}

	fun restart() {
		startUp()
		settings = null
	}
}
