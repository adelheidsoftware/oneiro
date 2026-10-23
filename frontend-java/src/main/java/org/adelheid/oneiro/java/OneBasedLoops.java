package org.adelheid.oneiro.java;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;

/**
 * Decides which loops count from 1 in Lua. A loop {@code for (int i = 0; i < n; i++)} becomes {@code for i = 1, n do}
 * when its body uses {@code i} only as an index ({@code list.get(i)}, {@code list.set(i, x)}, {@code array[i]},
 * {@code text.charAt(i)}), in {@code i + 1}, or compared with another loop variable that counts from 1. Lua's
 * {@code i} is then Java's {@code i + 1}: every index loses its {@code + 1}, {@code i + 1} becomes {@code i}, and
 * comparing two such variables keeps its meaning. Whether a comparison qualifies depends on the other loop, so the
 * whole file is decided at once, dropping loops until every one left qualifies.
 */
final class OneBasedLoops {

	private OneBasedLoops() {
	}

	/**
	 * The variables of the loops that count from 1.
	 *
	 * @param countsFromZero whether a loop is a numeric loop from 0 by 1 below a bound, as the lowering decides it
	 */
	static Set<Element> find(UnitLowering unit, Predicate<ForLoopTree> countsFromZero) {
		Map<Element, ForLoopTree> candidates = new HashMap<>();
		new TreeScanner<Void, Void>() {
			@Override
			public Void visitForLoop(ForLoopTree node, Void unused) {
				if (countsFromZero.test(node) && node.getInitializer().getFirst() instanceof VariableTree variable) {
					candidates.put(unit.element(variable), node);
				}
				return super.visitForLoop(node, null);
			}
		}.scan(unit.unit, null);
		while (true) {
			List<Element> unfit = new ArrayList<>();
			candidates.forEach((variable, loop) -> {
				if (!qualifies(unit, variable, loop, candidates.keySet())) {
					unfit.add(variable);
				}
			});
			if (unfit.isEmpty()) {
				return Set.copyOf(candidates.keySet());
			}
			unfit.forEach(candidates::remove);
		}
	}

	private static boolean qualifies(UnitLowering unit, Element variable, ForLoopTree loop, Set<Element> candidates) {
		boolean[] fits = {true};
		new TreePathScanner<Void, Void>() {
			@Override
			public Void visitIdentifier(IdentifierTree node, Void unused) {
				if (variable.equals(unit.trees.getElement(getCurrentPath()))
						&& !fits(unit, getCurrentPath(), node, candidates)) {
					fits[0] = false;
				}
				return super.visitIdentifier(node, null);
			}
		}.scan(unit.path(loop.getStatement()), null);
		return fits[0];
	}

	/** Whether one use of the loop variable keeps its meaning when the variable counts from 1. */
	private static boolean fits(UnitLowering unit, TreePath path, IdentifierTree use, Set<Element> candidates) {
		Tree parent = path.getParentPath().getLeaf();
		return switch (parent) {
			case ArrayAccessTree access -> access.getIndex().equals(use);
			case MethodInvocationTree call -> !call.getArguments().isEmpty() && call.getArguments().getFirst().equals(use)
					&& call.getMethodSelect() instanceof MemberSelectTree select
					&& isIndexed(unit, select, call.getArguments().size());
			case BinaryTree binary -> {
				ExpressionTree other = binary.getLeftOperand().equals(use) ? binary.getRightOperand() : binary.getLeftOperand();
				yield switch (binary.getKind()) {
					case PLUS -> unit.types.isIntegral(unit.type(binary))
							&& other instanceof LiteralTree one && Integer.valueOf(1).equals(one.getValue());
					case EQUAL_TO, NOT_EQUAL_TO, LESS_THAN, LESS_THAN_EQUAL, GREATER_THAN, GREATER_THAN_EQUAL ->
							other instanceof IdentifierTree name && candidates.contains(unit.element(name));
					default -> false;
				};
			}
			default -> false;
		};
	}

	/** Whether a method takes an index first: a list's {@code get} and {@code set}, a text's {@code charAt}. */
	private static boolean isIndexed(UnitLowering unit, MemberSelectTree select, int argumentCount) {
		TypeMirror receiver = unit.type(select.getExpression());
		if (unit.types.isList(receiver)) {
			return (select.getIdentifier().contentEquals("get") && argumentCount == 1)
					|| (select.getIdentifier().contentEquals("set") && argumentCount == 2);
		}
		return select.getIdentifier().contentEquals("charAt") && argumentCount == 1
				&& (unit.types.isString(receiver) || unit.types.isStringBuilder(receiver));
	}
}
