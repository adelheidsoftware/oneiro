package plugin

import org.adelheid.oneiro.rs3.PluginPermissions

/** Client enum values that have no names in Lua. */
object Names {

	fun kind(permission: PluginPermissions): String {
		return permission.name
	}
}
