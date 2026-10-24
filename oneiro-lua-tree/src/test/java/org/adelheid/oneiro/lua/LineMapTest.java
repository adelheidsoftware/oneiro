package org.adelheid.oneiro.lua;

import static org.adelheid.oneiro.lua.BinaryOperator.EQUAL;
import static org.adelheid.oneiro.lua.Lua.binary;
import static org.adelheid.oneiro.lua.Lua.bool;
import static org.adelheid.oneiro.lua.Lua.header;
import static org.adelheid.oneiro.lua.Lua.ifThen;
import static org.adelheid.oneiro.lua.Lua.local;
import static org.adelheid.oneiro.lua.Lua.name;
import static org.adelheid.oneiro.lua.Lua.nil;
import static org.adelheid.oneiro.lua.Lua.returns;
import static org.adelheid.oneiro.lua.Lua.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Every printed line traces back to the statement it belongs to, including the {@code end} that closes it. */
class LineMapTest {

	private static final SourcePosition MODULE = new SourcePosition("core/Row.java", 5);
	private static final SourcePosition DECLARATION = new SourcePosition("core/Row.java", 10);
	private static final SourcePosition CHECK = new SourcePosition("core/Row.java", 11);
	private static final SourcePosition RESULT = new SourcePosition("core/Row.java", 14);

	@Test
	void mapsEachLineToTheStatementItBelongsTo() {
		Statement guard = ifThen(binary(name("row"), EQUAL, nil()), returns(bool(false)));
		Statement function = new Statement.FunctionDeclaration(List.of("Row", "complete"), null, FunctionBody.of(
				List.of("row"),
				new Block(List.of(
						new Block.Entry(guard, Trivia.NONE, CHECK),
						new Block.Entry(returns(bool(true)), Trivia.NONE, RESULT)),
						List.of())));
		Chunk chunk = new Chunk(header("Header."), new Block(List.of(
				new Block.Entry(local("Row", table()), Trivia.NONE, MODULE),
				new Block.Entry(function, Lua.blank(), DECLARATION)),
				List.of()));

		LineMap lines = LuaPrinter.print(chunk).lineMap();

		assertEquals(10, lines.lineCount());
		assertNull(lines.sourceOf(1), "-- Header.");
		assertNull(lines.sourceOf(2), "blank line");
		assertEquals(MODULE, lines.sourceOf(3), "local Row = {}");
		assertNull(lines.sourceOf(4), "blank line");
		assertEquals(DECLARATION, lines.sourceOf(5), "function Row.complete(row)");
		assertEquals(CHECK, lines.sourceOf(6), "if row == nil then");
		assertEquals(CHECK, lines.sourceOf(7), "return false, without a position of its own, takes the if's");
		assertEquals(CHECK, lines.sourceOf(8), "end of the if");
		assertEquals(RESULT, lines.sourceOf(9), "return true");
		assertEquals(DECLARATION, lines.sourceOf(10), "end of the function");
	}
}
