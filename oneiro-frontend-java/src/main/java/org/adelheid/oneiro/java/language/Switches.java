package org.adelheid.oneiro.java.language;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CaseLabelTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ConstantCaseLabelTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.DefaultCaseLabelTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.PatternCaseLabelTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.tree.YieldTree;
import com.sun.source.util.TreeScanner;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import javax.lang.model.type.TypeMirror;
import org.adelheid.oneiro.java.core.BodyLowering;
import org.adelheid.oneiro.java.core.CommentIndex;
import org.adelheid.oneiro.java.core.Rejection;
import org.adelheid.oneiro.java.core.SourceFacts;
import org.adelheid.oneiro.java.core.UnitLowering;
import org.adelheid.oneiro.lowering.translation.PlainValues;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Comment;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.SourcePosition;
import org.adelheid.oneiro.lua.Statement;
import org.adelheid.oneiro.lua.Trivia;
import org.jspecify.annotations.Nullable;

/**
 * Switch statements and switch expressions as Lua if-chains: each case's test, its arm's code, and for a switch
 * expression, where each arm's value goes. Java's rules for arms hold in Lua too: an arm does not fall through,
 * and a block arm of a switch expression ends with its yield.
 */
public final class Switches {

	// Where a yield hands its value, for each switch expression being lowered, innermost first
	private final Deque<Function<Expression, Statement>> sinks = new ArrayDeque<>();

	private final BodyLowering body;
	private final UnitLowering unit;

	public Switches(BodyLowering body) {
		this.body = body;
		this.unit = body.unit;
	}

	/** A {@code yield}: its value goes where the innermost switch expression's value goes. */
	public List<Statement> yieldStatement(YieldTree yielded) {
		Function<Expression, Statement> sink = sinks.peek();
		if (sink == null) {
			throw new IllegalStateException("yield outside a switch expression at " + unit.origin(yielded));
		}
		return body.deliver(yielded.getValue(), sink);
	}

	/**
	 * A switch as an if/elseif chain, with {@code default} as the else. The selector goes into a local when it has a
	 * call, which Java evaluates once, or when its Lua is more than a name or field and more than one case compares
	 * it; otherwise each case compares it as it is. With a sink, the switch is an expression, and each case hands its
	 * value to the sink.
	 */
	public List<Statement> switchChain(Tree switchTree, ExpressionTree selectorTree, List<? extends CaseTree> cases,
			@Nullable Function<Expression, Statement> sink) {
		List<Statement> lowered = new ArrayList<>();
		Expression selector = body.expression(selectorTree);
		long comparisons = cases.stream()
				.flatMap(arm -> arm.getLabels().stream())
				.filter(label -> label instanceof ConstantCaseLabelTree || label instanceof PatternCaseLabelTree)
				.count();
		if (SourceFacts.containsCall(selectorTree) || (!PlainValues.isPlain(selector) && comparisons > 1)) {
			String local = body.invent(selectorName(selectorTree), switchTree);
			lowered.add(new Statement.Local(List.of(local), List.of(selector)));
			selector = name(local);
		}
		List<Statement.If.Branch> branches = new ArrayList<>();
		Block orElse = null;
		List<Expression> tests = new ArrayList<>();
		boolean isDefault = false;
		int cursor = unit.end(selectorTree);
		TypeMirror selectorType = unit.type(selectorTree);
		for (int index = 0; index < cases.size(); index++) {
			CaseTree arm = cases.get(index);
			boolean last = index == cases.size() - 1;
			// A pattern that every value matches, case Object o, tests nothing
			boolean unconditional = false;
			for (CaseLabelTree label : arm.getLabels()) {
				switch (label) {
					case ConstantCaseLabelTree constant -> tests.add(binary(selector, BinaryOperator.EQUAL,
							body.expression(constant.getConstantExpression())));
					case DefaultCaseLabelTree _ -> isDefault = true;
					case PatternCaseLabelTree pattern -> {
						VariableTree variable = TypeTests.patternVariable(pattern.getPattern());
						body.declare(variable);
						body.alias(variable, selector);
						TypeMirror type = unit.type(variable);
						if (body.types().isWithin(selectorType, type)) {
							unconditional = true;
						} else {
							tests.add(body.typeTests().typeTest(selector, type, label));
						}
					}
					default -> throw Rejection.notYet(label, "This kind of case label");
				}
			}
			ExpressionTree guard = arm.getGuard();
			Expression condition = tests.stream().reduce((a, b) -> binary(a, BinaryOperator.OR, b)).orElse(null);
			if (guard != null) {
				condition = condition == null
						? body.expression(guard)
						: binary(condition, BinaryOperator.AND, body.expression(guard));
			} else if (unconditional) {
				isDefault = true;
			}
			// case A: case B: with nothing between them share the statements that follow
			if (arm.getCaseKind() == CaseTree.CaseKind.STATEMENT && arm.getStatements().isEmpty() && !last) {
				continue;
			}
			Block code = armBody(arm, cursor, last ? unit.end(switchTree) - 1 : unit.end(arm), sink, last);
			cursor = unit.end(arm);
			if (isDefault) {
				orElse = code;
			} else {
				if (condition == null) {
					throw new IllegalStateException("a case that tests nothing");
				}
				branches.add(new Statement.If.Branch(condition, code));
			}
			tests.clear();
			isDefault = false;
		}
		Block otherwise = orElse == null || orElse.isEmpty() ? null : orElse;
		if (!branches.isEmpty()) {
			lowered.add(new Statement.If(branches, otherwise));
		} else if (otherwise != null) {
			lowered.add(new Statement.Do(otherwise));
		}
		return lowered;
	}

	/** {@code kind} for {@code event.getKind()} or {@code event.kind()}, the name for a selector read into a local. */
	private static String selectorName(ExpressionTree selector) {
		if (BodyLowering.withoutParentheses(selector) instanceof MethodInvocationTree call
				&& call.getArguments().isEmpty()) {
			String method = switch (call.getMethodSelect()) {
				case MemberSelectTree select -> select.getIdentifier().toString();
				case IdentifierTree identifier -> identifier.getName().toString();
				default -> "";
			};
			if (method.length() > 3 && method.startsWith("get") && Character.isUpperCase(method.charAt(3))) {
				return Character.toLowerCase(method.charAt(3)) + method.substring(4);
			}
			if (!method.isEmpty()) {
				return method;
			}
		}
		return "selected";
	}

	/**
	 * One case as the body of an if branch, with the comments from the end of the previous case on, so that a comment
	 * above {@code case} opens its branch.
	 */
	private Block armBody(CaseTree arm, int from, int to, @Nullable Function<Expression, Statement> sink,
			boolean last) {
		if (arm.getCaseKind() == CaseTree.CaseKind.STATEMENT) {
			List<? extends StatementTree> statements = arm.getStatements();
			if (!last && canCompleteNormally(statements)) {
				throw new Rejection(arm, "Lua has no fall-through; repeat the shared code or move it into a method.");
			}
			return caseStatements(statements, arm, from, to, sink);
		}
		Tree code = arm.getBody();
		return switch (code) {
			case BlockTree block -> caseStatements(block.getStatements(), arm, from, to, sink);
			case StatementTree statement -> caseStatements(List.of(statement), arm, from, to, sink);
			case ExpressionTree value -> {
				// The value of a switch expression's case, case A -> 1
				List<Comment> leading = BodyLowering.comments(unit.comments.take(from, unit.start(value)));
				List<Statement> statements = body.statementsOf(value,
						() -> sink != null ? body.deliver(value, sink) : body.expressionStatement(value));
				CommentIndex.SourceComment trailing = unit.comments.takeTrailing(unit.end(value));
				SourcePosition origin = unit.origin(value);
				List<Block.Entry> entries = new ArrayList<>();
				for (int index = 0; index < statements.size(); index++) {
					boolean lastStatement = index == statements.size() - 1;
					Trivia trivia = new Trivia(false, index == 0 ? leading : List.of(),
							lastStatement && trailing != null ? String.join(" ", trailing.lines()) : null);
					entries.add(new Block.Entry(statements.get(index), trivia, origin));
				}
				int closingFrom = trailing != null ? trailing.end() : unit.end(value);
				yield new Block(entries, BodyLowering.comments(unit.comments.take(closingFrom, to)));
			}
			default -> throw new IllegalStateException("a case body of kind " + code.getKind());
		};
	}

	/**
	 * A case's statements without the break that ends the case, and without the braces of a case written as a block,
	 * because each branch of the if is a scope of its own.
	 */
	private Block caseStatements(List<? extends StatementTree> statements, CaseTree arm, int from, int to,
			@Nullable Function<Expression, Statement> sink) {
		List<? extends StatementTree> kept = withoutFinalBreak(statements);
		if (kept.size() == 1 && kept.getFirst() instanceof BlockTree block) {
			kept = withoutFinalBreak(block.getStatements());
		}
		checkNoEarlyBreak(kept);
		if (sink != null) {
			checkYieldsAtEnd(kept);
			sinks.push(sink);
		}
		try {
			Block lowered = body.statements(kept, from, to);
			CommentIndex.SourceComment afterBreak = unit.comments.takeTrailing(unit.end(arm));
			if (afterBreak == null) {
				return lowered;
			}
			List<Comment> closing = new ArrayList<>(lowered.closingComments());
			closing.addAll(BodyLowering.comments(List.of(afterBreak)));
			return new Block(lowered.entries(), closing);
		} finally {
			if (sink != null) {
				sinks.pop();
			}
		}
	}

	private static List<? extends StatementTree> withoutFinalBreak(List<? extends StatementTree> statements) {
		if (!statements.isEmpty() && statements.getLast() instanceof BreakTree jump && jump.getLabel() == null) {
			return statements.subList(0, statements.size() - 1);
		}
		return statements;
	}

	/** Whether control can reach the end of the statements, which in a case would fall through into the next one. */
	static boolean canCompleteNormally(List<? extends StatementTree> statements) {
		if (statements.isEmpty()) {
			return true;
		}
		return switch (statements.getLast()) {
			case BreakTree _, ReturnTree _, ThrowTree _, ContinueTree _, YieldTree _ -> false;
			case BlockTree block -> canCompleteNormally(block.getStatements());
			case IfTree branch -> {
				StatementTree orElse = branch.getElseStatement();
				yield orElse == null || canCompleteNormally(List.of(branch.getThenStatement()))
						|| canCompleteNormally(List.of(orElse));
			}
			default -> true;
		};
	}

	/** A break before the end of a case has no Lua form: Lua cannot leave an if branch early. */
	private static void checkNoEarlyBreak(List<? extends StatementTree> statements) {
		List<BreakTree> found = new ArrayList<>();
		new TreeScanner<Void, Void>() {
			@Override
			public Void visitBreak(BreakTree node, Void unused) {
				if (node.getLabel() == null) {
					found.add(node);
				}
				return null;
			}

			// A break inside these leaves them, not the switch
			@Override
			public Void visitWhileLoop(WhileLoopTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitDoWhileLoop(DoWhileLoopTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitForLoop(ForLoopTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitEnhancedForLoop(EnhancedForLoopTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitSwitch(SwitchTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitClass(ClassTree node, Void unused) {
				return null;
			}
		}.scan(statements, null);
		if (!found.isEmpty()) {
			throw new Rejection(found.getFirst(), "Lua has no switch to leave early, and a break there would leave the"
					+ " enclosing loop instead; use if/else inside the case.");
		}
	}

	/**
	 * Each yield must end a path through its case, as the last statement or the last of an if's branches, because
	 * the Lua that assigns the value cannot skip the statements after it.
	 */
	private static void checkYieldsAtEnd(List<? extends StatementTree> statements) {
		Set<YieldTree> atEnd = yieldsAtEnd(statements);
		List<YieldTree> found = new ArrayList<>();
		new TreeScanner<Void, Void>() {
			@Override
			public Void visitYield(YieldTree node, Void unused) {
				found.add(node);
				return super.visitYield(node, null);
			}

			// Their yields belong to them
			@Override
			public Void visitSwitchExpression(SwitchExpressionTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitClass(ClassTree node, Void unused) {
				return null;
			}
		}.scan(statements, null);
		for (YieldTree yielded : found) {
			if (!atEnd.contains(yielded)) {
				throw new Rejection(yielded, "In Lua each case's value is assigned or returned at its end, which a"
						+ " yield in the middle cannot reach; move the yield to the end of its case, or of each branch"
						+ " of an if there.");
			}
		}
	}

	private static Set<YieldTree> yieldsAtEnd(List<? extends StatementTree> statements) {
		Set<YieldTree> atEnd = new HashSet<>();
		if (statements.isEmpty()) {
			return atEnd;
		}
		switch (statements.getLast()) {
			case YieldTree yielded -> atEnd.add(yielded);
			case BlockTree block -> atEnd.addAll(yieldsAtEnd(block.getStatements()));
			case IfTree branch -> {
				atEnd.addAll(yieldsAtEnd(List.of(branch.getThenStatement())));
				StatementTree orElse = branch.getElseStatement();
				if (orElse != null) {
					atEnd.addAll(yieldsAtEnd(List.of(orElse)));
				}
			}
			default -> {
			}
		}
		return atEnd;
	}
}
