package plugin;

import static org.adelheid.oneiro.rs3.Globals.log;

import org.adelheid.oneiro.rs3.Chat;
import org.adelheid.oneiro.rs3.Event;
import org.adelheid.oneiro.rs3.HTTPRequest;
import org.adelheid.oneiro.rs3.Player;
import org.adelheid.oneiro.rs3.PlayerEntity;
import org.adelheid.oneiro.rs3.PlayerList;
import org.adelheid.oneiro.rs3.PluginURL;
import org.jspecify.annotations.Nullable;

/** Finds players, reads the tracking ID, fetches a page and echoes the chat: each a use moderation reviews closely. */
public final class Lookups {

	private Lookups() {
	}

	static int playersInView() {
		return PlayerList.All().size();
	}

	/** The local player is no other player, so finding it is no review of its own. */
	static PlayerEntity self() {
		return PlayerList.Self();
	}

	static @Nullable Long trackingId() {
		return Player.GetTrackingID();
	}

	static void ping(PluginURL base) {
		new HTTPRequest(base, "/ping").Get(request -> log(request.responseCode));
	}

	/** The Mod.io description says what the plugin fetches, so the request's warning is silenced; the chat's is not. */
	@SuppressWarnings("oneiro:http")
	static void fetch(PluginURL base) {
		new HTTPRequest(base).Get(request -> Chat.AddMessage(request.data));
	}

	static void echo() {
		Event.ChatMessage.Subscribe("watcher.echo", message -> log(message.chatMessage));
	}

	/** Unsubscribing is no use of the chat of its own. */
	static void stopEcho() {
		Event.ChatMessage.Unsubscribe("watcher.echo");
	}
}
