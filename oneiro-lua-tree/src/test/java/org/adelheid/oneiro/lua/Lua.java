package org.adelheid.oneiro.lua;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Short builders for the trees in the printer tests, so that each test reads close to the Lua it expects. */
final class Lua {

	private Lua() {
	}

	static Expression.Name name(String name) {
		return new Expression.Name(name);
	}

	/** Several names, for argument lists such as {@code f(a, b, c)}. */
	static Expression[] names(String... names) {
		return Arrays.stream(names).map(Lua::name).toArray(Expression[]::new);
	}

	static Expression number(String text) {
		return new Expression.NumberLiteral(text);
	}

	static Expression string(String value) {
		return Expression.string(value);
	}

	static Expression nil() {
		return new Expression.Nil();
	}

	static Expression bool(boolean value) {
		return new Expression.BooleanLiteral(value);
	}

	static Expression field(Expression target, String name) {
		return new Expression.Field(target, name);
	}

	static Expression index(Expression target, Expression key) {
		return new Expression.Index(target, key);
	}

	static Expression call(Expression function, Expression... arguments) {
		return new Expression.Call(function, List.of(arguments));
	}

	static Expression call(String function, Expression... arguments) {
		return call(name(function), arguments);
	}

	static Expression method(Expression receiver, String method, Expression... arguments) {
		return new Expression.MethodCall(receiver, method, List.of(arguments));
	}

	static Expression binary(Expression left, BinaryOperator operator, Expression right) {
		return new Expression.Binary(operator, left, right);
	}

	/** Operands joined left to right by one operator: {@code chain(AND, a, b, c)} is {@code (a and b) and c}. */
	static Expression chain(BinaryOperator operator, Expression... operands) {
		Expression result = operands[0];
		for (int index = 1; index < operands.length; index++) {
			result = binary(result, operator, operands[index]);
		}
		return result;
	}

	/**
	 * Operands joined right to left, as {@code ..} groups: {@code rightChain(CONCAT, a, b, c)} is
	 * {@code a .. (b .. c)}.
	 */
	static Expression rightChain(BinaryOperator operator, Expression... operands) {
		Expression result = operands[operands.length - 1];
		for (int index = operands.length - 2; index >= 0; index--) {
			result = binary(operands[index], operator, result);
		}
		return result;
	}

	static Expression unary(UnaryOperator operator, Expression operand) {
		return new Expression.Unary(operator, operand);
	}

	static Expression table(Expression.TableField... fields) {
		return new Expression.Table(List.of(fields));
	}

	/** A table of positional values. */
	static Expression list(Expression... values) {
		return table(Arrays.stream(values).map(Expression.TableField.Positional::new)
				.toArray(Expression.TableField[]::new));
	}

	static Expression.TableField named(String name, Expression value) {
		return new Expression.TableField.Named(name, value);
	}

	static Expression.TableField keyed(Expression key, Expression value) {
		return new Expression.TableField.Keyed(key, value);
	}

	static Expression function(List<String> parameters, Statement... body) {
		return new Expression.FunctionExpression(FunctionBody.of(parameters, Block.of(body)));
	}

	static Statement local(String name, Expression value) {
		return new Statement.Local(List.of(name), List.of(value));
	}

	static Statement assign(Expression target, Expression value) {
		return new Statement.Assignment(List.of(target), List.of(value));
	}

	static Statement run(Expression call) {
		return new Statement.CallStatement(call);
	}

	static Statement returns(Expression... values) {
		return new Statement.Return(List.of(values));
	}

	static Statement ifThen(Expression condition, Statement... body) {
		return new Statement.If(List.of(new Statement.If.Branch(condition, Block.of(body))), null);
	}

	static Statement.If.Branch branch(Expression condition, Statement... body) {
		return new Statement.If.Branch(condition, Block.of(body));
	}

	static Block.Entry entry(Statement statement) {
		return Block.Entry.of(statement);
	}

	static Block.Entry entry(Statement statement, Trivia trivia) {
		return new Block.Entry(statement, trivia, null);
	}

	static Block block(Block.Entry... entries) {
		return new Block(List.of(entries), List.of());
	}

	/** A blank line before the statement. */
	static Trivia blank() {
		return new Trivia(true, List.of(), null);
	}

	/** Comment lines above the statement, after an optional blank line. */
	static Trivia above(boolean blankLineBefore, Comment... comments) {
		return new Trivia(blankLineBefore, List.of(comments), null);
	}

	static Trivia trailing(String comment) {
		return new Trivia(false, List.of(), comment);
	}

	/** The comment lines of a file header, one per string. */
	static List<Comment> header(String... lines) {
		List<Comment> comments = new ArrayList<>();
		for (String line : lines) {
			comments.add(Comment.line(line));
		}
		return comments;
	}
}
