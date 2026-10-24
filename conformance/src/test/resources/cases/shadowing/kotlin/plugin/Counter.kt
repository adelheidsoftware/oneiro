package plugin

import org.adelheid.oneiro.api.Plugin
import org.adelheid.oneiro.rs3.Globals.log

/** Remembers the last count it showed, through a function whose parameter has the property's name. */
class Counter : Plugin() {

	private var shown = 0

	override fun startUp() {
		show(3)
		log("shown", shown)
	}

	private fun show(shown: Int) {
		this.shown = shown
	}
}
