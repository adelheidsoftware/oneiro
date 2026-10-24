package plugin

import org.adelheid.oneiro.api.ConfigGroup
import org.adelheid.oneiro.api.ConfigItem

/** The herb tracker's settings. */
@ConfigGroup("herbs")
interface HerbConfig {

	@ConfigItem(keyName = "greet", name = "Greeting", description = "Logs a line when the plugin starts")
	fun greet(): Boolean = true

	@ConfigItem(keyName = "hideHans", name = "Hide Hans")
	fun hideHans(): Boolean = false

	@ConfigItem(keyName = "runs", name = "Runs", description = "How many times the plugin has stopped")
	fun runs(): Int = 0

	@ConfigItem(keyName = "runs", name = "Runs", description = "How many times the plugin has stopped")
	fun setRuns(runs: Int)

	/** What the tracker reports each time. */
	@ConfigItem(keyName = "mode", name = "Mode")
	fun mode(): Mode = Mode.COUNT

	@ConfigItem(keyName = "mode", name = "Mode")
	fun setMode(mode: Mode)
}
