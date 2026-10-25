package plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Method references as functions. */
public final class Refs {

	private Refs() {
	}

	/** A seed, with its name. */
	public record Seed(String name) {
	}

	/** A sack that holds what it is given. */
	public static class Sack {

		private final List<String> held = new ArrayList<>();

		public void put(String item) {
			held.add(item);
		}

		public int size() {
			return held.size();
		}
	}

	/** Each name's length, by a library method. */
	public static List<Integer> lengths(List<String> names) {
		return names.stream().map(String::length).toList();
	}

	/** The names that are not empty. */
	public static long filled(List<String> names) {
		return names.stream().filter(Objects::nonNull).filter(name -> !name.isEmpty()).count();
	}

	/** The seeds of the names. */
	public static List<Seed> seeds(List<String> names) {
		return names.stream().map(Seed::new).toList();
	}

	/** The seeds' names, by the record's accessor. */
	public static List<String> names(List<Seed> seeds) {
		return seeds.stream().map(Seed::name).toList();
	}

	/** Puts every item into the sack, and counts them. */
	public static int fill(List<String> items) {
		Sack sack = new Sack();
		items.forEach(sack::put);
		return sack.size();
	}

	/** The numbers in the texts. */
	public static List<Integer> numbers(List<String> texts) {
		return texts.stream().map(Integer::parseInt).toList();
	}

	/** Copies the items into a list, through the list's add. */
	public static List<String> copied(List<String> items) {
		List<String> copy = new ArrayList<>();
		items.forEach(copy::add);
		return copy;
	}

	/** Prints each line. */
	public static void print(List<String> lines) {
		lines.forEach(System.out::println);
	}

	/** The texts in upper case. */
	public static List<String> shouted(List<String> texts) {
		return texts.stream().map(String::toUpperCase).toList();
	}

	/** The long names, collected by the list's add. */
	public static List<String> longNames(List<String> names) {
		List<String> found = new ArrayList<>();
		names.stream().filter(name -> name.length() > 3).forEach(found::add);
		return found;
	}
}
