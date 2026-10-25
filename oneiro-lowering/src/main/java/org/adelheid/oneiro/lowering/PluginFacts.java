package org.adelheid.oneiro.lowering;

import java.util.Set;

/**
 * What the compiler needs to know about the plugin besides its sources, from the build's {@code oneiro { plugin { } }}
 * block: the identifier its event subscriptions are named after, the permissions and manifest flags the code may rely
 * on, the files of its own Lua, which no class's Lua may replace, and whether the sources are the {@code dev} source
 * set, which may use development-only members.
 *
 * @param id the plugin's identifier, such as {@code herb-tracker}; subscriptions are named {@code "<id>.<method>"}
 * @param permissions the permissions the manifest requests, required or optional, such as {@code chatAccess}
 * @param manifestFlags the manifest's flags that are set, such as {@code hasSettingsIcon}
 * @param luaFiles the paths of the source set's own Lua files in the plugin, such as {@code src/core/levels.lua}
 * @param dev whether the sources are the {@code dev} source set
 */
public record PluginFacts(String id, Set<String> permissions, Set<String> manifestFlags, Set<String> luaFiles,
		boolean dev) {

	/** A plugin named {@code plugin} with no permissions, flags or Lua, as the conformance cases assume by default. */
	public static final PluginFacts DEFAULT = new PluginFacts("plugin", Set.of(), Set.of(), Set.of(), false);

	public PluginFacts {
		permissions = Set.copyOf(permissions);
		manifestFlags = Set.copyOf(manifestFlags);
		luaFiles = Set.copyOf(luaFiles);
	}
}
