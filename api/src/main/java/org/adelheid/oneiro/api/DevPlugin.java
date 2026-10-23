package org.adelheid.oneiro.api;

/**
 * Development helpers that never ship: the one class of the {@code dev} source set that extends {@code DevPlugin}
 * works like the {@link Plugin} class, with {@link Subscribe} methods, {@link #startUp()} and {@link #shutDown()}, and
 * may use members marked {@link LuaDevOnly}, such as {@code ExecuteConsoleCommand}. Only the development folder that
 * the dev link points at loads it, after the plugin itself; the zip never contains it.
 */
public abstract class DevPlugin {

	/** Subclasses are created by the client, not by plugin code. */
	protected DevPlugin() {
	}

	/** Runs after the plugin's own start-up, before this class's events are subscribed. */
	protected void startUp() throws Exception {
	}

	/** Runs when the plugin is disabled, after this class's events are unsubscribed and the plugin has shut down. */
	protected void shutDown() throws Exception {
	}
}
