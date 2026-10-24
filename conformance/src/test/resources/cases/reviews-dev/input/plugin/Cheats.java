package plugin;

import org.adelheid.oneiro.api.DevPlugin;
import org.adelheid.oneiro.rs3.Chat;

/** A development helper that writes to the chat: the dev source set never ships, so moderation never sees it. */
public final class Cheats extends DevPlugin {

	@Override
	protected void startUp() {
		Chat.AddMessage("Development build");
	}
}
