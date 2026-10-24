package org.adelheid.oneiro.java;

import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.integer;
import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Comment;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.Statement;
import org.jspecify.annotations.Nullable;

/**
 * Loops as Lua loops. A counting {@code for} becomes a numeric for where its variable, bound and step allow, counting
 * from 1 where {@link OneBasedLoops} chose that; any other {@code for} becomes a while loop with its update at
 * the end of the body. A for-each walks an array, a list, a set, a map's entries, keys or values, or the
 * characters of a string. A {@code continue} jumps to a label at the end of its loop's body.
 */
final class Loops {

	// The loops being lowered, innermost first
	private final Deque<Loop> enclosing = new ArrayDeque<>();

	private final BodyLowering body;
	private final UnitLowering unit;
	private final SourceFacts facts;
	private final JavaTypes types;

	Loops(BodyLowering body) {
		this.body = body;
		this.unit = body.unit;
		this.facts = body.facts();
		this.types = body.types();
	}

	/** A loop being lowered, and the label its {@code continue} statements jump to, or null when it has none. */
	private record Loop(@Nullable String continueLabel) {
	}

	/**
	 * A loop body, with the label its {@code continue} statements jump to as its last statement. When statements must
	 * follow the label (a {@code for} loop's update, run after a {@code continue} too), the body goes into a
	 * {@code do ... end} block first, because Lua's goto may not jump into the scope of the body's locals.
	 */
	Block loopBody(StatementTree code, List<Statement> update) {
		String label = usesContinue(code) ? continueLabel() : null;
		enclosing.push(new Loop(label));
		Block lowered;
		try {
			lowered = body.body(code);
		} finally {
			enclosing.pop();
		}
		if (label == null && update.isEmpty()) {
			return lowered;
		}
		List<Block.Entry> entries = new ArrayList<>();
		if (label != null && !update.isEmpty()) {
			entries.add(Block.Entry.of(new Statement.Do(lowered)));
		} else {
			entries.addAll(lowered.entries());
		}
		if (label != null) {
			entries.add(Block.Entry.of(new Statement.Label(label)));
		}
		update.forEach(statement -> entries.add(Block.Entry.of(statement)));
		List<Comment> closing = label != null && !update.isEmpty() ? List.of() : lowered.closingComments();
		return new Block(entries, closing);
	}

	/**
	 * The label for a loop's {@code continue}: {@code continue}, or {@code continue2} and up inside a loop that already
	 * has one, because Lua forbids a label where one of the same name is visible.
	 */
	private String continueLabel() {
		long labelled = enclosing.stream().filter(loop -> loop.continueLabel() != null).count();
		return labelled == 0 ? "continue" : "continue" + (labelled + 1);
	}

	/** Whether the body has a {@code continue} for this loop, not counting nested loops, lambdas and classes. */
	private static boolean usesContinue(StatementTree loopBody) {
		return new TreeSearch() {
			@Override
			public Boolean visitContinue(ContinueTree node, Void unused) {
				return node.getLabel() == null;
			}

			@Override
			public Boolean visitWhileLoop(WhileLoopTree node, Void unused) {
				return false;
			}

			@Override
			public Boolean visitDoWhileLoop(DoWhileLoopTree node, Void unused) {
				return false;
			}

			@Override
			public Boolean visitForLoop(ForLoopTree node, Void unused) {
				return false;
			}

			@Override
			public Boolean visitEnhancedForLoop(EnhancedForLoopTree node, Void unused) {
				return false;
			}

			@Override
			public Boolean visitLambdaExpression(LambdaExpressionTree node, Void unused) {
				return false;
			}

			@Override
			public Boolean visitClass(ClassTree node, Void unused) {
				return false;
			}
		}.foundIn(loopBody);
	}

	Statement continueStatement(ContinueTree jump) {
		if (jump.getLabel() != null) {
			throw BodyLowering.labelledJump(jump);
		}
		Loop loop = enclosing.peek();
		if (loop == null || loop.continueLabel() == null) {
			throw new IllegalStateException("continue outside a loop that was scanned for it");
		}
		return new Statement.Goto(loop.continueLabel());
	}

	List<Statement> forLoop(ForLoopTree loop) {
		Statement numeric = numericFor(loop);
		if (numeric != null) {
			return List.of(numeric);
		}
		List<Statement> lowered = new ArrayList<>();
		for (StatementTree initializer : loop.getInitializer()) {
			lowered.addAll(body.statement(initializer));
		}
		Expression condition = loop.getCondition() == null
				? new Expression.BooleanLiteral(true)
				: body.expression(loop.getCondition());
		List<Statement> update = loop.getUpdate().stream()
				.flatMap(step -> body.expressionStatement(step.getExpression()).stream())
				.toList();
		lowered.add(new Statement.While(condition, loopBody(loop.getStatement(), update)));
		return lowered;
	}

	/**
	 * A loop that can be a Lua numeric for: one integer variable starting at {@code start}, compared with a bound that
	 * the body cannot change, and stepped by a constant the body does not change either.
	 */
	private record NumericShape(VariableTree variable, Element element, ExpressionTree start, Tree.Kind comparison,
			ExpressionTree bound, long step) {
	}

	/** The loop's numeric shape, or null when it has another shape and becomes a while loop. */
	private @Nullable NumericShape numericShape(ForLoopTree loop) {
		if (loop.getInitializer().size() != 1
				|| !(loop.getInitializer().getFirst() instanceof VariableTree variable)
				|| !types.isIntegral(unit.type(variable))) {
			return null;
		}
		ExpressionTree start = variable.getInitializer();
		Element element = unit.element(variable);
		if (start == null || !(loop.getCondition() instanceof BinaryTree condition)
				|| !facts.refersTo(condition.getLeftOperand(), element) || loop.getUpdate().size() != 1) {
			return null;
		}
		Long step = step(loop.getUpdate().getFirst().getExpression(), element);
		if (step == null) {
			return null;
		}
		Tree.Kind comparison = condition.getKind();
		boolean shapeFits = step > 0
				? comparison == Tree.Kind.LESS_THAN || comparison == Tree.Kind.LESS_THAN_EQUAL
				: comparison == Tree.Kind.GREATER_THAN || comparison == Tree.Kind.GREATER_THAN_EQUAL;
		ExpressionTree bound = condition.getRightOperand();
		if (!shapeFits || facts.assigns(loop.getStatement(), element)
				|| !facts.unchangedBy(bound, loop.getStatement())) {
			return null;
		}
		return new NumericShape(variable, element, start, comparison, bound, step);
	}

	/** Whether a loop counts up by 1 from 0 while below a bound: the loops that may count from 1 in Lua instead. */
	boolean countsFromZero(ForLoopTree loop) {
		NumericShape shape = numericShape(loop);
		return shape != null && shape.comparison() == Tree.Kind.LESS_THAN && shape.step() == 1
				&& shape.start() instanceof LiteralTree zero && Integer.valueOf(0).equals(zero.getValue());
	}

	/**
	 * A Lua numeric for, when the loop has that shape. {@code i < n} becomes {@code for i = a, n - 1}. A loop from 0
	 * that {@link OneBasedLoops} picks counts from 1 instead: {@code for i = 1, #list} with {@code list[i]}. Null when
	 * the loop has another shape and becomes a while loop.
	 */
	private @Nullable Statement numericFor(ForLoopTree loop) {
		NumericShape shape = numericShape(loop);
		if (shape == null) {
			return null;
		}
		String luaName = body.declare(shape.variable());
		Element element = shape.element();
		if (unit.countsFromOne(element)) {
			body.nonNegative.add(element);
			return new Statement.NumericFor(luaName, integer(1), body.expression(shape.bound()), null,
					loopBody(loop.getStatement(), List.of()));
		}
		Expression limit = switch (shape.comparison()) {
			case LESS_THAN -> IndexArithmetic.minusOne(body.expression(shape.bound()));
			case GREATER_THAN -> IndexArithmetic.plusOne(body.expression(shape.bound()));
			default -> body.expression(shape.bound());
		};
		if (shape.step() > 0 && facts.knownNonNegative(shape.start())) {
			body.nonNegative.add(element);
		}
		Expression stepExpression = shape.step() == 1 ? null : integer(shape.step());
		return new Statement.NumericFor(luaName, body.expression(shape.start()), limit, stepExpression,
				loopBody(loop.getStatement(), List.of()));
	}

	/** The step of {@code i++}, {@code i--}, {@code i += k} or {@code i -= k} for a literal k; null otherwise. */
	private @Nullable Long step(ExpressionTree update, Element variable) {
		if (update instanceof UnaryTree unary && facts.refersTo(unary.getExpression(), variable)) {
			return switch (unary.getKind()) {
				case POSTFIX_INCREMENT, PREFIX_INCREMENT -> 1L;
				case POSTFIX_DECREMENT, PREFIX_DECREMENT -> -1L;
				default -> null;
			};
		}
		if (update instanceof CompoundAssignmentTree compound && facts.refersTo(compound.getVariable(), variable)
				&& compound.getExpression() instanceof LiteralTree literal
				&& literal.getValue() instanceof Number amount && amount.longValue() > 0) {
			return switch (compound.getKind()) {
				case PLUS_ASSIGNMENT -> amount.longValue();
				case MINUS_ASSIGNMENT -> -amount.longValue();
				default -> null;
			};
		}
		return null;
	}

	Statement forEach(EnhancedForLoopTree loop) {
		ExpressionTree iterable = loop.getExpression();
		TypeMirror type = unit.type(iterable);
		TypeMirror variableType = unit.type(loop.getVariable());
		if (!types.isChar(variableType) && (types.isIntegral(variableType) || types.isFloating(variableType))
				&& ((type instanceof ArrayType array && types.isChar(array.getComponentType()))
						|| (types.isList(type) && types.isChar(types.typeArgument(type, 0))))) {
			throw new Rejection(loop.getVariable(), "Each char would reach this loop as a one-character string, not as"
					+ " its number; loop with a char variable and convert it with (int) where the number is needed.");
		}
		// for (char c : text.toCharArray()) walks the text itself, one character at a time
		if (BodyLowering.withoutParentheses(iterable) instanceof MethodInvocationTree call
				&& call.getArguments().isEmpty() && call.getMethodSelect() instanceof MemberSelectTree select
				&& select.getIdentifier().contentEquals("toCharArray")
				&& types.isString(unit.type(select.getExpression()))) {
			String item = body.declare(loop.getVariable());
			Expression characters = Expression.methodCall(body.expression(select.getExpression()), "gmatch",
					Expression.string("."));
			return new Statement.GenericFor(List.of(item), List.of(characters),
					loopBody(loop.getStatement(), List.of()));
		}
		if (type.getKind() == TypeKind.ARRAY || types.isList(type) || types.isDeque(type)) {
			String item = body.declare(loop.getVariable());
			return new Statement.GenericFor(List.of("_", item),
					List.of(call(name("ipairs"), body.expression(iterable))), loopBody(loop.getStatement(), List.of()));
		}
		if (types.isSet(type) && !(iterable instanceof MethodInvocationTree)) {
			String item = body.declare(loop.getVariable());
			return new Statement.GenericFor(List.of(item), List.of(call(name("pairs"), body.expression(iterable))),
					loopBody(loop.getStatement(), List.of()));
		}
		if (iterable instanceof MethodInvocationTree view && view.getArguments().isEmpty()
				&& view.getMethodSelect() instanceof MemberSelectTree select
				&& types.isMap(unit.type(select.getExpression()))) {
			Expression map = call(name("pairs"), body.expression(select.getExpression()));
			String method = select.getIdentifier().toString();
			switch (method) {
				case "entrySet" -> {
					String key = entryLocal(loop.getVariable(), "key");
					String value = entryLocal(loop.getVariable(), "value");
					Element entry = unit.element(loop.getVariable());
					body.entries.put(entry, List.of(key, value));
					Statement lowered = new Statement.GenericFor(List.of(key, value), List.of(map),
							loopBody(loop.getStatement(), List.of()));
					// The entry is out of scope after its loop, so body.entries holds only the loops around the code
					body.entries.remove(entry);
					return lowered;
				}
				case "keySet" -> {
					return new Statement.GenericFor(List.of(body.declare(loop.getVariable())), List.of(map),
							loopBody(loop.getStatement(), List.of()));
				}
				case "values" -> {
					return new Statement.GenericFor(List.of("_", body.declare(loop.getVariable())), List.of(map),
							loopBody(loop.getStatement(), List.of()));
				}
				default -> {
				}
			}
		}
		throw Rejection.notYet(iterable, "A for-each loop over this kind of value");
	}

	/**
	 * The local of an entry's key or value: {@code key} or {@code value}, or the entry variable's name followed by
	 * it where the method or a loop around this one already uses that name, as in {@code cellKey}.
	 */
	private String entryLocal(VariableTree entry, String preferred) {
		Set<String> enclosing = new HashSet<>();
		body.entries.values().forEach(enclosing::addAll);
		String local = unit.freshLocal(preferred, entry);
		if (local.equals(unit.names.luaName(preferred)) && !enclosing.contains(local)) {
			return local;
		}
		String prefixed = entry.getName() + preferred.substring(0, 1).toUpperCase(Locale.ROOT) + preferred.substring(1);
		return unit.freshLocal(prefixed, entry);
	}
}
