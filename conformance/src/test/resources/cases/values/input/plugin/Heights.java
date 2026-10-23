package plugin;

import java.util.List;
import org.adelheid.oneiro.api.LuaReturnValues;
import org.adelheid.oneiro.rs3.World;

/** Several values returned from Lua functions, and returned to Lua. */
public final class Heights {

	/** The smallest and the largest of some values. */
	@LuaReturnValues
	public record Span(int low, int high) {
	}

	private Heights() {
	}

	/**
	 * The height of the ground, or the fallback where the world has none.
	 *
	 * @param fallback the height to use instead
	 */
	public static int groundOr(int x, int z, int fallback) {
		var ground = World.GetGroundHeight(0, x, z);
		if (!ground.available()) {
			return fallback;
		}
		return ground.height();
	}

	public static int lowest(int x, int z) {
		var here = World.GetGroundHeight(0, x, z);
		var east = World.GetGroundHeight(0, x + 512, z);
		return Math.min(here.height(), east.height());
	}

	/** The span of the values. */
	public static Span span(List<Integer> values) {
		int low = values.get(0);
		int high = low;
		for (int value : values) {
			low = Math.min(low, value);
			high = Math.max(high, value);
		}
		return new Span(low, high);
	}

	public static int width(List<Integer> values) {
		var span = span(values);
		return span.high() - span.low();
	}

	public static Span spanOfAll(List<Integer> values) {
		return span(values);
	}

	public static Span checkedSpan(List<Integer> values) {
		var span = span(values);
		if (span.low() < 0) {
			throw new IllegalArgumentException("negative values");
		}
		return span;
	}

	public static String report(List<Integer> values) {
		var summary = Stats.summarise(values);
		return summary.count() + " values, mean " + summary.mean();
	}

	public static int range(List<Integer> values) {
		var extremes = Stats.extremes(values);
		return extremes.most() - extremes.least();
	}
}
