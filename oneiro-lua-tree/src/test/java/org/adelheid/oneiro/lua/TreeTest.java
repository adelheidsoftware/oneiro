package org.adelheid.oneiro.lua;

import static org.adelheid.oneiro.lua.Lua.call;
import static org.adelheid.oneiro.lua.Lua.name;
import static org.adelheid.oneiro.lua.Lua.number;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The tree refuses what Lua cannot express, so a front end's mistake surfaces where it is made, not in the output. */
class TreeTest {

	@Test
	void refusesKeywordsAndNonAsciiAsNames() {
		assertThrows(IllegalArgumentException.class, () -> new Expression.Name("end"));
		assertThrows(IllegalArgumentException.class, () -> new Expression.Name("größe"));
		assertThrows(IllegalArgumentException.class, () -> new Statement.Local(List.of("1st"), List.of()));
	}

	@Test
	void refusesNegativeOrMalformedNumerals() {
		assertThrows(IllegalArgumentException.class, () -> new Expression.NumberLiteral("-1"));
		assertThrows(IllegalArgumentException.class, () -> new Expression.NumberLiteral("1_000"));
		assertEquals("0xFF", new Expression.NumberLiteral("0xFF").text());
	}

	@Test
	void buildsNegativeIntegersAsNegatedNumerals() {
		assertEquals(
				new Expression.Unary(UnaryOperator.NEGATE, new Expression.NumberLiteral("5")),
				Expression.integer(-5));
		assertEquals(new Expression.Field(name("math"), "mininteger"), Expression.integer(Long.MIN_VALUE));
	}

	@Test
	void acceptsOnlyAssignableTargetsAndCallStatements() {
		assertThrows(IllegalArgumentException.class,
				() -> new Statement.Assignment(List.of(call("f")), List.of(number("1"))));
		assertThrows(IllegalArgumentException.class, () -> new Statement.CallStatement(name("f")));
	}

	@Test
	void keepsCommentsOnOneLine() {
		assertThrows(IllegalArgumentException.class, () -> Comment.line("two\nlines"));
		assertThrows(IllegalArgumentException.class, () -> new Trivia(false, List.of(), "two\nlines"));
	}
}
