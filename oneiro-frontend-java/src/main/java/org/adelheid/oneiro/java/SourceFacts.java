package org.adelheid.oneiro.java;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;

/**
 * What lowering needs to know about the Java source beyond its types: which integer expressions are never
 * negative, so that {@code /} can be Lua's {@code //}; whether code assigns a variable or resizes a collection;
 * and whether a loop bound keeps its value while the loop runs.
 */
final class SourceFacts {

	/** The compound assignments that keep a non-negative value non-negative when given one. */
	private static final Set<Tree.Kind> RAISING = Set.of(Tree.Kind.PLUS_ASSIGNMENT, Tree.Kind.MULTIPLY_ASSIGNMENT,
			Tree.Kind.DIVIDE_ASSIGNMENT, Tree.Kind.REMAINDER_ASSIGNMENT);

	private final BodyLowering body;
	private final UnitLowering unit;
	private final JavaTypes types;

	SourceFacts(BodyLowering body) {
		this.body = body;
		this.unit = body.unit;
		this.types = body.types();
	}

	/**
	 * Whether an integer expression is known never to be negative: a non-negative literal, a size or length, an
	 * absolute value, a loop variable counting up from a non-negative start, or a final local or constant with such
	 * a value. Sums, products and quotients of those stay non-negative.
	 */
	boolean knownNonNegative(ExpressionTree tree) {
		if (types.isChar(unit.type(tree))) {
			// A char's number, which is never negative
			return true;
		}
		return switch (tree) {
			case LiteralTree literal -> literal.getValue() instanceof Number number && number.longValue() >= 0;
			case IdentifierTree identifier ->
					body.nonNegative.contains(unit.element(identifier))
							|| unit.nonNegativeMembers().isNonNegativeField(unit.element(identifier));
			case MemberSelectTree select -> (select.getIdentifier().contentEquals("length")
					&& unit.type(select.getExpression()).getKind() == TypeKind.ARRAY)
					|| unit.nonNegativeMembers().isNonNegativeField(unit.element(select));
			case MethodInvocationTree call ->
					isSizeOrAbs(call) || unit.nonNegativeMembers().returnsNonNegative(unit.element(call));
			case ParenthesizedTree parenthesized -> knownNonNegative(parenthesized.getExpression());
			case BinaryTree binary -> switch (binary.getKind()) {
				case PLUS, MULTIPLY, DIVIDE, REMAINDER ->
						knownNonNegative(binary.getLeftOperand()) && knownNonNegative(binary.getRightOperand());
				default -> false;
			};
			default -> false;
		};
	}

	private boolean isSizeOrAbs(MethodInvocationTree call) {
		ExecutableElement method = (ExecutableElement) unit.element(call);
		String name = method.getSimpleName().toString();
		TypeElement owner = JavaTypes.owner(method);
		String ownerName = owner.getQualifiedName().toString();
		return (name.equals("size") && call.getArguments().isEmpty()
				&& (types.isSubtype(owner.asType(), "java.util.Collection")
						|| types.isSubtype(owner.asType(), "java.util.Map")))
				|| (name.equals("length") && ownerName.equals("java.lang.String"))
				|| (name.equals("abs") && ownerName.equals("java.lang.Math"));
	}

	boolean refersTo(ExpressionTree tree, Element element) {
		return tree instanceof IdentifierTree identifier && element.equals(unit.element(identifier));
	}

	/**
	 * Whether every write to the variable in the scope keeps a non-negative value non-negative: {@code ++}; {@code +=},
	 * {@code *=}, {@code /=} or {@code %=} with a non-negative value; or {@code =} of one.
	 */
	boolean onlyRaises(Tree scope, Element variable) {
		boolean lowered = new TreeSearch() {
			@Override
			public Boolean visitAssignment(AssignmentTree node, Void unused) {
				return (writes(node.getVariable(), variable) && !knownNonNegative(node.getExpression()))
						|| Boolean.TRUE.equals(super.visitAssignment(node, null));
			}

			@Override
			public Boolean visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
				return (writes(node.getVariable(), variable)
						&& !(RAISING.contains(node.getKind()) && knownNonNegative(node.getExpression())))
						|| Boolean.TRUE.equals(super.visitCompoundAssignment(node, null));
			}

			@Override
			public Boolean visitUnary(UnaryTree node, Void unused) {
				boolean decrement = node.getKind() == Tree.Kind.PREFIX_DECREMENT
						|| node.getKind() == Tree.Kind.POSTFIX_DECREMENT;
				return (writes(node.getExpression(), variable) && decrement)
						|| Boolean.TRUE.equals(super.visitUnary(node, null));
			}
		}.foundIn(scope);
		return !lowered;
	}

	/** Whether the tree names the variable, as {@code count} or {@code this.count}. */
	private boolean writes(ExpressionTree tree, Element variable) {
		return (tree instanceof IdentifierTree || tree instanceof MemberSelectTree)
				&& variable.equals(unit.element(tree));
	}

	/** Whether the code assigns the variable or changes it with ++, --, or a compound assignment. */
	boolean assigns(Tree code, Element variable) {
		return new TreeSearch() {
			@Override
			public Boolean visitAssignment(AssignmentTree node, Void unused) {
				return refersTo(node.getVariable(), variable) || Boolean.TRUE.equals(super.visitAssignment(node, null));
			}

			@Override
			public Boolean visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
				return refersTo(node.getVariable(), variable)
						|| Boolean.TRUE.equals(super.visitCompoundAssignment(node, null));
			}

			@Override
			public Boolean visitUnary(UnaryTree node, Void unused) {
				return (BodyLowering.isStep(node.getKind()) && refersTo(node.getExpression(), variable))
						|| Boolean.TRUE.equals(super.visitUnary(node, null));
			}
		}.foundIn(code);
	}

	/**
	 * Whether a loop bound keeps its value while the body runs, as a Lua numeric for assumes when it evaluates the
	 * bound once: literals, variables the body does not assign, and the size or length of a collection the body does
	 * not add to or remove from.
	 */
	boolean unchangedBy(ExpressionTree bound, StatementTree loopBody) {
		return switch (bound) {
			case LiteralTree _ -> true;
			case IdentifierTree identifier -> !assigns(loopBody, unit.element(identifier));
			case ParenthesizedTree parenthesized -> unchangedBy(parenthesized.getExpression(), loopBody);
			case BinaryTree binary ->
					unchangedBy(binary.getLeftOperand(), loopBody) && unchangedBy(binary.getRightOperand(), loopBody);
			case MemberSelectTree select -> select.getExpression() instanceof IdentifierTree array
					&& select.getIdentifier().contentEquals("length") && !assigns(loopBody, unit.element(array));
			case MethodInvocationTree call -> call.getArguments().isEmpty()
					&& call.getMethodSelect() instanceof MemberSelectTree select
					&& (select.getIdentifier().contentEquals("size") || select.getIdentifier().contentEquals("length"))
					&& select.getExpression() instanceof IdentifierTree receiver
					&& !assigns(loopBody, unit.element(receiver))
					&& !resizes(loopBody, unit.element(receiver));
			default -> false;
		};
	}

	/** Whether the code calls a method that changes the size of the collection. */
	private boolean resizes(Tree code, Element collection) {
		Set<String> resizing = Set.of("add", "addAll", "remove", "removeAll", "removeIf", "retainAll", "clear", "put",
				"putAll", "append");
		return new TreeSearch() {
			@Override
			public Boolean visitMethodInvocation(MethodInvocationTree node, Void unused) {
				boolean here = node.getMethodSelect() instanceof MemberSelectTree select
						&& resizing.contains(select.getIdentifier().toString())
						&& refersTo(select.getExpression(), collection);
				return here || Boolean.TRUE.equals(super.visitMethodInvocation(node, null));
			}
		}.foundIn(code);
	}

	/** Whether the tree calls a method or creates an object anywhere. */
	static boolean containsCall(Tree tree) {
		return new TreeSearch() {
			@Override
			public Boolean visitMethodInvocation(MethodInvocationTree node, Void unused) {
				return true;
			}

			@Override
			public Boolean visitNewClass(NewClassTree node, Void unused) {
				return true;
			}
		}.foundIn(tree);
	}
}
