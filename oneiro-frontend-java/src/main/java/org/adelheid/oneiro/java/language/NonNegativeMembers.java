package org.adelheid.oneiro.java.language;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import org.adelheid.oneiro.java.core.BodyLowering;
import org.adelheid.oneiro.java.core.JavaTypes;
import org.adelheid.oneiro.java.core.SourceFacts;
import org.adelheid.oneiro.java.core.UnitLowering;
import org.jspecify.annotations.Nullable;

/**
 * The fields and methods of a file whose integer values are never negative, so that integer {@code /} and {@code %}
 * on them can be Lua's {@code //} and {@code %}, which agree with Java's only for non-negative operands.
 */
public final class NonNegativeMembers {

	/** Fields known never to hold a negative integer: constants, and fields that start so and are only raised. */
	private final Set<Element> nonNegativeFields = new HashSet<>();

	/** Methods of this file known to return only non-negative integers, or known not to. */
	private final Map<Element, Boolean> nonNegativeMethods = new HashMap<>();

	private final UnitLowering unit;
	private final JavaTypes types;

	public NonNegativeMembers(UnitLowering unit) {
		this.unit = unit;
		this.types = unit.types;
	}

	public boolean isNonNegativeField(@Nullable Element element) {
		return element != null && nonNegativeFields.contains(element);
	}

	/**
	 * Finds the fields of the file whose value is never negative, so that integer {@code /} and {@code %} on them can
	 * be Lua's {@code //} and {@code %}: integer fields that are private or final, start non-negative (Java's default
	 * is 0), and that the file only ever raises, as {@code ++}, a constant or a counter does. A field may rest on
	 * another, a constant declared further down for one, so the search repeats until it finds nothing new.
	 */
	public void inferNonNegativeFields(ClassTree top) {
		List<VariableTree> candidates = new ArrayList<>();
		new TreeScanner<Void, Void>() {
			@Override
			public Void visitClass(ClassTree node, Void unused) {
				for (Tree member : node.getMembers()) {
					if (member instanceof VariableTree field && types.isIntegral(unit.type(field))) {
						Set<Modifier> flags = field.getModifiers().getFlags();
						if (flags.contains(Modifier.PRIVATE) || flags.contains(Modifier.FINAL)) {
							candidates.add(field);
						}
					}
				}
				return super.visitClass(node, null);
			}
		}.scan(top, null);
		SourceFacts facts = new BodyLowering(unit, false, false).facts();
		boolean found = true;
		while (found) {
			found = false;
			for (VariableTree field : candidates) {
				Element element = unit.element(field);
				ExpressionTree initializer = field.getInitializer();
				if (!nonNegativeFields.contains(element)
						&& (initializer == null || facts.knownNonNegative(initializer))
						&& facts.onlyRaises(unit.compilationUnit, element)) {
					nonNegativeFields.add(element);
					found = true;
				}
			}
		}
	}

	/**
	 * Whether every call of the method returns a non-negative integer: a method of this file that nothing in the
	 * plugin overrides, each of whose {@code return}s gives a value known never to be negative.
	 */
	public boolean returnsNonNegative(@Nullable Element element) {
		if (!(element instanceof ExecutableElement method) || !types.isIntegral(method.getReturnType())
				|| !unit.fileTypes.contains(JavaTypes.owner(method)) || types.isOverridden(method)) {
			return false;
		}
		Boolean known = nonNegativeMethods.get(method);
		if (known != null) {
			return known;
		}
		// A method that calls itself is not known while it is being worked out
		nonNegativeMethods.put(method, false);
		MethodTree tree = unit.trees.getTree(method);
		boolean result = tree != null && tree.getBody() != null && returnsOnlyNonNegative(tree.getBody());
		nonNegativeMethods.put(method, result);
		return result;
	}

	/** Whether every {@code return} of the body, outside the lambdas and classes in it, gives a non-negative value. */
	private boolean returnsOnlyNonNegative(BlockTree body) {
		SourceFacts facts = new BodyLowering(unit, false, false).facts();
		List<ExpressionTree> returned = new ArrayList<>();
		new TreeScanner<Void, Void>() {
			@Override
			public Void visitReturn(ReturnTree node, Void unused) {
				if (node.getExpression() != null) {
					returned.add(node.getExpression());
				}
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
		}.scan(body, null);
		return !returned.isEmpty() && returned.stream().allMatch(facts::knownNonNegative);
	}
}
