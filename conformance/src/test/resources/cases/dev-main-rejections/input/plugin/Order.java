package plugin;

/** Dev code that would read the order of a main enum whose constants are strings. */
public final class Order {

	private Order() {
	}

	public static int place(SummaryStyle style) {
		return style.ordinal();
	}

	public static boolean before(SummaryStyle a, SummaryStyle b) {
		return a.compareTo(b) < 0;
	}

	public static Object plain() {
		return new Plain();
	}
}
