package plugin;

import static org.adelheid.oneiro.rs3.Globals.log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.adelheid.oneiro.rs3.CoordGrid;
import org.adelheid.oneiro.rs3.Event;
import org.adelheid.oneiro.rs3.GameState;
import org.adelheid.oneiro.rs3.ModifierKey;
import org.adelheid.oneiro.rs3.NPCEntity;
import org.adelheid.oneiro.rs3.NPCList;
import org.adelheid.oneiro.rs3.NPCListFilterSettings;
import org.adelheid.oneiro.rs3.PersistentDB;
import org.adelheid.oneiro.rs3.Player;
import org.adelheid.oneiro.rs3.SkillType;
import org.adelheid.oneiro.rs3.config;
import org.adelheid.oneiro.rs3.id;
import org.adelheid.oneiro.rs3.Vector2;
import org.adelheid.oneiro.rs3.ui;
import org.adelheid.oneiro.rs3.vars;
import org.jspecify.annotations.Nullable;

/** Calls into the RS3 API: global tables, singletons, objects, constants, enums and events. */
public final class Watch {

	private Watch() {
	}

	public static List<String> names(String text) {
		List<String> found = new ArrayList<>();
		for (NPCEntity npc : NPCList.All()) {
			// An entity can leave the view between ticks
			if (npc.isValid && npc.displayName.contains(text)) {
				found.add(npc.displayName);
			}
		}
		return found;
	}

	/**
	 * The NPCs near a tile, other than Hans.
	 *
	 * @param tile where to look
	 */
	public static List<NPCEntity> near(CoordGrid tile) {
		NPCListFilterSettings settings = new NPCListFilterSettings();
		settings.coordGrid = tile;
		settings.range = 5;
		settings.func = npc -> npc.config != config.NPC.RS3_HANS;
		return NPCList.Filter(settings);
	}

	public static int attackOr(int fallback) {
		if (!Player.IsAvailable()) {
			return fallback;
		}
		return Player.GetLevelGameplay(SkillType.ATTACK);
	}

	public static int visits() {
		Integer stored = PersistentDB.GetInt("visits");
		int visits = stored == null ? 1 : stored + 1;
		PersistentDB.SetInt("visits", visits);
		return visits;
	}

	public static String describe(int objectId) {
		return switch (objectId) {
			case id.Obj.RS3_COINS -> "coins";
			case id.Obj.RS3_LOGS -> "logs";
			default -> id.Obj.FromID(objectId);
		};
	}

	public static boolean shifted(int modifiers) {
		return (modifiers & ModifierKey.SHIFT) != 0;
	}

	public static ui.Text label(ui.ComponentCollection parent, CoordGrid tile) {
		ui.Text text = new ui.Text(parent);
		text.content = "Tile " + tile;
		text.SetPos(10, 20);
		return text;
	}

	public static ui.@Nullable Component chatbox() {
		return ui.Interfaces.GetComponent(137, 2);
	}

	/** A hook's callback gets the component, then the hook's own values, which Java checks against the hook. */
	public static void clicks(ui.Text label) {
		label.Subscribe(ui.Hook.ONCLICK, (_, x, y) -> label.content = "Clicked at " + x + ", " + y);
		label.Subscribe(ui.Hook.ONTIMER, "watch.timer", self -> label.hidden = false);
	}

	/** Unnamed parameters at the end are left out, as Lua drops the values a function does not name. */
	public static void clickAnywhere(ui.Text label) {
		label.Subscribe(ui.Hook.ONCLICK, (_, x, _) -> label.content = "Clicked at " + x);
		label.Subscribe(ui.Hook.ONTIMER, "watch.hide", _ -> label.hidden = true);
	}

	/** A callback that returns false stops the listeners after it, as a Lua one may. */
	public static void clicksOnRight(ui.Text label) {
		label.Subscribe(ui.Hook.ONCLICK.stoppable(), (_, x, y) -> x > 10);
	}

	public static void unclick(ui.Text label) {
		label.Unsubscribe(ui.Hook.ONCLICK);
	}

	/** The client's vectors add and scale with Lua's own operators. */
	public static Vector2 nudged(Vector2 position, Vector2 offset) {
		return position.plus(offset).times(2.0);
	}

	public static void watch() {
		Event.GameStateChanged.Subscribe("watch.state", event -> {
			if (event.newState == GameState.GAME) {
				log("Logged in with quest points:", vars.VarPlayer.RS3_QUEST_POINTS.value);
			}
		});
		Event.ServerLogic.Subscribe("watch.tick", tick -> log("tick"));
		Event.EntityDraw.Subscribe("watch.draw",
				event -> event.npcEntity == null || event.npcEntity.config != config.NPC.RS3_MAN);
	}

	/** An API enum's constants are plain Lua values, so they can key a table. */
	public static Map<GameState, String> stateNames() {
		return Map.of(GameState.GAME, "in game");
	}

	/** An API enum's value as text is its name, which the client's FromID gives. */
	public static String stateText(GameState state) {
		return "state " + state + ", " + state.name().toLowerCase();
	}

	/** An array spread over a Lua function's values, as Java spreads it over the parameters. */
	public static void logAll(Object[] values) {
		log("values:", values);
	}

	/** A name that starts with a digit is no Lua name, so Lua indexes the table with it. */
	public static int guardId() {
		return id.NPC.RS3_2ND_GUARD;
	}

	public static void unwatch() {
		Event.GameStateChanged.Unsubscribe("watch.state");
		Event.ServerLogic.Unsubscribe("watch.tick");
		Event.EntityDraw.Unsubscribe("watch.draw");
	}
}
