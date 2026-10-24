package plugin;

/** Counts visits; written for threads, which the client's one thread makes no difference to. */
public final class Visits {

	private static volatile int count;

	private Visits() {
	}

	static synchronized int visit() {
		count++;
		return count;
	}
}
