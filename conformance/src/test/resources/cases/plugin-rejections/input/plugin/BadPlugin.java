package plugin;

import org.adelheid.oneiro.api.Plugin;
import org.adelheid.oneiro.api.Subscribe;
import org.adelheid.oneiro.rs3.ChatMessageEventPayload;
import org.adelheid.oneiro.rs3.EntityDrawEventPayload;
import org.adelheid.oneiro.rs3.LogicEventPayload;
import org.adelheid.oneiro.rs3.ServerLogicEventPayload;
import org.adelheid.oneiro.rs3.SettingsLayerReadyEventPayload;

/** A plugin class that breaks the rules of the plugin model. */
public final class BadPlugin extends Plugin {

	private BadConfig settings = null;
	private final Helper helper = new Helper();

	public BadPlugin() {
		helper.count();
	}

	@Override
	protected void startUp() {
		helper.remember(this);
	}

	@Subscribe
	public void onTwo(LogicEventPayload event, int extra) {
	}

	@Subscribe
	public void onText(String text) {
	}

	@Subscribe
	public void onDraw(EntityDrawEventPayload event) {
	}

	@Subscribe
	public boolean onLogic(LogicEventPayload event) {
		return true;
	}

	@Subscribe
	public void onChat(ChatMessageEventPayload event) {
	}

	@Subscribe
	public void onSettings(SettingsLayerReadyEventPayload event) {
	}

	@Subscribe
	public void onTick(ServerLogicEventPayload tick) {
		Object kept = tick;
	}

	public void restart() {
		startUp();
		settings = null;
	}
}
