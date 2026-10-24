package plugin;

import org.adelheid.oneiro.rs3.PlayerList;

/** Counts the players in view; the Mod.io description covers it, so the class silences the warning for all of it. */
@SuppressWarnings("oneiro:other-players")
final class Crowd {

	private Crowd() {
	}

	static int size() {
		return PlayerList.All().size();
	}
}
