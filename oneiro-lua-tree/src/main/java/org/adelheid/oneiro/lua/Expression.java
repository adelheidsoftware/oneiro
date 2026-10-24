package org.adelheid.oneiro.lua;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The Lua expressions Oneiro emits. The tree holds no parentheses for precedence, which the printer adds where Lua
 * needs them; {@link Parenthesized} is only for parentheses that change meaning.
 */
public sealed interface Expression {

	record Nil() implements Expression {
	}

	record BooleanLiteral(boolean value) implements Expression {
	}

	/**
	 * A numeral as Lua writes it: {@code 42}, {@code 0xFF}, {@code 1.5}, {@code 1e3}. Never negative; a negative
	 * number is {@link UnaryOperator#NEGATE} applied to a numeral, which is what {@link #integer(long)} builds.
	 */
	record NumberLiteral(String text) implements Expression {

		private static final Pattern NUMERAL =
				Pattern.compile("0[xX][0-9A-Fa-f]+|(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

		public NumberLiteral {
			if (!NUMERAL.matcher(text).matches()) {
				throw new IllegalArgumentException("Not a Lua numeral: " + text);
			}
		}
	}

	/**
	 * A string. The printer quotes it as StyLua does, or writes it as a long bracket string ({@code [[...]]}) when
	 * {@code longBracket} is set and the text allows it, as for Java text blocks.
	 */
	record StringLiteral(String value, boolean longBracket) implements Expression {
	}

	/** {@code ...} inside a function that takes varargs. */
	record Vararg() implements Expression {
	}

	record Name(String name) implements Expression {

		public Name {
			LuaNames.requireName(name);
		}
	}

	/** {@code target[key]}. */
	record Index(Expression target, Expression key) implements Expression {
	}

	/** {@code target.name}. */
	record Field(Expression target, String name) implements Expression {

		public Field {
			LuaNames.requireName(name);
		}
	}

	/** {@code function(arguments)}. */
	record Call(Expression function, List<Expression> arguments) implements Expression {

		public Call {
			arguments = List.copyOf(arguments);
		}
	}

	/** {@code receiver:method(arguments)}, which passes the receiver as {@code self}. */
	record MethodCall(Expression receiver, String method, List<Expression> arguments) implements Expression {

		public MethodCall {
			LuaNames.requireName(method);
			arguments = List.copyOf(arguments);
		}
	}

	record FunctionExpression(FunctionBody function) implements Expression {
	}

	record Binary(BinaryOperator operator, Expression left, Expression right) implements Expression {
	}

	record Unary(UnaryOperator operator, Expression operand) implements Expression {
	}

	/** A table constructor; the printer adds the trailing comma when the fields go one per line. */
	record Table(List<TableField> fields) implements Expression {

		public Table {
			fields = List.copyOf(fields);
		}
	}

	/** Parentheses that change meaning, such as {@code (s:gsub(a, b))} keeping only the first result. */
	record Parenthesized(Expression inner) implements Expression {
	}

	/** One field of a table constructor. */
	sealed interface TableField {

		/** {@code value}, stored at the next array index. */
		record Positional(Expression value) implements TableField {
		}

		/** {@code name = value}. */
		record Named(String name, Expression value) implements TableField {

			public Named {
				LuaNames.requireName(name);
			}
		}

		/** {@code [key] = value}. */
		record Keyed(Expression key, Expression value) implements TableField {
		}

		/** A field with comment lines above it, which puts every field of the table on a line of its own. */
		record Commented(List<Comment> comments, TableField field) implements TableField {

			public Commented {
				comments = List.copyOf(comments);
			}
		}
	}

	static Name name(String name) {
		return new Name(name);
	}

	/** An integer: a numeral, or for a negative value a negated numeral. */
	static Expression integer(long value) {
		if (value == Long.MIN_VALUE) {
			// The numeral 9223372036854775808 does not fit an integer, so Lua would read its negation as a float
			return new Field(name("math"), "mininteger");
		}
		NumberLiteral magnitude = new NumberLiteral(Long.toString(Math.abs(value)));
		return value < 0 ? new Unary(UnaryOperator.NEGATE, magnitude) : magnitude;
	}

	static StringLiteral string(String value) {
		return new StringLiteral(value, false);
	}

	static Field field(Expression target, String name) {
		return new Field(target, name);
	}

	static Call call(Expression function, Expression... arguments) {
		return new Call(function, List.of(arguments));
	}

	static MethodCall methodCall(Expression receiver, String method, Expression... arguments) {
		return new MethodCall(receiver, method, List.of(arguments));
	}

	static Binary binary(Expression left, BinaryOperator operator, Expression right) {
		return new Binary(operator, left, right);
	}
}
