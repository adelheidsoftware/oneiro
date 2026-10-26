package plugin;

import org.adelheid.oneiro.lib.gauges.gauges;

/** An optional library plugin, used where a check that it is loaded reaches. */
public final class Meters {

	private Meters() {
	}

	public static void show(double value) {
		if (gauges.isLoaded()) {
			gauges.Gauge gauge = new gauges.Gauge("health");
			gauge.SetValue(value);
		}
	}

	public static String version() {
		if (!gauges.isLoaded()) {
			return "none";
		}
		return gauges.version;
	}

	public static boolean present() {
		return gauges.isLoaded();
	}

	public static String label(boolean wanted) {
		return wanted && gauges.isLoaded() ? "v" + gauges.version : "-";
	}

	public static boolean isGauges(String identifier) {
		return identifier.equals(gauges.ALIAS);
	}
}
