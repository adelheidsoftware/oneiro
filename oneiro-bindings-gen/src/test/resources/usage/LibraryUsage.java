package usage;

import java.util.List;
import org.adelheid.oneiro.lib.gauges.gauges;
import org.adelheid.oneiro.lib.widgets.widgets;
import org.adelheid.oneiro.rs3.ui;

/** Uses each shape of the bindings generated from the library fixtures; it only has to compile. */
final class LibraryUsage {

	private LibraryUsage() {
	}

	static void all(ui.Component parent) {
		// A module's tables, reached as Lua reaches them
		widgets.Counter counter = new widgets.Counter(3);
		int count = counter.increment() + counter.increment(2);
		widgets.Counter same = counter.advance(1).advance(2);
		widgets.LoudCounter loud = new widgets.LoudCounter();
		widgets.Counter advanced = loud.advance(1);
		String trimmed = widgets.text.trim(" x ");
		String found = widgets.text.find(List.of("a", "b"), value -> value.startsWith("b"));
		String joined = widgets.text.join(List.of(1, 2), (value, index, last) -> value + (last ? "" : ","));
		widgets.text.MeasureResult measured = widgets.text.measure("line");
		int width = measured.width();
		boolean cut = measured.cut();
		widgets.text.paint("WHITE");
		int white = widgets.text.COLOURS.WHITE;
		String align = widgets.text.align;

		// A library plugin's global, whose types name the RS3 API's
		gauges.GaugeOptions options = new gauges.GaugeOptions();
		options.width = 100;
		options.onChange = value -> { };
		gauges.Gauge gauge = new gauges.Gauge(parent, options);
		gauge.SetValue(0.5);
		gauges.Gauge highlighted = gauge.Highlight(0xFF0000);
		gauge.Follow(counter);
		gauge.Destroy();
		gauges.Gauge answer = gauges.Prompt.create(parent);
		String version = gauges.version;
	}
}
