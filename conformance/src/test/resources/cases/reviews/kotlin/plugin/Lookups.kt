package plugin

import org.adelheid.oneiro.rs3.Chat
import org.adelheid.oneiro.rs3.Event
import org.adelheid.oneiro.rs3.Globals.log
import org.adelheid.oneiro.rs3.HTTPRequest
import org.adelheid.oneiro.rs3.Player
import org.adelheid.oneiro.rs3.PlayerEntity
import org.adelheid.oneiro.rs3.PlayerList
import org.adelheid.oneiro.rs3.PluginURL

/** Finds players, reads the tracking ID, fetches a page and echoes the chat: each a use moderation reviews closely. */
internal object Lookups {

	fun playersInView(): Int {
		return PlayerList.All().size
	}

	/** The local player is no other player, so finding it is no review of its own. */
	fun self(): PlayerEntity {
		return PlayerList.Self()
	}

	fun trackingId(): Long? {
		return Player.GetTrackingID()
	}

	fun ping(base: PluginURL) {
		HTTPRequest(base, "/ping").Get { request -> log(request.responseCode) }
	}

	/** The Mod.io description says what the plugin fetches, so the request's warning is silenced; the chat's is not. */
	@Suppress("oneiro:http")
	fun fetch(base: PluginURL) {
		HTTPRequest(base).Get { request -> Chat.AddMessage(request.data) }
	}

	fun echo() {
		Event.ChatMessage.Subscribe("watcher.echo") { message -> log(message.chatMessage) }
	}

	/** Unsubscribing is no use of the chat of its own. */
	fun stopEcho() {
		Event.ChatMessage.Unsubscribe("watcher.echo")
	}
}
