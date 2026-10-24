package plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Gaps between Java and Lua, each rejected with what to write instead. */
public final class Gaps {

	/** A point as a plain table. */
	public record Point(int x, int y) {
	}

	/** An interface that classes implement. */
	public interface Area {
		double area();
	}

	/** One class that implements it. */
	public static final class Unit implements Area {
		@Override
		public double area() {
			return 1.0;
		}

		private static int helper() {
			return 1;
		}
	}

	/** Another class with a private member of the same name. */
	public static final class Other {
		private static int helper() {
			return 2;
		}
	}

	private Gaps() {
	}

	public static int fallThrough(int value) {
		int count = 0;
		switch (value) {
			case 1:
				count++;
			case 2:
				count++;
				break;
			default:
				count = -1;
		}
		return count;
	}

	public static int leaveEarly(int value, boolean skip) {
		int count = 0;
		switch (value) {
			case 1:
				if (skip) {
					break;
				}
				count++;
				break;
			default:
				count = -1;
		}
		return count;
	}

	public static int inside(int value) {
		return 1 + switch (value) {
			case 1 -> 10;
			default -> 20;
		};
	}

	public static String kind(Object value) {
		return value.getClass().getSimpleName();
	}

	public static int words(String text) {
		return text.split("\\s+").length;
	}

	public static boolean both(boolean ready, List<Integer> values) {
		return ready & values.isEmpty();
	}

	public static int shown(boolean flag, @Nullable String name) {
		return (flag ? name : "none").length();
	}

	public static int replaced(List<Integer> values) {
		int old = values.set(0, 1);
		return old;
	}

	public static Map<Point, Integer> keyed() {
		Map<Point, Integer> map = new HashMap<>();
		map.put(new Point(1, 2), 3);
		return map;
	}

	public static void nulls(List<String> names) {
		names.add(null);
	}

	public static int search(List<List<Integer>> rows) {
		int found = 0;
		rows:
		for (List<Integer> row : rows) {
			for (int value : row) {
				if (value < 0) {
					continue rows;
				}
				found++;
			}
		}
		return found;
	}

	public static Area area() {
		return () -> 2.0;
	}

	public static String general(double value) {
		return String.format("%g", value);
	}

	public static String grouped(int value) {
		return String.format("%,d", value);
	}

	public static int hashed(Point point) {
		return point.hashCode();
	}

	public static String dated(long millis) {
		return String.format("%tF", millis);
	}

	public static String nullness(@Nullable String name) {
		return String.format("%b", name);
	}

	public static void letters(String text) {
		text.chars();
	}

	public static int point(String text) {
		return text.codePointAt(0);
	}

	public static char afterBlank(String text) {
		return text.charAt(text.indexOf(" ") + 1);
	}

	public static int codes(char[] letters) {
		int total = 0;
		for (int code : letters) {
			total += code;
		}
		return total;
	}

	public static int accent() {
		return 'é' - 'a';
	}

	public static int mapped(Optional<String> name) {
		return name.map(String::length).orElse(0);
	}

	public static List<String> unique(List<String> names) {
		return names.stream().distinct().toList();
	}

	public static long counted(List<String> names) {
		return 1 + names.stream().count();
	}

	public static List<String> sortedFirst(List<String> names) {
		return names.stream().sorted().filter(name -> !name.isEmpty()).toList();
	}

	/** Herbs that a for loop could walk. */
	public abstract static class Basket implements Iterable<String> {
	}

	/** A step that says what it is, which no class implements. */
	public interface Step {
		void run();

		default String describe() {
			return "a step";
		}
	}

	public static String stepName(Step step) {
		return step.describe();
	}

	/** A class that extends a library class. */
	public static class Names extends ArrayList<String> {
	}

	public static int early(int value) {
		return switch (value) {
			case 1 -> {
				if (value > 0) {
					yield 2;
				}
				yield 3;
			}
			default -> 0;
		};
	}

	public static boolean digits(String text) {
		return text.matches("\\d+");
	}
}
