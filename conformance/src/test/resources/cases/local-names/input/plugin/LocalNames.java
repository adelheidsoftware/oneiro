package plugin;

import static org.adelheid.oneiro.rs3.Globals.log;

import java.util.List;

/** The locals Oneiro names: each name once in a function, none a global's, a list's after where it goes. */
public final class LocalNames {

	private LocalNames() {
	}

	/** Lines of text a section keeps. */
	public static final class Sections {

		private List<String> lines = List.of();

		/** Keeps the names in capitals, a list built for a field. */
		public void keep(List<String> names) {
			lines = names.stream().map(name -> name.toUpperCase()).toList();
		}
	}

	/** Shows a titled list of texts. */
	public static void show(String title, List<String> texts) {
		log(title, texts.size());
	}

	/** How many groups have more than one big value: a count inside a count's lambda. */
	public static long busyGroups(List<List<Integer>> groups) {
		return groups.stream().filter(group -> group.stream().filter(value -> value > 1).count() > 1).count();
	}

	/** The names shown twice: a list handed to a parameter, named after it, and then another. */
	public static void showBoth(List<String> names) {
		show("loud", names.stream().map(name -> name.toUpperCase()).toList());
		show("quiet", names.stream().map(name -> name.toLowerCase()).toList());
	}

	/** How many names are empty, and how many long, beside a local of its own named count. */
	public static String counts(List<String> names) {
		int count = names.size();
		return count + ": " + (int) names.stream().filter(name -> name.isEmpty()).count() + " empty, "
				+ (int) names.stream().filter(name -> name.length() > 3).count() + " long";
	}

	/** A value's kind in words, logged, and whether it is text: a switch's local beside the log() it would hide. */
	public static String describe(Object value) {
		Kinds.look(value);
		String described;
		switch (Kinds.log()) {
			case "number" -> described = "a number";
			case "other" -> described = "something";
			default -> described = "unknown";
		}
		log(described);
		return value instanceof String ? described + ", text" : described;
	}
}
