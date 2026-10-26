package plugin

import org.adelheid.oneiro.lib.gauges.gauges

/** An optional library plugin, used where a check that it is loaded reaches. */
object Meters {

	fun show(value: Double) {
		if (gauges.isLoaded()) {
			val gauge = gauges.Gauge("health")
			gauge.SetValue(value)
		}
	}

	fun version(): String {
		if (!gauges.isLoaded()) {
			return "none"
		}
		return gauges.version
	}

	fun present(): Boolean {
		return gauges.isLoaded()
	}

	fun label(wanted: Boolean): String {
		return if (wanted && gauges.isLoaded()) "v" + gauges.version else "-"
	}

	fun isGauges(identifier: String): Boolean {
		return identifier == gauges.ALIAS
	}
}
