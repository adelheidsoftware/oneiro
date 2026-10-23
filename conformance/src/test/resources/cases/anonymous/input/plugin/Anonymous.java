package plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Anonymous classes: functions where Java means one, objects otherwise. */
public final class Anonymous {

	private Anonymous() {
	}

	/** Something that hears words and has a name. */
	public interface Listener {
		String heard(String word);

		String name();
	}

	/** A shape whose area each kind gives. */
	public abstract static class Shape {

		protected final String name;

		protected Shape(String name) {
			this.name = name;
		}

		public abstract double area();

		public String describe() {
			return name + " of " + area();
		}
	}

	/** Sorts the words longest first, with an anonymous comparator. */
	public static List<String> longestFirst(List<String> words) {
		List<String> sorted = new ArrayList<>(words);
		sorted.sort(new Comparator<String>() {
			@Override
			public int compare(String a, String b) {
				return b.length() - a.length();
			}
		});
		return sorted;
	}

	/** Runs a task the given number of times, which counts in the list. */
	public static void repeated(int times, List<Integer> counts) {
		Runnable task = new Runnable() {
			@Override
			public void run() {
				counts.add(counts.size());
			}
		};
		for (int i = 0; i < times; i++) {
			task.run();
		}
	}

	/** A listener that answers with the word it heard, and how many it has heard. */
	public static Listener echo(String prefix) {
		return new Listener() {
			private int heardCount = 0;

			@Override
			public String heard(String word) {
				heardCount++;
				return prefix + word + " (" + heardCount + ")";
			}

			@Override
			public String name() {
				return "echo";
			}
		};
	}

	/** The words, each with its length after the separator, through a class of this method's own. */
	public static List<String> counted(List<String> words, String separator) {
		class Count {

			private final String word;

			Count(String word) {
				this.word = word;
			}

			String text() {
				return word + separator + word.length();
			}
		}
		List<String> texts = new ArrayList<>();
		for (String word : words) {
			texts.add(new Count(word).text());
		}
		return texts;
	}

	/** A square of side 1, made on the spot. */
	public static Shape unitSquare() {
		return new Shape("square") {
			@Override
			public double area() {
				return 1.0;
			}
		};
	}
}
