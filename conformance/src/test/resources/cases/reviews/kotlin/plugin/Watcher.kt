package plugin

import org.adelheid.oneiro.api.Plugin
import org.adelheid.oneiro.api.Subscribe
import org.adelheid.oneiro.rs3.Chat
import org.adelheid.oneiro.rs3.ChatMessageEventPayload
import org.adelheid.oneiro.rs3.MiniMenuReadyEventPayload

/** Uses of the API that moderation reviews closely, each warned about where it is. */
class Watcher : Plugin() {

	@Subscribe
	fun onChatMessage(message: ChatMessageEventPayload) {
		// Changing the message is part of the subscription, which the warning names
		message.chatMessage = "<col=ff0000>${message.chatMessage}</col>"
	}

	@Subscribe
	fun onMiniMenuReady(ready: MiniMenuReadyEventPayload) {
		val menu = ready.miniMenu
		menu.Swap(1, 2)
		menu.Add("Note", { Chat.AddMessage("Noted") })
		for (entry in menu.entries) {
			// Reading the text changes nothing; assigning it changes the menu
			if (entry.overrideText == null) {
				entry.overrideText = entry.fullText + "!"
			}
		}
	}
}
