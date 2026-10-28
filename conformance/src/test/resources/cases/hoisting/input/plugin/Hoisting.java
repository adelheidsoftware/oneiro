package plugin;

import static org.adelheid.oneiro.rs3.Globals.log;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Values Lua reads twice, or makes with statements, read into a local before the statement that uses them. */
public final class Hoisting {

	private Hoisting() {
	}

	/** How a monster feels. */
	public enum Mood { CALM, ANGRY, AFRAID }

	/** Logs how the monster feels: a switch inside a call. */
	public static void report(Mood mood) {
		log("The monster is " + switch (mood) {
			case CALM -> "calm";
			case ANGRY -> "angry";
			case AFRAID -> "afraid";
		});
	}

	private static boolean confirmed(boolean on) {
		return on;
	}

	/** Whether to warn: a conditional of booleans inside a call. */
	public static boolean warn(Mood mood, boolean setting) {
		boolean warning = confirmed(mood == Mood.ANGRY ? true : setting);
		return warning;
	}

	/** How many names are empty, logged: a stream inside a call. */
	public static void empties(List<String> names) {
		log((int) names.stream().filter(name -> name.isEmpty()).count() + " empty");
	}

	/** How many names are long and how many short: two streams in one call. */
	public static void sizes(List<String> names) {
		log((int) names.stream().filter(name -> name.length() > 5).count() + " long, "
				+ (int) names.stream().filter(name -> name.length() <= 5).count() + " short");
	}

	/** Whether the wanted drop dropped, when one is wanted: a stream on the right of &&. */
	public static boolean dropped(List<String> drops, String wanted) {
		return !wanted.isEmpty() && drops.stream().anyMatch(drop -> drop.equals(wanted));
	}

	/** The changes, or "nothing": a conditional with a stream in a branch. */
	public static String changed(List<String> changes) {
		return changes.isEmpty() ? "nothing" : changes.stream().map(change -> change.toUpperCase())
				.collect(Collectors.joining(", "));
	}

	/** Each entry as a line of the ledger. */
	public static List<String> detailed(List<String> entries) {
		return entries.stream().map(entry -> "- " + entry).toList();
	}

	/** A header and then the ledger's lines, a call's list added to another. */
	public static List<String> ledger(List<String> entries, String header) {
		List<String> lines = new ArrayList<>();
		lines.add(header);
		lines.addAll(detailed(entries));
		return lines;
	}

	/** The middle of a text. */
	public static int middle(String text) {
		return text.length() / 2;
	}

	/** The char after a text's middle, at a position worked out once. */
	public static char afterMiddle(String text) {
		return text.charAt(middle(text) + 1);
	}

	/** Adds one to the count at a text's middle, a position worked out once. */
	public static void bump(int[] counts, String text) {
		counts[middle(text)] += 1;
	}
}
