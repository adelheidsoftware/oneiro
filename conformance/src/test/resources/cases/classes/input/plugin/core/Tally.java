package plugin.core;

import java.util.HashSet;
import java.util.Set;

/** A tally of confirmed modules, as a class with instances. */
public class Tally {

	/** Every tally starts here. */
	public static final int START = 0;

	private final Set<Integer> confirmed = new HashSet<>(); // sprite ID -> true
	private int count;
	private boolean locked;
	public String label;

	public Tally(String label) {
		this.label = label;
		count = START;
	}

	/** Confirms a module once; confirming it again changes nothing. */
	public void confirm(int sprite) {
		if (locked || confirmed.contains(sprite)) {
			return;
		}
		confirmed.add(sprite);
		bump();
	}

	public int total() {
		return count;
	}

	public void lock() {
		locked = true;
	}

	private void bump() {
		count++;
	}

	public static Tally named(String label) {
		return new Tally(label);
	}
}
