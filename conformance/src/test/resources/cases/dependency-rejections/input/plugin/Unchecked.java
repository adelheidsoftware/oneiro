package plugin;

import java.util.List;
import java.util.function.BooleanSupplier;
import org.adelheid.oneiro.lib.gauges.gauges;

/** Uses of an optional library plugin that no check reaches. */
public final class Unchecked {

	private Unchecked() {
	}

	public static String version() {
		return gauges.version;
	}

	public static void afterTheCheck(double value) {
		if (gauges.isLoaded()) {
			value = value * 2;
		}
		new gauges.Gauge("health").SetValue(value);
	}

	public static void later(List<Double> values) {
		if (!gauges.isLoaded()) {
			return;
		}
		values.forEach(value -> new gauges.Gauge("health").SetValue(value));
	}

	public static String otherwise() {
		return gauges.isLoaded() ? "loaded" : gauges.version;
	}

	public static BooleanSupplier check() {
		return gauges::isLoaded;
	}
}
