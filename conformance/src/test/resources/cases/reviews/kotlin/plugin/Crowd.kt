package plugin

import org.adelheid.oneiro.rs3.PlayerList

/** Counts the players in view; the Mod.io description covers it, so the object silences the warning for all of it. */
@Suppress("oneiro:other-players")
internal object Crowd {

	fun size(): Int {
		return PlayerList.All().size
	}
}
