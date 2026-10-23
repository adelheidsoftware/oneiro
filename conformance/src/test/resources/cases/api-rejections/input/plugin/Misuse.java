package plugin;

import static org.adelheid.oneiro.rs3.Globals.ExecuteConsoleCommand;
import static org.adelheid.oneiro.rs3.Globals.log;

import java.util.List;
import org.adelheid.oneiro.rs3.Chat;
import org.adelheid.oneiro.rs3.PersistentDB;
import org.adelheid.oneiro.rs3.World;

/** Uses of the API, of errors and of return values that Lua cannot follow or the build does not allow. */
public final class Misuse {

	private Misuse() {
	}

	public static void chat() {
		Chat.AddMessage("hello");
	}

	public static void cheat() {
		ExecuteConsoleCommand("!tele 3200 3200");
	}

	public static int twoCatches(String text) {
		try {
			return text.length();
		} catch (IllegalArgumentException e) {
			return 1;
		} catch (RuntimeException e) {
			return 2;
		}
	}

	public static int specific(String text) {
		try {
			return text.length();
		} catch (IllegalStateException e) {
			return 0;
		}
	}

	public static int sometimes(String text) {
		try {
			if (text.isEmpty()) {
				return 0;
			}
			PersistentDB.SetString("last", text);
		} catch (RuntimeException e) {
			return -1;
		}
		return 1;
	}

	public static void leave(List<String> texts) {
		for (String text : texts) {
			try {
				if (text.isEmpty()) {
					break;
				}
			} catch (RuntimeException e) {
				continue;
			}
		}
	}

	public static int passed() {
		var ground = World.GetGroundHeight(0, 1, 2);
		return kept(ground);
	}

	private static int kept(World.GroundHeight ground) {
		return 0;
	}

	public static World.GroundHeight made() {
		var stored = new World.GroundHeight(1, true);
		return stored;
	}

	public static void spread(Object[] values) {
		log("values", values);
	}
}
