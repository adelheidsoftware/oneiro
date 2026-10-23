package plugin;

import java.util.List;

/** Switches as if/elseif chains. */
public final class Switches {

	private Switches() {
	}

	// An arrow switch, with several labels on one case and a default
	public static String describe(int code) {
		String text;
		switch (code) {
			// The common case first
			case 1 -> text = "one";
			case 2, 3 -> text = "a few";
			default -> text = "many";
		}
		return text;
	}

	// The classic form: a break ends each case, and empty cases share the statements that follow
	public static int weight(String size) {
		int weight = 0;
		switch (size) {
			case "small":
				weight = 1;
				break;
			case "medium":
			case "large":
				// Both count as big
				weight = 5;
				break;
			default:
				weight = -1;
		}
		return weight;
	}

	// The default first; Lua's else comes last
	public static boolean isWeekend(String day) {
		switch (day) {
			default:
				return false;
			case "sat":
			case "sun":
				return true;
		}
	}

	// A selector with a call is read once, into a local
	public static String amount(List<Integer> values) {
		switch (values.size()) {
			case 0:
				return "none";
			case 1:
				return "one";
			default:
				return "several";
		}
	}

	// A selector Lua computes is read once too, when more than one case compares it
	public static String parity(int value) {
		return switch (value % 2) {
			case 0 -> "even";
			case 1, -1 -> "odd";
			default -> "impossible";
		};
	}

	// A switch expression as the value of a local, with a case that yields from a block
	public static int price(String item, boolean member) {
		int price = switch (item) {
			case "apple" -> 3;
			case "pear" -> {
				int base = 4;
				yield member ? base - 1 : base;
			}
			default -> 0;
		};
		return price;
	}

	// Returned directly
	public static String season(int month) {
		return switch (month) {
			case 12, 1, 2 -> "winter";
			case 3, 4, 5 -> "spring";
			case 6, 7, 8 -> "summer";
			default -> "autumn";
		};
	}

	// Assigned
	public static String label(int level) {
		String label = "unknown";
		label = switch (level) {
			case 0 -> "perfect";
			case 5 -> "poor";
			default -> "level " + level;
		};
		return label;
	}

	// Inside a loop, where continue still jumps to the next value
	public static int sumOdd(List<Integer> values) {
		int sum = 0;
		for (int value : values) {
			switch (value % 2) {
				case 0 -> {
					continue;
				}
				default -> sum += value;
			}
		}
		return sum;
	}
}
