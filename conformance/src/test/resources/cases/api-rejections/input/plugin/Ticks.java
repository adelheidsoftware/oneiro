package plugin;

import org.adelheid.oneiro.rs3.Event;

/** An event that passes nothing, whose payload a lambda tries to read. */
public final class Ticks {

	private Ticks() {
	}

	public static void watch() {
		Event.ServerLogic.Subscribe("ticks.tick", tick -> System.out.println(tick));
	}
}
