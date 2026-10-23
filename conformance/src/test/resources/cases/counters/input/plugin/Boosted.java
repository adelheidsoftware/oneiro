package plugin;

/** Counters whose level can drop below zero. */
public class Boosted extends Counters {

	@Override
	public int level() {
		return super.level() - 5;
	}
}
