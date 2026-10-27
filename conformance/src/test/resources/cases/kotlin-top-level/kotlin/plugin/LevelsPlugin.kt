package plugin

import org.adelheid.oneiro.api.Plugin
import org.adelheid.oneiro.rs3.Globals.log

private const val GREETING = "Levels ready"

/** Logs the greeting. */
private fun greet() {
	log(GREETING)
}

/** The plugin, which greets when it starts. */
class LevelsPlugin : Plugin() {

	override fun startUp() {
		greet()
	}
}
