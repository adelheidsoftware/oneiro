package org.adelheid.oneiro.api;

/**
 * The plugin's entry point: the one class of a plugin that extends {@code Plugin} becomes its {@code main.lua}. The
 * client runs {@code main.lua} once when the plugin is enabled, so the class is a singleton: its fields and methods
 * become locals of {@code main.lua}, {@link #startUp()} runs when the file loads, and {@link #shutDown()} runs in the
 * global {@code PluginShutdown}, which the client calls when the plugin is disabled. Methods annotated
 * {@link Subscribe} are subscribed to their events after {@code startUp()} and unsubscribed before {@code shutDown()}.
 *
 * <pre>{@code
 * public final class HerbPlugin extends Plugin {
 *     private int ticks;
 *
 *     @Subscribe
 *     public void onServerLogic(ServerLogicEventPayload tick) {
 *         ticks++;
 *     }
 * }
 * }</pre>
 *
 * <p>The manifest is configured in the Gradle build, {@code oneiro { plugin { ... } }}, not on this class.
 */
public abstract class Plugin {

	/** Subclasses are created by the client, not by plugin code. */
	protected Plugin() {
	}

	/** Runs when the plugin is enabled, after its members are defined and before its events are subscribed. */
	protected void startUp() throws Exception {
	}

	/** Runs when the plugin is disabled, after its events are unsubscribed. */
	protected void shutDown() throws Exception {
	}
}
