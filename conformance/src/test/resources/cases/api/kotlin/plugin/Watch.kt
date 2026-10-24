package plugin

import java.util.function.Predicate
import org.adelheid.oneiro.rs3.CoordGrid
import org.adelheid.oneiro.rs3.Event
import org.adelheid.oneiro.rs3.GameState
import org.adelheid.oneiro.rs3.Globals.log
import org.adelheid.oneiro.rs3.ModifierKey
import org.adelheid.oneiro.rs3.NPCEntity
import org.adelheid.oneiro.rs3.NPCList
import org.adelheid.oneiro.rs3.NPCListFilterSettings
import org.adelheid.oneiro.rs3.PersistentDB
import org.adelheid.oneiro.rs3.Player
import org.adelheid.oneiro.rs3.SkillType
import org.adelheid.oneiro.rs3.Vector2
import org.adelheid.oneiro.rs3.config
import org.adelheid.oneiro.rs3.id
import org.adelheid.oneiro.rs3.ui
import org.adelheid.oneiro.rs3.vars

/** Calls into the RS3 API: global tables, singletons, objects, constants, enums and events. */
object Watch {

	fun names(text: String): List<String> {
		val found = ArrayList<String>()
		for (npc in NPCList.All()) {
			// An entity can leave the view between ticks
			if (npc.isValid && npc.displayName.contains(text)) {
				found.add(npc.displayName)
			}
		}
		return found
	}

	/**
	 * The NPCs near a tile, other than Hans.
	 *
	 * @param tile where to look
	 */
	fun near(tile: CoordGrid): List<NPCEntity> {
		val settings = NPCListFilterSettings()
		settings.coordGrid = tile
		settings.range = 5
		settings.func = Predicate { npc -> npc.config != config.NPC.RS3_HANS }
		return NPCList.Filter(settings)
	}

	fun attackOr(fallback: Int): Int {
		if (!Player.IsAvailable()) {
			return fallback
		}
		return Player.GetLevelGameplay(SkillType.ATTACK)
	}

	fun visits(): Int {
		val visits = (PersistentDB.GetInt("visits") ?: 0) + 1
		PersistentDB.SetInt("visits", visits)
		return visits
	}

	fun describe(objectId: Int): String {
		return when (objectId) {
			id.Obj.RS3_COINS -> "coins"
			id.Obj.RS3_LOGS -> "logs"
			else -> id.Obj.FromID(objectId)
		}
	}

	fun shifted(modifiers: Int): Boolean {
		return (modifiers and ModifierKey.SHIFT) != 0
	}

	fun label(parent: ui.ComponentCollection, tile: CoordGrid): ui.Text {
		val text = ui.Text(parent)
		text.content = "Tile $tile"
		text.SetPos(10, 20)
		return text
	}

	fun chatbox(): ui.Component? {
		return ui.Interfaces.GetComponent(137, 2)
	}

	/** A hook's callback gets the component, then the hook's own values, which Kotlin checks against the hook. */
	fun clicks(label: ui.Text) {
		label.Subscribe(ui.Hook.ONCLICK) { _, x, y -> label.content = "Clicked at $x, $y" }
		label.Subscribe(ui.Hook.ONTIMER, "watch.timer") { self -> label.hidden = false }
	}

	/** Unnamed parameters at the end are left out, as Lua drops the values a function does not name. */
	fun clickAnywhere(label: ui.Text) {
		label.Subscribe(ui.Hook.ONCLICK) { _, x, _ -> label.content = "Clicked at $x" }
		label.Subscribe(ui.Hook.ONTIMER, "watch.hide") { _ -> label.hidden = true }
	}

	/** A callback that returns false stops the listeners after it, as a Lua one may. */
	fun clicksOnRight(label: ui.Text) {
		label.Subscribe(ui.Hook.ONCLICK.stoppable()) { _, x, y -> x > 10 }
	}

	fun unclick(label: ui.Text) {
		label.Unsubscribe(ui.Hook.ONCLICK)
	}

	/** The client's vectors add and scale with Lua's own operators. */
	fun nudged(position: Vector2, offset: Vector2): Vector2 {
		return (position + offset) * 2.0
	}

	fun watch() {
		Event.GameStateChanged.Subscribe("watch.state") { event ->
			if (event.newState == GameState.GAME) {
				log("Logged in with quest points:", vars.VarPlayer.RS3_QUEST_POINTS.value)
			}
		}
		Event.ServerLogic.Subscribe("watch.tick") { log("tick") }
		Event.EntityDraw.Subscribe("watch.draw") { event ->
			event.npcEntity?.config != config.NPC.RS3_MAN
		}
	}

	/** An API enum's constants are plain Lua values, so they can key a table. */
	fun stateNames(): Map<GameState, String> {
		return mapOf(GameState.GAME to "in game")
	}

	/** An API enum's value as text is its name, which the client's FromID gives. */
	fun stateText(state: GameState): String {
		return "state $state, ${state.name.lowercase()}"
	}

	/** An array spread over a Lua function's values, as Kotlin spreads it over the parameters. */
	fun logAll(values: Array<Any?>) {
		log("values:", *values)
	}

	/** A name that starts with a digit is no Lua name, so Lua indexes the table with it. */
	fun guardId(): Int {
		return id.NPC.RS3_2ND_GUARD
	}

	fun unwatch() {
		Event.GameStateChanged.Unsubscribe("watch.state")
		Event.ServerLogic.Unsubscribe("watch.tick")
		Event.EntityDraw.Unsubscribe("watch.draw")
	}
}
