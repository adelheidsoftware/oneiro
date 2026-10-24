package org.adelheid.oneiro.lua;

import static org.adelheid.oneiro.lua.BinaryOperator.ADD;
import static org.adelheid.oneiro.lua.BinaryOperator.AND;
import static org.adelheid.oneiro.lua.BinaryOperator.CONCAT;
import static org.adelheid.oneiro.lua.BinaryOperator.EQUAL;
import static org.adelheid.oneiro.lua.BinaryOperator.FLOOR_DIVIDE;
import static org.adelheid.oneiro.lua.BinaryOperator.GREATER;
import static org.adelheid.oneiro.lua.BinaryOperator.LESS;
import static org.adelheid.oneiro.lua.BinaryOperator.LESS_EQUAL;
import static org.adelheid.oneiro.lua.BinaryOperator.MODULO;
import static org.adelheid.oneiro.lua.BinaryOperator.MULTIPLY;
import static org.adelheid.oneiro.lua.BinaryOperator.NOT_EQUAL;
import static org.adelheid.oneiro.lua.BinaryOperator.OR;
import static org.adelheid.oneiro.lua.BinaryOperator.POWER;
import static org.adelheid.oneiro.lua.BinaryOperator.SUBTRACT;
import static org.adelheid.oneiro.lua.Lua.above;
import static org.adelheid.oneiro.lua.Lua.assign;
import static org.adelheid.oneiro.lua.Lua.binary;
import static org.adelheid.oneiro.lua.Lua.blank;
import static org.adelheid.oneiro.lua.Lua.block;
import static org.adelheid.oneiro.lua.Lua.bool;
import static org.adelheid.oneiro.lua.Lua.branch;
import static org.adelheid.oneiro.lua.Lua.call;
import static org.adelheid.oneiro.lua.Lua.chain;
import static org.adelheid.oneiro.lua.Lua.entry;
import static org.adelheid.oneiro.lua.Lua.field;
import static org.adelheid.oneiro.lua.Lua.function;
import static org.adelheid.oneiro.lua.Lua.header;
import static org.adelheid.oneiro.lua.Lua.ifThen;
import static org.adelheid.oneiro.lua.Lua.index;
import static org.adelheid.oneiro.lua.Lua.keyed;
import static org.adelheid.oneiro.lua.Lua.list;
import static org.adelheid.oneiro.lua.Lua.local;
import static org.adelheid.oneiro.lua.Lua.method;
import static org.adelheid.oneiro.lua.Lua.name;
import static org.adelheid.oneiro.lua.Lua.named;
import static org.adelheid.oneiro.lua.Lua.names;
import static org.adelheid.oneiro.lua.Lua.nil;
import static org.adelheid.oneiro.lua.Lua.number;
import static org.adelheid.oneiro.lua.Lua.returns;
import static org.adelheid.oneiro.lua.Lua.rightChain;
import static org.adelheid.oneiro.lua.Lua.run;
import static org.adelheid.oneiro.lua.Lua.string;
import static org.adelheid.oneiro.lua.Lua.table;
import static org.adelheid.oneiro.lua.Lua.trailing;
import static org.adelheid.oneiro.lua.Lua.unary;
import static org.adelheid.oneiro.lua.UnaryOperator.LENGTH;
import static org.adelheid.oneiro.lua.UnaryOperator.NEGATE;
import static org.adelheid.oneiro.lua.UnaryOperator.NOT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Prints trees and compares them with hand-written Lua in {@code src/test/resources}. Every expected file is what
 * StyLua makes of one-line input, with the configuration in {@code stylua.toml} at the repository root, so these tests
 * pin the printer to StyLua's layout.
 */
class LuaPrinterTest {

	/** Blueprint-Helper's {@code src/core/row.lua}, a module written by hand in the house style. */
	@Test
	void printsTheRowModuleExactlyAsWrittenByHand() {
		Expression row = name("row");
		Expression slot = index(row, name("slot"));
		Statement complete = new Statement.FunctionDeclaration(List.of("Row", "complete"), null, FunctionBody.of(
				List.of("row"),
				Block.of(
						ifThen(binary(row, EQUAL, nil()), returns(bool(false))),
						new Statement.NumericFor("slot", number("1"), number("5"), null, Block.of(
								ifThen(binary(binary(slot, EQUAL, nil()), OR, binary(slot, LESS, number("0"))),
										returns(bool(false))))),
						returns(bool(true)))));
		Chunk chunk = new Chunk(
				header(
						"A row is the five track slots of the Discovery window as the reader sees them: an array"
								+ " of five sprite IDs, -1",
						"where a slot is empty. This is the one place that says when a row is complete, which is"
								+ " when a reading can refer",
						"to it, a puzzle identity can be built from it, and every module on it has a target slot."),
				block(
						entry(local("Row", table())),
						entry(complete, above(true,
								Comment.annotation("@param row integer[]? five sprite IDs, -1 for an empty slot"),
								Comment.annotation("@return boolean complete true when every one of the five slots"
										+ " holds a module"))),
						entry(returns(name("Row")), blank())));

		assertEquals(expected("row.lua"), LuaPrinter.print(chunk).text());
	}

	@Test
	void breaksLongLinesAsStyLuaDoes() {
		Expression[] words = {
				string("identity"), string("moduleOf"), string("spriteOf"), string("readings"), string("visited"),
				string("contradiction"), string("answers"), string("answer"), string("suggestion"),
		};
		Expression[] moreWords = List.of(
				string("identity"), string("moduleOf"), string("spriteOf"), string("readings"), string("visited"),
				string("contradiction"), string("answers"), string("answer"), string("suggestion"),
				string("prototyping")).toArray(Expression[]::new);
		Expression.TableField[] labelFields = {
				named("text", string("a fairly long string value for this table field")),
				named("colour", field(name("Theme"), "MUTED")),
				named("size", number("12")),
				named("x", number("1")),
				named("y", number("2")),
				named("z", number("3")),
		};
		Expression total = binary(
				binary(
						binary(name("alphaValueNumberOne"), ADD,
								binary(name("betaValueNumberTwo"), MULTIPLY, name("gammaValueNumberThree"))),
						SUBTRACT, name("deltaValueNumberFour")),
				ADD, name("epsilonValueLongerStill"));
		Expression joined = rightChain(CONCAT, names("firstStringPart", "secondStringPart", "thirdStringPart",
				"fourthStringPart", "fifthStringPart", "sixthPartLongerStill"));
		Expression subscribe = call(field(field(name("Event"), "ServerLogic"), "Subscribe"),
				string("tick-counter.server-logic"),
				function(List.of("payload"), assign(name("count"), binary(name("count"), ADD, number("1")))));
		// A table holding a function keeps the call's arguments on its line, as a function does, while that line fits
		Expression shape = call("withMembers", call(field(name("Shape"), "new"), string("square")),
				table(named("area", function(List.of("self"), returns(number("1.0"))))));
		Expression tooLongToHug = call("someFunction", name("argumentNumberOne"), name("argumentNumberTwo"),
				name("argumentNumberThree"), name("argumentNumberFour"), name("argumentNumberFive"),
				function(List.of(), returns(number("1"))));
		Expression functionFirst = call("pcall",
				function(List.of(), assign(name("count"), binary(name("count"), ADD, number("1")))), name("count"));
		Expression tableLast = call("someFunction", name("alpha"), name("beta"),
				table(named("text", string("a fairly long string value for this table field")),
						named("colour", field(name("Theme"), "MUTED")), named("size", number("12"))));
		Statement check = new Statement.LocalFunction("check", FunctionBody.of(
				List.of("parameterNumberOne", "parameterNumberTwo", "parameterNumberThree", "parameterNumberFour",
						"parameterFiveSixSeven"),
				Block.of(
						new Statement.If(List.of(
								branch(
										chain(AND,
												binary(name("someLongConditionName"), NOT_EQUAL, nil()),
												binary(name("anotherLongConditionName"), NOT_EQUAL, nil()),
												binary(name("yetAnotherConditionName"), NOT_EQUAL, nil()),
												name("lastOne")),
										returns(number("1"))),
								branch(
										call("someFunctionName", names("argumentNumberOne", "argumentNumberTwo",
												"argumentNumberThree", "argumentNumberFour", "argument5")),
										returns(number("2")))),
								null),
						returns(chain(OR,
								binary(name("someLongConditionName"), NOT_EQUAL, nil()),
								binary(name("anotherLongConditionName"), NOT_EQUAL, nil()),
								binary(name("yetAnotherConditionName"), NOT_EQUAL, nil()),
								name("lastOne1234"))))));
		Chunk chunk = new Chunk(
				header("Line breaking: each statement is too long for one line, so the text shows where StyLua"
						+ " breaks it."),
				block(
						entry(local("message", call("formatStr",
								string("Blueprint {} at level {} with {} readings and {} more words"
										+ " here plus a lot more"),
								name("name"), name("level"), name("count"), name("other"), name("more")))),
						entry(local("call", call("someFunction", names("argumentNumberOne", "argumentNumberTwo",
								"argumentNumberThree", "argumentNumberFour", "argumentNumberFive6")))),
						entry(local("list", list(words))),
						entry(local("longer", list(moreWords))),
						entry(local("named", table(labelFields))),
						entry(run(method(name("view"), "AddText", table(labelFields)))),
						entry(local("total", total)),
						entry(local("joined", joined)),
						entry(run(subscribe)),
						entry(run(call("pcall", function(List.of())))),
						entry(local("object", shape)),
						entry(run(tooLongToHug)),
						entry(run(functionFirst)),
						entry(local("labels", tableLast)),
						entry(check, blank())));

		assertEquals(expected("layout.lua"), LuaPrinter.print(chunk).text());
	}

	@Test
	void printsStatementsCommentsAndBlankLines() {
		Expression self = name("self");
		Expression tracker = name("Tracker");
		Expression total = name("total");
		Statement create = new Statement.FunctionDeclaration(List.of("Tracker", "new"), null, FunctionBody.of(
				List.of(),
				block(
						entry(local("self", call("setmetatable", table(), tracker))),
						entry(assign(field(self, "confirmed"), table()), trailing("sprite ID -> true")),
						entry(assign(field(self, "count"), number("0"))),
						entry(returns(self)))));
		Statement confirm = new Statement.FunctionDeclaration(List.of("Tracker"), "confirm", FunctionBody.of(
				List.of("sprite"),
				Block.of(new Statement.If(
						List.of(branch(
								unary(NOT, index(field(self, "confirmed"), name("sprite"))),
								assign(index(field(self, "confirmed"), name("sprite")), bool(true)),
								assign(field(self, "count"), binary(field(self, "count"), ADD, number("1"))))),
						Block.of(run(call("print", string("already confirmed"))))))));
		Block countDownBody = new Block(
				List.of(
						entry(new Statement.Local(List.of("total"), List.of())),
						entry(new Statement.NumericFor("index", name("from"), number("1"), unary(NEGATE, number("1")),
								Block.of(
										ifThen(binary(binary(name("index"), MODULO, number("2")), EQUAL, number("0")),
												new Statement.Goto("continue")),
										assign(total, binary(binary(total, OR, number("0")), ADD, name("index"))),
										new Statement.Label("continue")))),
						entry(new Statement.GenericFor(List.of("_", "value"),
								List.of(call("ipairs", list(new Expression.Vararg()))),
								Block.of(run(call("print", name("value")))))),
						entry(new Statement.While(binary(total, GREATER, number("10")),
								Block.of(assign(total, binary(total, FLOOR_DIVIDE, number("2")))))),
						entry(new Statement.Repeat(
								Block.of(assign(total, binary(total, SUBTRACT, number("1")))),
								binary(total, LESS_EQUAL, number("0"))))),
				List.of(Comment.line("Nothing else to do here.")));
		Statement countDown = new Statement.LocalFunction("countDown",
				new FunctionBody(List.of("from"), true, countDownBody));
		Chunk chunk = new Chunk(
				header("Statement forms, comments and blank lines.", "",
						"The second paragraph of the header follows an empty comment line."),
				block(
						entry(local("Tracker", table())),
						entry(assign(field(tracker, "__index"), tracker)),
						entry(create, above(true,
								Comment.line("Creates a tracker with nothing confirmed."),
								Comment.annotation("@return Tracker"))),
						entry(confirm, blank()),
						entry(countDown, blank()),
						entry(new Statement.Local(List.of("first", "second"), List.of(number("1"), number("2"))),
								blank()),
						entry(new Statement.Assignment(List.of(name("first"), name("second")),
								List.of(name("second"), name("first")))),
						entry(new Statement.While(bool(true), Block.of(new Statement.Break()))),
						entry(returns(tracker), blank())));

		assertEquals(expected("statements.lua"), LuaPrinter.print(chunk).text());
	}

	@Test
	void addsOnlyTheParenthesesLuaNeeds() {
		Expression x = name("x");
		Expression y = name("y");
		Expression z = name("z");
		Chunk chunk = new Chunk(
				header("Expressions: parentheses only where Lua needs them, unary operators, strings and"
						+ " other literals."),
				block(
						entry(local("a", binary(binary(x, ADD, y), MULTIPLY, z))),
						entry(local("b", binary(x, SUBTRACT, binary(y, SUBTRACT, z)))),
						entry(local("c", binary(binary(x, SUBTRACT, y), SUBTRACT, z))),
						entry(local("d", rightChain(CONCAT, x, y, z))),
						entry(local("e", binary(number("2"), POWER, unary(NEGATE, x)))),
						entry(local("f", binary(unary(NEGATE, x), POWER, number("2")))),
						entry(local("g", unary(NEGATE, unary(NEGATE, x)))),
						entry(local("h", unary(NOT, binary(name("p"), AND, name("q"))))),
						entry(local("i", unary(NEGATE, binary(x, ADD, y)))),
						entry(local("j", binary(unary(LENGTH, name("list")), ADD, number("1")))),
						entry(local("k", method(string("x"), "rep", number("3")))),
						entry(local("l", list(number("1"), number("2"), number("3")))),
						entry(local("m", table(keyed(name("key"), name("value")), named("name", string("text"))))),
						entry(local("n", string("say \"hi\""))),
						entry(local("o", string("it's"))),
						entry(local("p", string("tab\tline\n"))),
						entry(local("q", chain(ADD, number("0xFF"), number("1.5"), number("1e3")))),
						entry(local("r", nil())),
						entry(local("s", binary(bool(true), AND, unary(NOT, bool(false))))),
						entry(local("t", method(index(field(field(name("a"), "b"), "c"), name("d")), "e", name("f")))),
						entry(local("u", new Expression.StringLiteral("first line\nsecond line", true))),
						entry(local("v", new Expression.Vararg())),
						entry(local("w", Expression.integer(Long.MIN_VALUE))),
						entry(local("y", table(
								new Expression.TableField.Commented(List.of(Comment.line("The first.")),
										named("FIRST", string("FIRST"))),
								named("SECOND", string("SECOND")))))));

		assertEquals(expected("expressions.lua"), LuaPrinter.print(chunk).text());
	}

	private static String expected(String resource) {
		try (InputStream input = LuaPrinterTest.class.getResourceAsStream(resource)) {
			return new String(Objects.requireNonNull(input, resource).readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Cannot read " + resource, e);
		}
	}
}
