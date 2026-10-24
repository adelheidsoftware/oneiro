package org.adelheid.oneiro.lua;

import static org.adelheid.oneiro.lua.Doc.HARD_LINE;
import static org.adelheid.oneiro.lua.Doc.NOTHING_OR_LINE;
import static org.adelheid.oneiro.lua.Doc.SPACE_OR_LINE;
import static org.adelheid.oneiro.lua.Doc.concat;
import static org.adelheid.oneiro.lua.Doc.group;
import static org.adelheid.oneiro.lua.Doc.indent;
import static org.adelheid.oneiro.lua.Doc.text;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Writes the Lua tree as text in the house style: four-space indentation, 120 columns, and StyLua's line breaking for
 * the constructs Oneiro emits. A call's arguments, a parameter list or a table goes one item per line when it does
 * not fit; a long {@code if} or {@code while} condition moves onto its own lines between the keywords; a long operator
 * chain breaks before each operator; and an assignment's value moves to the next line when it fits there.
 */
public final class LuaPrinter {

	private static final int WIDTH = 120;

	// The source position of the statement being printed, re-marked on its closing lines (end, else, until)
	private @Nullable SourcePosition enclosing;

	private LuaPrinter() {
	}

	public static PrintedLua print(Chunk chunk) {
		DocRenderer.Rendered rendered = new DocRenderer(WIDTH).render(new LuaPrinter().chunk(chunk));
		return new PrintedLua(rendered.text(), new LineMap(rendered.lineOrigins()));
	}

	private Doc chunk(Chunk chunk) {
		List<Doc> parts = new ArrayList<>();
		for (Comment comment : chunk.header()) {
			parts.add(text(comment.render()));
			parts.add(HARD_LINE);
		}
		if (!chunk.header().isEmpty() && !chunk.body().isEmpty()) {
			parts.add(HARD_LINE);
		}
		if (!chunk.body().isEmpty()) {
			parts.add(block(chunk.body(), false));
			parts.add(HARD_LINE);
		}
		return concat(parts);
	}

	/** The block's statements, one per line; {@code breakFirst} starts with a line break, as inside a function. */
	private Doc block(Block block, boolean breakFirst) {
		List<Doc> parts = new ArrayList<>();
		boolean first = true;
		for (Block.Entry entry : block.entries()) {
			if (!first || breakFirst) {
				parts.add(HARD_LINE);
			}
			if (!first && entry.trivia().blankLineBefore()) {
				parts.add(HARD_LINE);
			}
			parts.add(entry(entry));
			first = false;
		}
		for (Comment comment : block.closingComments()) {
			if (!first || breakFirst) {
				parts.add(HARD_LINE);
			}
			parts.add(text(comment.render()));
			first = false;
		}
		return concat(parts);
	}

	private Doc entry(Block.Entry entry) {
		@Nullable SourcePosition outer = enclosing;
		enclosing = entry.origin();
		List<Doc> parts = new ArrayList<>();
		parts.add(new Doc.Mark(entry.origin()));
		for (Comment comment : entry.trivia().leading()) {
			parts.add(text(comment.render()));
			parts.add(HARD_LINE);
		}
		parts.add(statement(entry.statement()));
		String trailing = entry.trivia().trailing();
		if (trailing != null) {
			parts.add(text(" " + Comment.line(trailing).render()));
		}
		enclosing = outer;
		return concat(parts);
	}

	private Doc statement(Statement statement) {
		return switch (statement) {
			case Statement.Local local -> local.values().isEmpty()
					? text("local " + String.join(", ", local.names()))
					: assignment(text("local " + String.join(", ", local.names())), local.values());
			case Statement.Assignment assignment -> assignment(list(assignment.targets()), assignment.values());
			case Statement.CallStatement call -> expression(call.call());
			case Statement.If conditional -> ifStatement(conditional);
			case Statement.While loop ->
					concat(header("while", loop.condition(), "do"), body(loop.body()), closing("end"));
			case Statement.Do block -> concat(text("do"), body(block.body()), closing("end"));
			case Statement.Repeat loop ->
					concat(text("repeat"), body(loop.body()), closing("until "), expression(loop.condition()));
			case Statement.NumericFor loop -> concat(numericFor(loop), body(loop.body()), closing("end"));
			case Statement.GenericFor loop -> concat(
					text("for " + String.join(", ", loop.names()) + " in "),
					list(loop.expressions()),
					text(" do"),
					body(loop.body()),
					closing("end"));
			case Statement.FunctionDeclaration declaration ->
					concat(text("function " + functionName(declaration)), function(declaration.function()));
			case Statement.LocalFunction local ->
					concat(text("local function " + local.name()), function(local.function()));
			case Statement.Return returned -> returnStatement(returned);
			case Statement.Break _ -> text("break");
			case Statement.Goto jump -> text("goto " + jump.label());
			case Statement.Label label -> text("::" + label.name() + "::");
			case Statement.Verbatim verbatim -> {
				List<Doc> lines = new ArrayList<>();
				for (String line : verbatim.lines()) {
					if (!lines.isEmpty()) {
						lines.add(HARD_LINE);
					}
					lines.add(text(line));
				}
				yield concat(lines);
			}
		};
	}

	/**
	 * {@code head = values}. A single operator chain breaks before its operators, as StyLua does; anything else hangs:
	 * on the next line when it fits there, otherwise broken in place.
	 */
	private Doc assignment(Doc head, List<Expression> values) {
		Doc target = concat(head, text(" ="));
		if (values.size() == 1 && values.getFirst() instanceof Expression.Binary binary) {
			return group(target, text(" "), chain(binary, true));
		}
		return new Doc.Hang(target, list(values));
	}

	private Doc returnStatement(Statement.Return statement) {
		if (statement.values().isEmpty()) {
			return text("return");
		}
		if (statement.values().size() == 1 && statement.values().getFirst() instanceof Expression.Binary binary) {
			return group(text("return "), chain(binary, true));
		}
		return concat(text("return "), list(statement.values()));
	}

	private Doc ifStatement(Statement.If statement) {
		List<Doc> parts = new ArrayList<>();
		for (Statement.If.Branch branch : statement.branches()) {
			if (parts.isEmpty()) {
				parts.add(header("if", branch.condition(), "then"));
			} else {
				parts.add(lineOfStatement());
				parts.add(header("elseif", branch.condition(), "then"));
			}
			parts.add(body(branch.body()));
		}
		Block orElse = statement.orElse();
		if (orElse != null) {
			parts.add(closing("else"));
			parts.add(body(orElse));
		}
		parts.add(closing("end"));
		return concat(parts);
	}

	/**
	 * {@code if condition then} on one line, or, when that does not fit, StyLua's form: the keywords on lines of their
	 * own and the condition indented between them. An operator chain then breaks at every operator, even if it would
	 * fit on its line, because its line breaks belong to the header's group; any other condition stays whole.
	 */
	private Doc header(String keyword, Expression condition, String closingKeyword) {
		Doc conditionDoc = condition instanceof Expression.Binary binary
				? chain(binary, false)
				: expression(condition);
		return group(text(keyword), indent(SPACE_OR_LINE, conditionDoc), SPACE_OR_LINE, text(closingKeyword));
	}

	private Doc numericFor(Statement.NumericFor loop) {
		List<Doc> parts = new ArrayList<>();
		parts.add(text("for " + loop.variable() + " = "));
		parts.add(expression(loop.start()));
		parts.add(text(", "));
		parts.add(expression(loop.limit()));
		Expression step = loop.step();
		if (step != null) {
			parts.add(text(", "));
			parts.add(expression(step));
		}
		parts.add(text(" do"));
		return concat(parts);
	}

	/** A block inside a statement: one level deeper, starting on the line after the statement's header. */
	private Doc body(Block block) {
		return new Doc.Indent(block(block, true));
	}

	/**
	 * A line break and the keyword that closes or continues the statement: {@code end}, {@code else}, {@code until}.
	 */
	private Doc closing(String keyword) {
		return concat(lineOfStatement(), text(keyword));
	}

	/** A line break to a line that belongs to the statement itself, such as its {@code elseif} or {@code end}. */
	private Doc lineOfStatement() {
		return concat(new Doc.Mark(enclosing), HARD_LINE);
	}

	private static String functionName(Statement.FunctionDeclaration declaration) {
		String path = String.join(".", declaration.path());
		String method = declaration.method();
		return method == null ? path : path + ":" + method;
	}

	/** Parameters and body; an empty body closes on the same line, {@code function() end}, as StyLua writes it. */
	private Doc function(FunctionBody function) {
		List<Doc> parameters = new ArrayList<>();
		function.parameters().forEach(name -> parameters.add(text(name)));
		if (function.varargs()) {
			parameters.add(text("..."));
		}
		Doc parameterList = parenthesized(parameters);
		if (function.body().isEmpty()) {
			return concat(parameterList, text(" end"));
		}
		return concat(parameterList, body(function.body()), closing("end"));
	}

	private Doc expression(Expression expression) {
		return switch (expression) {
			case Expression.Nil _ -> text("nil");
			case Expression.BooleanLiteral literal -> text(literal.value() ? "true" : "false");
			case Expression.NumberLiteral literal -> text(literal.text());
			case Expression.StringLiteral literal -> string(literal);
			case Expression.Vararg _ -> text("...");
			case Expression.Name name -> text(name.name());
			case Expression.Index index ->
					concat(prefix(index.target()), text("["), expression(index.key()), text("]"));
			case Expression.Field field -> concat(prefix(field.target()), text("." + field.name()));
			case Expression.Call call -> concat(prefix(call.function()), arguments(call.arguments()));
			case Expression.MethodCall call ->
					concat(prefix(call.receiver()), text(":" + call.method()), arguments(call.arguments()));
			case Expression.FunctionExpression function -> concat(text("function"), function(function.function()));
			case Expression.Binary binary -> group(chain(binary, true));
			case Expression.Unary unary -> unary(unary);
			case Expression.Table table -> table(table);
			case Expression.Parenthesized parenthesized ->
					concat(text("("), expression(parenthesized.inner()), text(")"));
		};
	}

	private static Doc string(Expression.StringLiteral literal) {
		if (literal.longBracket()) {
			String longBracket = LuaStrings.longBracket(literal.value());
			if (longBracket != null) {
				return new Doc.Verbatim(longBracket);
			}
		}
		return text(LuaStrings.quoted(literal.value()));
	}

	/**
	 * An operator chain at one precedence, such as {@code a + b * c - d}: on one line when its group fits, otherwise
	 * broken before each of its operators. After {@code local x =} or {@code return} the continuation lines go one
	 * level deeper; inside a broken {@code if} condition they line up with the first operand.
	 */
	private Doc chain(Expression.Binary binary, boolean indentContinuation) {
		BinaryOperator level = binary.operator();
		List<Expression> operands = new ArrayList<>();
		List<BinaryOperator> operators = new ArrayList<>();
		flatten(binary, level.precedence(), operands, operators);
		boolean rightAssociative = level.rightAssociative();
		List<Doc> continuation = new ArrayList<>();
		for (int index = 0; index < operators.size(); index++) {
			boolean leftSide = rightAssociative && index + 1 < operands.size() - 1;
			continuation.add(SPACE_OR_LINE);
			continuation.add(text(operators.get(index).symbol() + " "));
			continuation.add(operand(operands.get(index + 1), level, leftSide));
		}
		Doc first = operand(operands.getFirst(), level, true);
		Doc rest = concat(continuation);
		return concat(first, indentContinuation ? new Doc.Indent(rest) : rest);
	}

	/**
	 * Collects the operands and operators of a chain at one precedence, following the side the operators group on:
	 * the left for most, the right for {@code ..} and {@code ^}. An operand on the other side stays whole and keeps
	 * its parentheses.
	 */
	private static void flatten(
			Expression expression, int precedence, List<Expression> operands, List<BinaryOperator> operators) {
		if (!(expression instanceof Expression.Binary binary) || binary.operator().precedence() != precedence) {
			operands.add(expression);
			return;
		}
		if (binary.operator().rightAssociative()) {
			operands.add(binary.left());
			operators.add(binary.operator());
			flatten(binary.right(), precedence, operands, operators);
		} else {
			flatten(binary.left(), precedence, operands, operators);
			operators.add(binary.operator());
			operands.add(binary.right());
		}
	}

	private Doc operand(Expression operand, BinaryOperator parent, boolean leftSide) {
		Doc doc = expression(operand);
		return needsParentheses(operand, parent, leftSide) ? concat(text("("), doc, text(")")) : doc;
	}

	private static boolean needsParentheses(Expression operand, BinaryOperator parent, boolean leftSide) {
		return switch (operand) {
			case Expression.Binary binary -> binary.operator().precedence() < parent.precedence()
					|| (binary.operator().precedence() == parent.precedence()
							&& (parent.rightAssociative() ? leftSide : !leftSide));
			// Only ^ binds tighter than a unary operator, and only on its left: (-x) ^ 2, but 2 ^ -x
			case Expression.Unary _ -> parent.precedence() > UnaryOperator.PRECEDENCE && leftSide;
			default -> false;
		};
	}

	private Doc unary(Expression.Unary unary) {
		Expression operand = unary.operand();
		// A binary operand below unary precedence needs parentheses, -(a + b) and not (a and b); so does a negated
		// negation, which would otherwise print as --, the start of a comment
		boolean parenthesize = (operand instanceof Expression.Binary binary
				&& binary.operator().precedence() < UnaryOperator.PRECEDENCE)
				|| (unary.operator() == UnaryOperator.NEGATE
						&& operand instanceof Expression.Unary inner
						&& inner.operator() == UnaryOperator.NEGATE);
		Doc operandDoc = expression(operand);
		return concat(
				text(unary.operator().prefix()),
				parenthesize ? concat(text("("), operandDoc, text(")")) : operandDoc);
	}

	/** The target of a call, index or field access, in parentheses unless Lua accepts it bare: {@code ("x"):rep(3)}. */
	private Doc prefix(Expression target) {
		boolean bare = target instanceof Expression.Name
				|| target instanceof Expression.Index
				|| target instanceof Expression.Field
				|| target instanceof Expression.Call
				|| target instanceof Expression.MethodCall
				|| target instanceof Expression.Parenthesized;
		Doc doc = expression(target);
		return bare ? doc : concat(text("("), doc, text(")"));
	}

	/**
	 * A call's arguments, as StyLua writes them. A table that is the only argument opens on the call's line and closes
	 * next to the parenthesis. When an argument cannot be on one line, a function with a body or a table holding one
	 * or a comment, all the arguments stay on the call's line and that one breaks inside, as in
	 * {@code pcall(function() ... end)}, if the line up to its first break fits. Otherwise the arguments go one per
	 * line when they do not fit.
	 */
	private Doc arguments(List<Expression> arguments) {
		if (arguments.isEmpty()) {
			return text("()");
		}
		List<Doc> docs = arguments.stream().map(this::expression).toList();
		if (arguments.size() == 1 && arguments.getFirst() instanceof Expression.Table) {
			return hugged(docs);
		}
		boolean hug = false;
		for (int index = 0; index < arguments.size(); index++) {
			Expression argument = arguments.get(index);
			hug |= (argument instanceof Expression.FunctionExpression || argument instanceof Expression.Table)
					&& Doc.breaks(docs.get(index));
		}
		return hug ? new Doc.Hug(hugged(docs), parenthesized(docs)) : parenthesized(docs);
	}

	/** {@code (a, b)} with no line breaks of its own, the arguments breaking inside where they must. */
	private static Doc hugged(List<Doc> arguments) {
		List<Doc> parts = new ArrayList<>();
		parts.add(text("("));
		for (Doc argument : arguments) {
			if (parts.size() > 1) {
				parts.add(text(", "));
			}
			parts.add(argument);
		}
		parts.add(text(")"));
		return concat(parts);
	}

	/** {@code (a, b)}, or one item per line with the closing parenthesis on its own line and no trailing comma. */
	private static Doc parenthesized(List<Doc> items) {
		if (items.isEmpty()) {
			return text("()");
		}
		List<Doc> inner = new ArrayList<>();
		for (Doc item : items) {
			if (!inner.isEmpty()) {
				inner.add(text(","));
				inner.add(SPACE_OR_LINE);
			}
			inner.add(item);
		}
		return group(text("("), indent(NOTHING_OR_LINE, concat(inner)), NOTHING_OR_LINE, text(")"));
	}

	/** {@code { a, b }} on one line, or one field per line with a trailing comma. */
	private Doc table(Expression.Table table) {
		if (table.fields().isEmpty()) {
			return text("{}");
		}
		List<Doc> inner = new ArrayList<>();
		for (Expression.TableField field : table.fields()) {
			if (!inner.isEmpty()) {
				inner.add(text(","));
				inner.add(SPACE_OR_LINE);
			}
			inner.add(field(field));
		}
		inner.add(new Doc.FlatOrBroken("", ","));
		return group(text("{"), indent(SPACE_OR_LINE, concat(inner)), SPACE_OR_LINE, text("}"));
	}

	private Doc field(Expression.TableField field) {
		return switch (field) {
			case Expression.TableField.Positional positional -> expression(positional.value());
			case Expression.TableField.Named named -> concat(text(named.name() + " = "), expression(named.value()));
			case Expression.TableField.Keyed keyed ->
					concat(text("["), expression(keyed.key()), text("] = "), expression(keyed.value()));
			case Expression.TableField.Commented commented -> {
				List<Doc> parts = new ArrayList<>();
				for (Comment comment : commented.comments()) {
					parts.add(text(comment.render()));
					parts.add(HARD_LINE);
				}
				parts.add(field(commented.field()));
				yield concat(parts);
			}
		};
	}

	/** Expressions separated by commas, as in {@code local a, b = x, y}. */
	private Doc list(List<Expression> expressions) {
		List<Doc> parts = new ArrayList<>();
		for (Expression expression : expressions) {
			if (!parts.isEmpty()) {
				parts.add(text(", "));
			}
			parts.add(expression(expression));
		}
		return concat(parts);
	}
}
