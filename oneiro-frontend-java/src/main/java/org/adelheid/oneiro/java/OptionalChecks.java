package org.adelheid.oneiro.java;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreePath;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import org.adelheid.oneiro.lowering.OptionalDependencies;
import org.jspecify.annotations.Nullable;

/**
 * The rule on an optional dependency ({@link OptionalDependencies}): code that reaches its global, a static member of
 * its class ({@code @LuaOptional}) or of a class nested in it, compiles only where a check that it is loaded reaches
 * it, in the same method: inside {@code if (prettyui.isLoaded())} or a loop on it, in the {@code else} of the negated
 * check, on the right of {@code prettyui.isLoaded() &&}, or after {@code if (!prettyui.isLoaded()) return;} or another
 * exit. The search stops at a lambda, a method or a class, whose code runs later.
 */
final class OptionalChecks {

	private final UnitLowering unit;

	OptionalChecks(UnitLowering unit) {
		this.unit = unit;
	}

	/** The alias of the optional dependency whose {@code isLoaded()} an expression calls; null for any other. */
	@Nullable String loaded(ExpressionTree expression) {
		return BodyLowering.withoutParentheses(expression) instanceof MethodInvocationTree call
				&& unit.element(call) instanceof ExecutableElement method ? unit.types.loadedCheck(method) : null;
	}

	/** Rejects a use of an optional dependency's member where no check that it is loaded reaches. */
	void check(Element member, Tree where) {
		String alias = unit.types.optionalAlias(JavaTypes.owner(member));
		if (alias != null && !reached(unit.path(where), alias)) {
			throw new Rejection(where, OptionalDependencies.unchecked(alias));
		}
	}

	private boolean reached(TreePath use, String alias) {
		Tree child = use.getLeaf();
		for (TreePath path = use.getParentPath(); path != null; path = path.getParentPath()) {
			Tree parent = path.getLeaf();
			if (parent instanceof LambdaExpressionTree || parent instanceof MethodTree || parent instanceof ClassTree) {
				// Code there runs later, when the library may be gone
				return false;
			}
			if (checksChild(parent, child, alias)) {
				return true;
			}
			child = parent;
		}
		return false;
	}

	/** Whether a tree checks that the dependency is loaded for one of its parts, {@code child}. */
	private boolean checksChild(Tree parent, Tree child, String alias) {
		return switch (parent) {
			case IfTree branch ->
					(child.equals(branch.getThenStatement()) && checks(branch.getCondition(), alias, true))
							|| (child.equals(branch.getElseStatement()) && checks(branch.getCondition(), alias, false));
			case ConditionalExpressionTree choice ->
					(child.equals(choice.getTrueExpression()) && checks(choice.getCondition(), alias, true))
							|| (child.equals(choice.getFalseExpression())
									&& checks(choice.getCondition(), alias, false));
			case WhileLoopTree loop -> child.equals(loop.getStatement()) && checks(loop.getCondition(), alias, true);
			case BinaryTree operation when child.equals(operation.getRightOperand()) ->
					(operation.getKind() == Tree.Kind.CONDITIONAL_AND
							&& checks(operation.getLeftOperand(), alias, true))
							|| (operation.getKind() == Tree.Kind.CONDITIONAL_OR
									&& checks(operation.getLeftOperand(), alias, false));
			case BlockTree block -> block.getStatements().stream()
					.takeWhile(statement -> !statement.equals(child))
					.anyMatch(statement -> exitsUnloaded(statement, alias));
			default -> false;
		};
	}

	/**
	 * Whether a condition tells that the dependency is loaded: when it holds, or for {@code holds} false, when it
	 * fails, as {@code !prettyui.isLoaded()} does.
	 */
	private boolean checks(ExpressionTree condition, String alias, boolean holds) {
		ExpressionTree inner = BodyLowering.withoutParentheses(condition);
		if (alias.equals(loaded(inner))) {
			return holds;
		}
		return switch (inner) {
			case UnaryTree not when not.getKind() == Tree.Kind.LOGICAL_COMPLEMENT ->
					checks(not.getExpression(), alias, !holds);
			case BinaryTree both when both.getKind() == Tree.Kind.CONDITIONAL_AND ->
					holds && (checks(both.getLeftOperand(), alias, true)
							|| checks(both.getRightOperand(), alias, true));
			case BinaryTree either when either.getKind() == Tree.Kind.CONDITIONAL_OR ->
					!holds && (checks(either.getLeftOperand(), alias, false)
							|| checks(either.getRightOperand(), alias, false));
			default -> false;
		};
	}

	/** Whether a statement leaves the code that follows it where the dependency is not loaded. */
	private boolean exitsUnloaded(StatementTree statement, String alias) {
		return statement instanceof IfTree branch && branch.getElseStatement() == null
				&& checks(branch.getCondition(), alias, false) && exits(branch.getThenStatement());
	}

	private static boolean exits(StatementTree statement) {
		return switch (statement) {
			case ReturnTree _, ThrowTree _, BreakTree _, ContinueTree _ -> true;
			case BlockTree block -> !block.getStatements().isEmpty() && exits(block.getStatements().getLast());
			default -> false;
		};
	}
}
