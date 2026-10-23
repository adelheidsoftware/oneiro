package plugin;

import org.adelheid.oneiro.rs3.PluginPermissions;

/** Client enum values that have no names in Lua. */
public final class Names {

	private Names() {
	}

	public static String kind(PluginPermissions permission) {
		return permission.name();
	}
}
