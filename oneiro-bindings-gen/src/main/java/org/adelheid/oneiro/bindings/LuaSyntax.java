package org.adelheid.oneiro.bindings;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Parses Lua 5.4 into what {@link LuaCatsReader} needs of a file: its top-level statements, each with the comments
 * before it and the parts that can declare an API (the names a local or an assignment binds and the values it binds
 * them to, a function's name and parameters, what a return gives), and the comments after the last statement. Nested
 * blocks, such as function bodies, are parsed in full, so that each statement ends where Lua ends it, and what they
 * hold is dropped.
 */
final class LuaSyntax {

	/** Lua that is not valid, at the line where the parser found it. */
	static final class SyntaxError extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private final int line;

		SyntaxError(int line, String message) {
			super(message);
			this.line = line;
		}

		int line() {
			return line;
		}
	}

	/**
	 * A comment.
	 *
	 * @param text what follows its {@code --}, without trailing whitespace; for a long comment, what its brackets hold
	 */
	record Comment(String text, int line, int endLine, boolean isLong) {
	}

	/** A file: its top-level statements, and the comments after the last one. */
	record Chunk(List<Statement> statements, List<Comment> trailing) {

		Chunk {
			statements = List.copyOf(statements);
			trailing = List.copyOf(trailing);
		}
	}

	/** A top-level statement, with the comments before it that do not trail other code on their line. */
	sealed interface Statement {

		List<Comment> comments();

		int line();

		int endLine();
	}

	/** {@code local a, b = x, y}. */
	record Local(List<String> names, List<Expression> values, List<Comment> comments, int line, int endLine)
			implements Statement {
	}

	/** {@code a.b, c = x, y}: each target is the names of its path, or empty when the target indexes or calls. */
	record Assignment(List<List<String>> targets, List<Expression> values, List<Comment> comments, int line,
			int endLine) implements Statement {
	}

	/** {@code function a.b:c(x, y) ... end}: the path names the function, its last name included. */
	record FunctionStatement(List<String> path, boolean method, List<String> parameters, List<Comment> comments,
			int line, int endLine) implements Statement {
	}

	/** {@code local function f(x) ... end}. */
	record LocalFunction(String name, List<String> parameters, List<Comment> comments, int line, int endLine)
			implements Statement {
	}

	/** {@code return x}. */
	record Return(List<Expression> values, List<Comment> comments, int line, int endLine) implements Statement {
	}

	/** Any other statement: a call, a loop, a block, a label. */
	record OtherStatement(List<Comment> comments, int line, int endLine) implements Statement {
	}

	/** An expression, as far as it can declare an API. */
	sealed interface Expression {
	}

	/** A table constructor. */
	record Table(List<Field> fields) implements Expression {
	}

	/**
	 * A field of a table constructor: {@code name = value}, {@code ["key"] = value}, or another kind, whose name and
	 * key are both null.
	 *
	 * @param comments the comments before the field that do not trail other code
	 * @param trailing the comment after the field on its line, such as an enum value's {@code -- 0}
	 */
	record Field(@Nullable String name, @Nullable String key, Expression value, List<Comment> comments,
			@Nullable Comment trailing, int line, int endLine) {
	}

	/** {@code function(x, y) ... end}. */
	record FunctionLiteral(List<String> parameters) implements Expression {
	}

	/** {@code require "path"} or {@code require("path")}, with a literal path. */
	record Require(String module) implements Expression {
	}

	/** {@code a.b.c}: a name, or names joined by dots. */
	record Path(List<String> names) implements Expression {
	}

	/** A literal: a string's value, a number as written with its sign, {@code true}, {@code false} or {@code nil}. */
	record Literal(LiteralKind kind, String value) implements Expression {
	}

	/** What a literal is. */
	enum LiteralKind {
		STRING,
		NUMBER,
		BOOLEAN,
		NIL,
	}

	/** Any other expression: a call, an operation, an index. */
	record OtherExpression() implements Expression {
	}

	private static final OtherExpression OTHER = new OtherExpression();
	private static final Set<String> BINARY_OPERATORS = Set.of("+", "-", "*", "/", "//", "%", "^", "..", "==", "~=",
			"<", "<=", ">", ">=", "and", "or", "&", "|", "~", "<<", ">>");
	private static final Set<String> BLOCK_ENDS = Set.of("end", "else", "elseif", "until");

	private final List<LuaLexer.Token> tokens;
	private int position;
	/** The comments read past since the last statement or field began. */
	private final List<LuaLexer.Token> comments = new ArrayList<>();
	/** The line the last token consumed ended on. */
	private int previousEndLine;

	private LuaSyntax(List<LuaLexer.Token> tokens) {
		this.tokens = tokens;
	}

	/** Parses a file's source. */
	static Chunk parse(String source) {
		return new LuaSyntax(LuaLexer.tokenize(source)).chunk();
	}

	private Chunk chunk() {
		List<Statement> statements = new ArrayList<>();
		while (peek().kind() != LuaLexer.Kind.END) {
			if (isBlockEnd()) {
				throw error("'<eof>' expected");
			}
			Statement statement = statement();
			if (statement != null) {
				statements.add(statement);
			}
		}
		return new Chunk(statements, takeComments());
	}

	/** One statement; null for an empty one, {@code ;}. */
	private @Nullable Statement statement() {
		List<Comment> leading = takeComments();
		LuaLexer.Token first = peek();
		int line = first.line();
		if (first.is(";")) {
			next();
			return null;
		}
		if (first.is("::")) {
			next();
			name();
			expect("::");
			return other(leading, line);
		}
		if (first.kind() == LuaLexer.Kind.KEYWORD) {
			return switch (first.text()) {
				case "if" -> {
					next();
					expression();
					expect("then");
					block();
					while (accept("elseif")) {
						expression();
						expect("then");
						block();
					}
					if (accept("else")) {
						block();
					}
					expect("end");
					yield other(leading, line);
				}
				case "while" -> {
					next();
					expression();
					expect("do");
					block();
					expect("end");
					yield other(leading, line);
				}
				case "do" -> {
					next();
					block();
					expect("end");
					yield other(leading, line);
				}
				case "for" -> {
					next();
					name();
					if (accept("=")) {
						expression();
						expect(",");
						expression();
						if (accept(",")) {
							expression();
						}
					} else {
						while (accept(",")) {
							name();
						}
						expect("in");
						expressions();
					}
					expect("do");
					block();
					expect("end");
					yield other(leading, line);
				}
				case "repeat" -> {
					next();
					block();
					expect("until");
					expression();
					yield other(leading, line);
				}
				case "function" -> {
					next();
					List<String> path = new ArrayList<>(List.of(name()));
					boolean method = false;
					while (accept(".")) {
						path.add(name());
					}
					if (accept(":")) {
						path.add(name());
						method = true;
					}
					List<String> parameters = functionBody();
					yield new FunctionStatement(List.copyOf(path), method, parameters, leading, line, previousEndLine);
				}
				case "local" -> {
					next();
					if (accept("function")) {
						String name = name();
						List<String> parameters = functionBody();
						yield new LocalFunction(name, parameters, leading, line, previousEndLine);
					}
					List<String> names = new ArrayList<>();
					do {
						names.add(name());
						// An attribute, <const> or <close>
						if (accept("<")) {
							name();
							expect(">");
						}
					} while (accept(","));
					List<Expression> values = accept("=") ? expressions() : List.of();
					yield new Local(List.copyOf(names), values, leading, line, previousEndLine);
				}
				case "return" -> {
					next();
					List<Expression> values = isBlockEnd() || check(";") ? List.of() : expressions();
					accept(";");
					yield new Return(values, leading, line, previousEndLine);
				}
				case "break" -> {
					next();
					yield other(leading, line);
				}
				case "goto" -> {
					next();
					name();
					yield other(leading, line);
				}
				default -> throw error("unexpected keyword");
			};
		}
		// An assignment, or a call
		Expression target = suffixed();
		if (check("=") || check(",")) {
			List<List<String>> targets = new ArrayList<>();
			targets.add(pathOf(target));
			while (accept(",")) {
				targets.add(pathOf(suffixed()));
			}
			expect("=");
			List<Expression> values = expressions();
			return new Assignment(List.copyOf(targets), values, leading, line, previousEndLine);
		}
		return other(leading, line);
	}

	private OtherStatement other(List<Comment> leading, int line) {
		return new OtherStatement(leading, line, previousEndLine);
	}

	private static List<String> pathOf(Expression target) {
		return target instanceof Path path ? path.names() : List.of();
	}

	private void block() {
		while (!isBlockEnd()) {
			statement();
		}
	}

	private boolean isBlockEnd() {
		LuaLexer.Token token = peek();
		return token.kind() == LuaLexer.Kind.END
				|| (token.kind() == LuaLexer.Kind.KEYWORD && BLOCK_ENDS.contains(token.text()));
	}

	/** {@code (x, y) ... end}, returning the parameters' names, {@code ...} included. */
	private List<String> functionBody() {
		expect("(");
		List<String> parameters = new ArrayList<>();
		if (!check(")")) {
			do {
				if (accept("...")) {
					parameters.add("...");
					break;
				}
				parameters.add(name());
			} while (accept(","));
		}
		expect(")");
		block();
		expect("end");
		return List.copyOf(parameters);
	}

	private List<Expression> expressions() {
		List<Expression> list = new ArrayList<>(List.of(expression()));
		while (accept(",")) {
			list.add(expression());
		}
		return List.copyOf(list);
	}

	/**
	 * An expression. Operators are read only to find where it ends, without their precedence: an operation is
	 * {@link OtherExpression} whatever its operands, but for a negative number.
	 */
	private Expression expression() {
		LuaLexer.Token token = peek();
		if (token.is("not") || token.is("-") || token.is("#") || token.is("~")) {
			next();
			Expression operand = expression();
			return token.is("-") && operand instanceof Literal literal && literal.kind() == LiteralKind.NUMBER
					? new Literal(LiteralKind.NUMBER, "-" + literal.value())
					: OTHER;
		}
		Expression result = simple();
		while (isBinaryOperator(peek())) {
			next();
			expression();
			result = OTHER;
		}
		return result;
	}

	private static boolean isBinaryOperator(LuaLexer.Token token) {
		return (token.kind() == LuaLexer.Kind.SYMBOL || token.kind() == LuaLexer.Kind.KEYWORD)
				&& BINARY_OPERATORS.contains(token.text());
	}

	private Expression simple() {
		LuaLexer.Token token = peek();
		if (token.kind() == LuaLexer.Kind.NUMBER) {
			next();
			return new Literal(LiteralKind.NUMBER, token.text());
		}
		if (token.kind() == LuaLexer.Kind.STRING) {
			next();
			return new Literal(LiteralKind.STRING, token.value());
		}
		if (token.is("nil")) {
			next();
			return new Literal(LiteralKind.NIL, "nil");
		}
		if (token.is("true") || token.is("false")) {
			next();
			return new Literal(LiteralKind.BOOLEAN, token.text());
		}
		if (token.is("...")) {
			next();
			return OTHER;
		}
		if (token.is("{")) {
			return table();
		}
		if (token.is("function")) {
			next();
			return new FunctionLiteral(functionBody());
		}
		return suffixed();
	}

	/** A name or a parenthesised expression, followed by field accesses, indexes and calls. */
	private Expression suffixed() {
		LuaLexer.Token token = peek();
		// The names, while the expression is a plain path
		List<String> path = null;
		Expression result = OTHER;
		if (token.kind() == LuaLexer.Kind.NAME) {
			next();
			path = new ArrayList<>(List.of(token.text()));
		} else if (token.is("(")) {
			next();
			expression();
			expect(")");
		} else {
			throw error("unexpected symbol");
		}
		while (true) {
			LuaLexer.Token suffix = peek();
			if (suffix.is(".")) {
				next();
				String name = name();
				if (path != null) {
					path.add(name);
				} else {
					result = OTHER;
				}
			} else if (suffix.is("[")) {
				next();
				expression();
				expect("]");
				path = null;
				result = OTHER;
			} else if (suffix.is(":")) {
				next();
				name();
				arguments();
				path = null;
				result = OTHER;
			} else if (suffix.is("(") || suffix.is("{") || suffix.kind() == LuaLexer.Kind.STRING) {
				List<Expression> arguments = arguments();
				boolean require = path != null && path.equals(List.of("require")) && arguments.size() == 1
						&& arguments.getFirst() instanceof Literal literal && literal.kind() == LiteralKind.STRING;
				result = require ? new Require(((Literal) arguments.getFirst()).value()) : OTHER;
				path = null;
			} else {
				break;
			}
		}
		return path != null ? new Path(List.copyOf(path)) : result;
	}

	private List<Expression> arguments() {
		LuaLexer.Token token = peek();
		if (token.kind() == LuaLexer.Kind.STRING) {
			next();
			return List.of(new Literal(LiteralKind.STRING, token.value()));
		}
		if (token.is("{")) {
			return List.of(table());
		}
		expect("(");
		List<Expression> arguments = check(")") ? List.of() : expressions();
		expect(")");
		return arguments;
	}

	private Table table() {
		expect("{");
		List<Field> fields = new ArrayList<>();
		while (!check("}")) {
			List<Comment> leading = takeComments();
			LuaLexer.Token start = peek();
			String name = null;
			String key = null;
			Expression value;
			if (start.is("[")) {
				next();
				Expression keyExpression = expression();
				expect("]");
				expect("=");
				if (keyExpression instanceof Literal literal && literal.kind() == LiteralKind.STRING) {
					key = literal.value();
				}
				value = expression();
			} else if (start.kind() == LuaLexer.Kind.NAME && second().is("=")) {
				next();
				next();
				name = start.text();
				value = expression();
			} else {
				value = expression();
			}
			int endLine = previousEndLine;
			boolean separated = accept(",") || accept(";");
			fields.add(new Field(name, key, value, leading, trailingComment(), start.line(), endLine));
			if (!separated) {
				break;
			}
		}
		takeComments();
		expect("}");
		return new Table(List.copyOf(fields));
	}

	/** The comment that trails the last token consumed on its line, taken from the comments read past. */
	private @Nullable Comment trailingComment() {
		peek();
		for (int index = 0; index < comments.size(); index++) {
			LuaLexer.Token comment = comments.get(index);
			if (comment.trailing() && comment.line() == previousEndLine) {
				comments.remove(index);
				return comment(comment);
			}
		}
		return null;
	}

	/** The comments read past that start their line; those that trail code are dropped. */
	private List<Comment> takeComments() {
		List<Comment> taken = new ArrayList<>();
		for (LuaLexer.Token comment : comments) {
			if (!comment.trailing()) {
				taken.add(comment(comment));
			}
		}
		comments.clear();
		return List.copyOf(taken);
	}

	private static Comment comment(LuaLexer.Token token) {
		return new Comment(token.text(), token.line(), token.endLine(), token.kind() == LuaLexer.Kind.LONG_COMMENT);
	}

	/** The next token that is not a comment; the comments before it join those read past. */
	private LuaLexer.Token peek() {
		while (true) {
			LuaLexer.Token token = tokens.get(position);
			if (token.kind() != LuaLexer.Kind.COMMENT && token.kind() != LuaLexer.Kind.LONG_COMMENT) {
				return token;
			}
			comments.add(token);
			position++;
		}
	}

	/** The token after the next one, comments skipped, without reading past either. */
	private LuaLexer.Token second() {
		peek();
		for (int index = position + 1; index < tokens.size(); index++) {
			LuaLexer.Token token = tokens.get(index);
			if (token.kind() != LuaLexer.Kind.COMMENT && token.kind() != LuaLexer.Kind.LONG_COMMENT) {
				return token;
			}
		}
		return tokens.getLast();
	}

	private LuaLexer.Token next() {
		LuaLexer.Token token = peek();
		position++;
		previousEndLine = token.endLine();
		return token;
	}

	private boolean check(String symbolOrKeyword) {
		return peek().is(symbolOrKeyword);
	}

	private boolean accept(String symbolOrKeyword) {
		if (check(symbolOrKeyword)) {
			next();
			return true;
		}
		return false;
	}

	private void expect(String symbolOrKeyword) {
		if (!check(symbolOrKeyword)) {
			throw error("'" + symbolOrKeyword + "' expected");
		}
		next();
	}

	private String name() {
		LuaLexer.Token token = peek();
		if (token.kind() != LuaLexer.Kind.NAME) {
			throw error("name expected");
		}
		next();
		return token.text();
	}

	private SyntaxError error(String message) {
		LuaLexer.Token token = peek();
		String near = token.kind() == LuaLexer.Kind.END ? "<eof>" : token.text();
		return new SyntaxError(token.line(), message + " near '" + near + "'");
	}
}
