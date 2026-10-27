package org.adelheid.oneiro.lowering.client;

import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.field;
import static org.adelheid.oneiro.lua.Expression.name;

import java.util.ArrayList;
import java.util.List;
import org.adelheid.oneiro.lua.Expression;

/**
 * The parts of the client's API that the compiler writes on its own. The rest of the API reaches it as the
 * annotations of the bindings generated from the stubs; these are what lowering itself builds on: the table of events
 * and the settings store, the global the client calls when the plugin is disabled, the string formatter, an enum
 * table's name lookup, and how a plugin checks a permission. The front ends and the rest of this module name them only
 * through this class, which the conformance suite's {@code ClientNamesTest} checks, so that when the client changes
 * one, the compiler changes here.
 */
public final class ClientApi {

	/** The table of events, to whose fields the plugin class's {@code @Subscribe} methods are subscribed. */
	public static final String EVENT = "Event";

	/** The store that keeps a plugin's settings, with a typed accessor for each kind of value. */
	public static final String PERSISTENT_DB = "PersistentDB";

	/** The global function the client calls when the plugin is disabled. */
	public static final String PLUGIN_SHUTDOWN = "PluginShutdown";

	/** The client's string formatter, whose template has a {@code {}} for each value. */
	public static final String FORMAT_STR = "formatStr";

	// The function of an enum table that turns a value, the client's number, into its name
	private static final String FROM_ID = "FromID";

	private ClientApi() {
	}

	/** {@code formatStr(template, values...)}. */
	public static Expression formatStr(String template, List<Expression> values) {
		List<Expression> arguments = new ArrayList<>();
		arguments.add(Expression.string(template));
		arguments.addAll(values);
		return new Expression.Call(name(FORMAT_STR), arguments);
	}

	/** {@code table.FromID(value)}, the name of a client enum's value. */
	public static Expression fromId(Expression table, Expression value) {
		return call(field(table, FROM_ID), value);
	}

	/** The reason a client enum's values have no names: the enum's table has no {@code FromID}. */
	public static String noFromId(String table) {
		return "The client's " + table + " table has no " + FROM_ID + " to give a value's name; compare the value with "
				+ table + "'s constants instead.";
	}

	/** How a plugin checks that it was granted a permission, as advice names it. */
	public static String permissionCheck(String permission) {
		return "CurrentPlugin.HasPermission(PluginPermissions." + permission + ")";
	}
}
