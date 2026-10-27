package plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Locals named like the file's private members, which share one namespace with them in Lua. */
public final class Walker {

	private static int total = 10;

	private Walker() {
	}

	/** The steps from n down to 1. */
	public static int countDown(int n) {
		return step(n);
	}

	private static int step(int n) {
		int step = n - 1;
		if (step <= 0) {
			return 0;
		}
		return 1 + step(step);
	}

	/** The amount added to the running total, which this method reads by its qualified name. */
	public static int plusTotal(int amount) {
		int total = amount;
		return total + Walker.total;
	}

	/** Twice the steps; a parameter named like a private method this method does not use keeps its name. */
	public static int doubled(int step) {
		return step * 2;
	}

	private static int sum(int a, int b) {
		return a + b;
	}

	/** Twice each value, added up, and the sum of one and two. */
	public static int doubledSum(List<Integer> values) {
		int doubled = values.stream().mapToInt(value -> value * 2).sum();
		return doubled + sum(1, 2);
	}

	/** For each factor, the values times it, then the values as they are. */
	public static List<List<Integer>> scaled(List<Integer> list, List<Integer> factors) {
		List<List<Integer>> out = new ArrayList<>();
		factors.forEach(factor -> {
			List<Integer> times = list.stream().map(value -> value * factor).toList();
			out.add(times);
			out.add(list);
		});
		return out;
	}

	/** How many values are below the number of keys. */
	public static long belowKeys(List<Integer> values, Map<String, Integer> keys) {
		return values.stream().filter(value -> value < keys.size()).count();
	}
}
