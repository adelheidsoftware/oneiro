package org.adelheid.oneiro.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The client's own messages explained. Each message, and the call in front of it, is as the beta client gave it, in
 * the console of the Rich Death test plugin (2026-10-24) or of the API probe (2026-10-25), unless a comment says
 * otherwise.
 */
class ClientMessagesTest {

	private static final String BINDINGS = "Oneiro's bindings follow the docs";

	@Test
	void aFieldThatTakesNoValueMadeInLua() {
		// Rich Death, assigning a Vector3.new to a ui.Model's rotation
		assertEquals("The client does not take this value for the field: it wants a value of another C++ type,"
				+ " although the console can show the two by the same name, as it leaves out the part of a name"
				+ " between < and >. " + BINDINGS + ", which give the field this type. The field cannot be set this"
				+ " way; where its type has a method that sets the same thing, use that.",
				ClientMessages.explain("stack index 3, expected userdata, received sol.jag::math::Vector3T: value at"
						+ " this index does not properly reflect the desired type (bad argument into"
						+ " 'void(const jag::math::Vector3T&)')", List.of("[C]: in metamethod 'newindex'",
						"[string \"-- Rich Death test plugin: draws the real par...\"]:80: in function")));
	}

	@Test
	void aFieldOfAValueTheClientHandedOut() {
		// Rich Death, setting x on the rotation read back from a ui.Model; the probe caught it, so it has no traceback
		assertEquals("This writes a field of a value that the client handed out, and the client gives it no field"
				+ " by this name that can be written: sol, the library the client binds its API with, takes the"
				+ " write for one into a container, which the value is not. Read its fields only; for a changed"
				+ " value, make a new one, such as with Vector3.new.",
				ClientMessages.explain("sol: cannot call 'container[key] = value' on type 'jag::math::Vector3T': it is"
						+ " not recognized as a container", List.of()));
	}

	@Test
	void aNameTheClientLacks() {
		// Rich Death, ui.ModelCamera.new()
		assertEquals("ModelCamera is nil in the client, so nothing can be read from it. Where ModelCamera is a part"
				+ " of the RS3 API, " + BINDINGS + ", which describe it, but the client lacks it; where it is a library"
				+ " plugin's, the library's types describe it, but the player's version of the library lacks it. Leave"
				+ " it out until the client or the library has it, or reach what it offers another way.",
				ClientMessages.explain("attempt to index a nil value (field 'ModelCamera')", List.of()));
		// Lua's own message for a method the client lacks, when the code calls it; no run has given it yet
		assertEquals("AlignTo is nil in the client, so it cannot be called. Where AlignTo is a part of the RS3 API, "
				+ BINDINGS + ", which describe it, but the client lacks it; where it is a library plugin's, the"
				+ " library's types describe it, but the player's version of the library lacks it. Leave it out until"
				+ " the client or the library has it, or reach what it offers another way.",
				ClientMessages.explain("attempt to call a nil value (method 'AlignTo')", List.of()));
		// A library plugin's table that the player's version lacks, as PrettyUI names its own
		assertEquals("SimpleView is nil in the client, so nothing can be read from it. Where SimpleView is a part of"
				+ " the RS3 API, " + BINDINGS + ", which describe it, but the client lacks it; where it is a library"
				+ " plugin's, the library's types describe it, but the player's version of the library lacks it. Leave"
				+ " it out until the client or the library has it, or reach what it offers another way.",
				ClientMessages.explain("attempt to index a nil value (field 'SimpleView')", List.of()));
	}

	@Test
	void anArgumentTheClientRequires() {
		// The API probe, SetModelSlot(0, 1) and ScreenConvert.CoordGridToScreen(coord): a method's object is its
		// first value, so stack index 4 is its third argument
		assertEquals("In the client, SetModelSlot requires argument 3, which this call leaves out: " + BINDINGS
				+ ", which let it be left out. Pass it.",
				ClientMessages.explain("stack index 4, expected number, received no value: not a numeric type (bad"
						+ " argument into 'bool(unsigned __int64, int, float, const jag::math::Vector3T&,"
						+ " const jag::math::Vector3T&)')", List.of("[C]: in method 'SetModelSlot'",
						"[string \"-- src/risky.lua...\"]:79: in field 'integer index'")));
		assertEquals("In the client, CoordGridToScreen requires argument 2, which this call leaves out: " + BINDINGS
				+ ", which let it be left out. Pass it.",
				ClientMessages.explain("stack index 2, expected number, received no value: not a numeric type (bad"
						+ " argument into 'sol::lua_value(const jag::game::CoordGrid&, int)')",
						List.of("[C]: in field 'CoordGridToScreen'")));
		// Without its traceback, the console's text names no function
		assertEquals("The client requires the value at stack index 4, counting the object a method is called on as"
				+ " 1, which this call leaves out: " + BINDINGS + ", which let it be left out. Pass it.",
				ClientMessages.explain("stack index 4, expected number, received no value: not a numeric type (bad"
						+ " argument into 'void(float, float, unsigned char)')", List.of()));
	}

	@Test
	void aFormTheClientLacks() {
		// The API probe, Interface:GetComponent(staticID)
		assertEquals("In the client, GetComponent has no form that takes these arguments: " + BINDINGS + ", which"
				+ " give one that the client lacks. Call it with the arguments of another of its forms.",
				ClientMessages.explain("sol: no matching function call takes this number of arguments and the"
						+ " specified types", List.of("[C]: in method 'GetComponent'",
						"[string \"-- src/risky.lua...\"]:73: in field 'integer index'")));
	}

	@Test
	void messagesOfThePluginsOwnAreNotExplained() {
		assertNull(ClientMessages.explain("attempt to perform arithmetic on a nil value (field 'count')", List.of()));
		// A field of the plugin's own, named in camel case, and a constant
		assertNull(ClientMessages.explain("attempt to index a nil value (field 'target')", List.of()));
		assertNull(ClientMessages.explain("attempt to index a nil value (field 'LIMIT')", List.of()));
		assertNull(ClientMessages.explain("error-probe: thrown from Java in a button's callback",
				List.of("[C]: in function 'base.error'")));
	}
}
