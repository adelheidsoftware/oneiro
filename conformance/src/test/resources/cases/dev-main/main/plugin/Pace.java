package plugin;

/** Reads the order of speeds, which makes their constants objects. */
public final class Pace {

	private Pace() {
	}

	/** The speed's place in its order. */
	public static int order(Speed speed) {
		return speed.ordinal();
	}
}
