package usage;

import static org.adelheid.oneiro.rs3.Globals.log;

import java.util.List;
import org.adelheid.oneiro.rs3.CoordGrid;
import org.adelheid.oneiro.rs3.CurrentPlugin;
import org.adelheid.oneiro.rs3.Event;
import org.adelheid.oneiro.rs3.GameState;
import org.adelheid.oneiro.rs3.HTTPRequest;
import org.adelheid.oneiro.rs3.Inv;
import org.adelheid.oneiro.rs3.Inventory;
import org.adelheid.oneiro.rs3.ModifierKey;
import org.adelheid.oneiro.rs3.NPCEntity;
import org.adelheid.oneiro.rs3.NPCList;
import org.adelheid.oneiro.rs3.NPCListFilterSettings;
import org.adelheid.oneiro.rs3.PersistentDB;
import org.adelheid.oneiro.rs3.Player;
import org.adelheid.oneiro.rs3.PluginPermissions;
import org.adelheid.oneiro.rs3.SkillType;
import org.adelheid.oneiro.rs3.Vector2;
import org.adelheid.oneiro.rs3.World;
import org.adelheid.oneiro.rs3.config;
import org.adelheid.oneiro.rs3.id;
import org.adelheid.oneiro.rs3.ui;
import org.adelheid.oneiro.rs3.vars;

/** Uses each shape of the bindings generated from the fixture stubs; it only has to compile. */
final class Usage {

	private Usage() {
	}

	static void all(ui.ComponentCollection parent) {
		boolean available = Player.IsAvailable();
		Long tracking = Player.GetTrackingID();
		int attack = Player.GetLevelGameplay(SkillType.ATTACK);
		Boolean enabled = PersistentDB.GetBool("enabled");
		PersistentDB.SetInt("count", 3);
		PersistentDB.Set("anything", 2.5);
		PersistentDB.IterateEntries((key, value) -> false);
		CoordGrid tile = new CoordGrid(0, 3200, 3200);
		int packed = tile.ToPacked();
		CoordGrid next = tile.Offset(1, 0);
		ui.Text text = new ui.Text(parent);
		text.content = "Hello";
		text.SetPos(10, 20);
		ui.Component found = ui.Interfaces.GetComponent(1477, 2);
		int coins = id.Obj.RS3_COINS;
		String name = id.Obj.FromID(coins);
		var hans = config.NPC.RS3_HANS;
		// A name that starts with a digit is a Java name after the prefix
		int guard = id.NPC.RS3_2ND_GUARD;
		var bank = config.Inv.RS3_BANK;
		var points = vars.VarPlayer.RS3_QUEST_POINTS;
		Inv inventory = Inventory.Find(config.Inv.RS3_INV);
		int logs = inventory == null ? 0 : inventory.GetTotal(config.Obj.RS3_LOGS);
		switch (coins) {
			case id.Obj.RS3_COINS -> log("coins");
			case id.Obj.RS3_LOGS -> log("logs", logs);
			default -> log("other");
		}
		int shift = ModifierKey.SHIFT;
		List<NPCEntity> npcs = NPCList.All();
		NPCListFilterSettings settings = new NPCListFilterSettings();
		settings.range = 5;
		settings.func = npc -> npc.displayName.equals("Hans");
		List<NPCEntity> near = NPCList.Filter(settings);
		Integer headbar = npcs.getFirst().GetHeadbar();
		World.GroundHeight ground = World.GetGroundHeight(0, 100, 100);
		int height = ground.height();
		World.RemoveRoof.Add("house", tile);
		Event.ServerLogic.Subscribe("usage.tick", payload -> log("tick"));
		Event.InventoryUpdated.Subscribe("usage.inventory", payload -> log("slots", payload.slotsUpdated.size()));
		Event.EntityDraw.Subscribe("usage.draw", payload -> payload.npcEntity == null);
		Event.GameStateChanged.Subscribe("usage.state", payload -> {
			if (payload.newState == GameState.GAME) {
				log("in game");
			}
		});
		Event.Logic.Unsubscribe("usage.logic");
		if (CurrentPlugin.HasPermission(PluginPermissions.httpAccess)) {
			new HTTPRequest("https://example.org").Get(request -> log(request.data));
		}
		Vector2 one = new Vector2(1.0);
		double length = new Vector2(3.0, 4.0).Length();
		log("all", available, tracking, attack, enabled, packed, next, found, name, hans, bank, points, shift, near,
				headbar, height, one, length);
	}
}
