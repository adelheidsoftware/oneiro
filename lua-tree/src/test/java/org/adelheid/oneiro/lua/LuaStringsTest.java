package org.adelheid.oneiro.lua;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** String quoting as StyLua does it, and the escapes that keep invisible characters visible to a moderator. */
class LuaStringsTest {

	@Test
	void prefersDoubleQuotes() {
		assertEquals("\"it's\"", LuaStrings.quoted("it's"));
		assertEquals("\"\"", LuaStrings.quoted(""));
	}

	@Test
	void switchesToSingleQuotesWhenTheTextHoldsMoreDoubleQuotes() {
		assertEquals("'say \"hi\"'", LuaStrings.quoted("say \"hi\""));
		assertEquals("'\"a\" \"b\" \\'c'", LuaStrings.quoted("\"a\" \"b\" 'c"));
	}

	@Test
	void escapesBackslashesAndControlCharacters() {
		assertEquals("\"a\\\\b\"", LuaStrings.quoted("a\\b"));
		assertEquals("\"\\t\\n\\r\"", LuaStrings.quoted("\t\n\r"));
		assertEquals("\"\\007\\000\\127\"", LuaStrings.quoted("\u0007\u0000\u007f"));
	}

	@Test
	void keepsVisibleNonAsciiTextAndEscapesInvisibleCharacters() {
		assertEquals("\"é größe\"", LuaStrings.quoted("é größe"));
		assertEquals("\"zero\\u{200b}width\"", LuaStrings.quoted("zero​width"));
		assertEquals("\"no\\u{a0}break\"", LuaStrings.quoted("no break"));
	}

	@Test
	void chooseALongBracketLevelTheTextCannotClose() {
		assertEquals("[[\nplain]]", LuaStrings.longBracket("plain"));
		assertEquals("[=[\na]]b]=]", LuaStrings.longBracket("a]]b"));
		assertEquals("[=[\nends with]]=]", LuaStrings.longBracket("ends with]"));
		assertEquals("[==[\na]=]b]]]==]", LuaStrings.longBracket("a]=]b]]"));
	}

	@Test
	void refusesALongBracketForCarriageReturns() {
		assertNull(LuaStrings.longBracket("windows\r\nline"));
	}
}
