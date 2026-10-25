package org.adelheid.oneiro.bindings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Lua parser behind the LuaCATS reader: statements end where Lua ends them, whatever their bodies hold; literals,
 * escapes and the forms of {@code require} read as Lua reads them; comments go to the statement or field they belong
 * to; and Lua that is not valid is an error at its line.
 */
class LuaSyntaxTest {

	@Test
	void statementsEndWhereLuaEndsThem() {
		LuaSyntax.Chunk chunk = LuaSyntax.parse("""
				local M = {}

				function M.run(list)
				    for i = 1, #list do
				        if list[i] == "end" then
				            while true do break end
				        elseif i > 2 then
				            repeat i = i - 1 until i < 0
				        else
				            do local _ = [==[ end ]] end ]==] end
				        end
				    end
				    --[[ end
				    end ]]
				    return function(x) return x end
				end
				M.after = M:chain():call { 1 } "text"
				goto done
				::done::
				return M
				""");
		List<LuaSyntax.Statement> statements = chunk.statements();
		assertEquals(List.of("Local", "FunctionStatement", "Assignment", "OtherStatement", "OtherStatement", "Return"),
				statements.stream().map(statement -> statement.getClass().getSimpleName()).toList());
		LuaSyntax.FunctionStatement run = (LuaSyntax.FunctionStatement) statements.get(1);
		assertEquals(List.of("M", "run"), run.path());
		assertEquals(List.of("list"), run.parameters());
		assertEquals(3, run.line());
		assertEquals(16, run.endLine());
		LuaSyntax.Assignment after = (LuaSyntax.Assignment) statements.get(2);
		assertEquals(List.of(List.of("M", "after")), after.targets());
		assertInstanceOf(LuaSyntax.OtherExpression.class, after.values().getFirst());
	}

	@Test
	void readsLiteralsAndEscapesAsLuaDoes() {
		LuaSyntax.Chunk chunk = LuaSyntax.parse("""
				local a = "tab\\tquote\\"back\\\\A\\65\\x41\\u{48}\\z
				      done"
				local b = 'single'
				local c = [[
				long]]
				local d = 0x1p4
				local e = -3.0e-2
				local f = -x
				local g = nil
				local h = true
				""");
		List<String> values = chunk.statements().stream()
				.map(statement -> ((LuaSyntax.Local) statement).values().getFirst())
				.map(value -> value instanceof LuaSyntax.Literal literal
						? literal.kind() + " " + literal.value()
						: "other")
				.toList();
		assertEquals(List.of("STRING tab\tquote\"back\\AAAHdone", "STRING single", "STRING long", "NUMBER 0x1p4",
				"NUMBER -3.0e-2", "other", "NIL nil", "BOOLEAN true"), values);
	}

	@Test
	void readsTheFormsOfRequire() {
		LuaSyntax.Chunk chunk = LuaSyntax.parse("""
				local a = require "lib/a"
				local b = require("lib/b")
				local c = require("lib/c").part
				local d = require(name)
				local e = lib.Part
				""");
		List<LuaSyntax.Expression> values = chunk.statements().stream()
				.map(statement -> ((LuaSyntax.Local) statement).values().getFirst())
				.toList();
		assertEquals(new LuaSyntax.Require("lib/a"), values.get(0));
		assertEquals(new LuaSyntax.Require("lib/b"), values.get(1));
		assertInstanceOf(LuaSyntax.OtherExpression.class, values.get(2));
		assertInstanceOf(LuaSyntax.OtherExpression.class, values.get(3));
		assertEquals(new LuaSyntax.Path(List.of("lib", "Part")), values.get(4));
	}

	@Test
	void commentsGoToWhatTheyComeBefore() {
		LuaSyntax.Chunk chunk = LuaSyntax.parse("""
				---@class M
				local M = { -- trails the brace
				    --- The first.
				    first = 1, -- trails the first
				    second = 2,
				}
				local x = 1 -- trails x

				--- After everything
				""");
		LuaSyntax.Local table = (LuaSyntax.Local) chunk.statements().getFirst();
		assertEquals(List.of("-@class M"), table.comments().stream().map(LuaSyntax.Comment::text).toList());
		List<LuaSyntax.Field> fields = ((LuaSyntax.Table) table.values().getFirst()).fields();
		assertEquals(List.of("- The first."),
				fields.getFirst().comments().stream().map(LuaSyntax.Comment::text).toList());
		LuaSyntax.Comment trailing = fields.getFirst().trailing();
		assertTrue(trailing != null && trailing.text().equals(" trails the first"), String.valueOf(trailing));
		assertNull(fields.get(1).trailing());
		assertEquals(List.of(), chunk.statements().get(1).comments());
		assertEquals(List.of("- After everything"), chunk.trailing().stream().map(LuaSyntax.Comment::text).toList());
		assertEquals(9, chunk.trailing().getFirst().line());
	}

	@Test
	void countsLinesAsLuaDoes() {
		LuaSyntax.Chunk chunk = LuaSyntax.parse("local a = 1\r\nlocal b = 2\n\rlocal c = 3\rlocal d = 4\n");
		assertEquals(List.of(1, 2, 3, 4), chunk.statements().stream().map(LuaSyntax.Statement::line).toList());
	}

	@Test
	void luaThatIsNotValidIsAnErrorAtItsLine() {
		assertEquals(2, errorLine("local a = 1\nlocal b = \"open\n"));
		assertEquals(3, errorLine("local a = 1\n\nend\n"));
		assertEquals(1, errorLine("local n = 3x\n"));
		assertEquals(2, errorLine("if x then\n  y = "));
	}

	private static int errorLine(String source) {
		return assertThrows(LuaSyntax.SyntaxError.class, () -> LuaSyntax.parse(source)).line();
	}
}
