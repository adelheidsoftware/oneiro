package plugin;

import java.util.function.Supplier;

/** Makes marks. */
public final class Board {

	private Board() {
	}

	public static Marker mark() {
		return new Marker();
	}

	public static Supplier<Marker> marker() {
		return Marker::new;
	}
}
