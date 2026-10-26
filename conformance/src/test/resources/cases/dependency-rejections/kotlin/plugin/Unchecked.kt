package plugin

import org.adelheid.oneiro.lib.gauges.gauges

/** Uses of an optional library plugin that no check reaches. */
object Unchecked {

	fun version(): String {
		return gauges.version
	}

	fun afterTheCheck(value: Double) {
		var shown = value
		if (gauges.isLoaded()) {
			shown *= 2
		}
		gauges.Gauge("health").SetValue(shown)
	}

	fun later(values: List<Double>) {
		if (!gauges.isLoaded()) {
			return
		}
		values.forEach { gauges.Gauge("health").SetValue(it) }
	}

	fun otherwise(): String {
		return if (gauges.isLoaded()) "loaded" else gauges.version
	}

	fun check(): () -> Boolean {
		return gauges::isLoaded
	}
}
