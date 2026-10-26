package org.adelheid.oneiro.plugin;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What the client's own error messages mean, and what to do instead. Oneiro's bindings follow the RS3 API's docs, so
 * code that compiles can still meet a part of the API that the client has differently, and the client's message then
 * names C++ types rather than the code's. Each message here is one the beta client gave, in the console of the Rich
 * Death test plugin or of the API probe (2026-10-24 and 25); any other message, such as one of the plugin's own, gets
 * no explanation.
 */
final class ClientMessages {

	// sol's check of a value the code gives the client: the value's place among the call's values, counting the object
	// a method is called on, what the client expected, and what it received
	private static final Pattern VALUE = Pattern.compile("stack index (\\d+), expected (.+?), received (.+?): .*");
	private static final Pattern CONTAINER = Pattern.compile(
			"sol: cannot call 'container\\[key] = value' on type '.*': it is not recognized as a container");
	private static final String NO_FORM =
			"sol: no matching function call takes this number of arguments and the specified types";
	// Lua's message for a name that is nil where the code reads from it or calls it
	private static final Pattern NIL =
			Pattern.compile("attempt to (index|call) a nil value \\((global|field|method) '(\\w+)'\\)");
	// The traceback's first call, when it is the client's function that raised the error: how the code named it, as
	// a method, a field or a metamethod, and its name
	private static final Pattern CLIENT_CALL = Pattern.compile("\\[C]: in (\\w+) '([^']+)'");
	private static final String BINDINGS = "Oneiro's bindings follow the docs";

	/** The client's function that raised an error, as the traceback names it. */
	private record ClientCall(String kind, String name) {

		/** Whether the code assigned a field of the client's, which sol makes a call of a metamethod. */
		boolean isAssignment() {
			return kind.equals("metamethod") && name.equals("newindex");
		}

		/** Whether the code called the function by its name, rather than through a metamethod. */
		boolean isNamed() {
			return !kind.equals("metamethod");
		}

		/** Which argument a stack index is: a method's object is its first value, which the code does not pass. */
		int argument(int index) {
			return kind.equals("method") ? index - 1 : index;
		}
	}

	private ClientMessages() {
	}

	/**
	 * What a message means and what to do instead, or null for a message this does not explain.
	 *
	 * @param message Lua's message, without the place in front of it
	 * @param calls the calls that led to the error, innermost first, as the traceback lists them
	 */
	static @Nullable String explain(String message, List<String> calls) {
		ClientCall call = clientCall(calls);
		Matcher value = VALUE.matcher(message);
		if (value.matches()) {
			return value(Integer.parseInt(value.group(1)), value.group(2), value.group(3), call);
		}
		if (CONTAINER.matcher(message).matches()) {
			return "This writes a field of a value that the client handed out, and the client gives it no field by"
					+ " this name that can be written: sol, the library the client binds its API with, takes the write"
					+ " for one into a container, which the value is not. Read its fields only; for a changed value,"
					+ " make a new one, such as with Vector3.new.";
		}
		if (message.equals(NO_FORM)) {
			return (call != null && call.isNamed() ? "In the client, " + call.name() + " has no form"
					: "The client has no form of this function")
					+ " that takes these arguments: " + BINDINGS + ", which give one that the client lacks. Call it"
					+ " with the arguments of another of its forms.";
		}
		Matcher nil = NIL.matcher(message);
		if (nil.matches() && isApiName(nil.group(2), nil.group(3))) {
			String name = nil.group(3);
			return name + " is nil in the client, so "
					+ (nil.group(1).equals("index") ? "nothing can be read from it" : "it cannot be called")
					+ ". Where " + name + " is a part of the RS3 API, " + BINDINGS + ", which describe it, but the"
					+ " client lacks it; where it is a library plugin's, the library's types describe it, but the"
					+ " player's version of the library lacks it. Leave it out until the client or the library has"
					+ " it, or reach what it offers another way.";
		}
		return null;
	}

	/** sol's check of a value: one the call leaves out, or a value of the wrong C++ type assigned to a field. */
	private static @Nullable String value(int index, String expected, String received, @Nullable ClientCall call) {
		if (received.equals("no value")) {
			String required = call != null && call.isNamed()
					? "In the client, " + call.name() + " requires argument " + call.argument(index) + ","
					: "The client requires the value at stack index " + index + ", counting the object a method is"
							+ " called on as 1,";
			return required + " which this call leaves out: " + BINDINGS + ", which let it be left out. Pass it.";
		}
		if (call != null && call.isAssignment() && expected.equals("userdata")) {
			return "The client does not take this value for the field: it wants a value of another C++ type"
					+ (received.startsWith("sol.") ? ", although the console can show the two by the same name, as it"
							+ " leaves out the part of a name between < and >" : "")
					+ ". " + BINDINGS + ", which give the field this type. The field cannot be set this way; where its"
					+ " type has a method that sets the same thing, use that.";
		}
		return null;
	}

	private static @Nullable ClientCall clientCall(List<String> calls) {
		if (calls.isEmpty()) {
			return null;
		}
		Matcher call = CLIENT_CALL.matcher(calls.get(0));
		return call.matches() ? new ClientCall(call.group(1), call.group(2)) : null;
	}

	/**
	 * Whether a nil name may be one of the RS3 API's or of a library plugin's, whose types may describe another version
	 * than the player has. A plugin compiled by Oneiro names nothing global but the API and its library plugins'
	 * aliases, and the API names its tables and functions with a capital, as a library plugin may name its
	 * tables, where Java and Kotlin name fields and methods in camel case; a name in capitals, such as a constant's, is
	 * left out. A vendored library ships with the plugin, so its names are always there.
	 */
	private static boolean isApiName(String kind, String name) {
		return kind.equals("global")
				|| (Character.isUpperCase(name.charAt(0)) && !name.equals(name.toUpperCase(Locale.ROOT)));
	}
}
