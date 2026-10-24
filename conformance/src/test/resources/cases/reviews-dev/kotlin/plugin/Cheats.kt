package plugin

import org.adelheid.oneiro.api.DevPlugin
import org.adelheid.oneiro.rs3.Chat

/** A development helper that writes to the chat: the dev source set never ships, so moderation never sees it. */
class Cheats : DevPlugin() {

	override fun startUp() {
		Chat.AddMessage("Development build")
	}
}
