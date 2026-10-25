package plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Lambdas, functional interfaces, method references and generics. */
public final class Functions {

	/** A check the plugin defines itself. */
	public interface Check {
		boolean test(int value);
	}

	/** Where values go, one at a time. */
	public interface Sink {
		void take(int value);
	}

	private Functions() {
	}

	public static <T> List<T> keep(List<T> items, Predicate<T> keep) {
		List<T> kept = new ArrayList<>();
		for (T item : items) {
			if (keep.test(item)) {
				kept.add(item);
			}
		}
		return kept;
	}

	public static int matching(List<Integer> values, Check check) {
		int matching = 0;
		for (int value : values) {
			if (check.test(value)) {
				matching++;
			}
		}
		return matching;
	}

	public static boolean isEven(int value) {
		return value % 2 == 0;
	}

	public static List<Integer> evens(List<Integer> values) {
		return keep(values, value -> value % 2 == 0);
	}

	public static int evenCount(List<Integer> values) {
		return matching(values, Functions::isEven);
	}

	public static int applied(int start) {
		Function<Integer, Integer> twice = x -> x * 2;
		BiFunction<Integer, Integer, Integer> sum = (a, b) -> {
			int total = a + b;
			return total;
		};
		Supplier<String> name = () -> "functions";
		Runnable nothing = () -> {
		};
		nothing.run();
		return sum.apply(twice.apply(start), name.get().length());
	}

	public static void each(List<Integer> values, Sink sink) {
		for (int value : values) {
			sink.take(value);
		}
	}

	public static List<Integer> collected(List<Integer> values) {
		List<Integer> found = new ArrayList<>();
		each(values, found::add);
		return found;
	}
}
