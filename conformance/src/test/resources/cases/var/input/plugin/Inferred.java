package plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Locals, loop variables, lambda parameters and resources declared with var, whose types javac infers. */
public final class Inferred {

	private Inferred() {
	}

	/** A reader of a text, which counts when it is closed. */
	public static final class Reader implements AutoCloseable {

		/** How many readers have been closed. */
		public static int closes;

		private final String text;

		public Reader(String text) {
			this.text = text;
		}

		public int size() {
			return text.length();
		}

		@Override
		public void close() {
			closes++;
		}
	}

	public static int sum(List<Integer> values) {
		var total = 0;
		for (var value : values) {
			total += value;
		}
		return total;
	}

	public static int sumBelow(int limit) {
		var total = 0;
		for (var i = 0; i < limit; i++) {
			total += i;
		}
		return total;
	}

	public static int weighted(Map<String, Integer> scores) {
		var total = 0;
		for (var entry : scores.entrySet()) {
			total += entry.getKey().length() * entry.getValue();
		}
		return total;
	}

	// var gives these locals the types ArrayList and HashMap, where a declared type would be List and Map
	public static List<String> longNames(List<String> names) {
		var kept = new ArrayList<String>();
		for (var name : names) {
			if (name.length() > 3) {
				kept.add(name);
			}
		}
		return kept;
	}

	public static Map<String, Integer> lengths(List<String> names) {
		var lengths = new HashMap<String, Integer>();
		for (var name : names) {
			lengths.put(name, name.length());
		}
		return lengths;
	}

	public static int combined(int a, int b) {
		BiFunction<Integer, Integer, Integer> sum = (var x, var y) -> x + y;
		return sum.apply(a, b);
	}

	public static int measured(String text) {
		try (var reader = new Reader(text)) {
			return reader.size();
		}
	}
}
