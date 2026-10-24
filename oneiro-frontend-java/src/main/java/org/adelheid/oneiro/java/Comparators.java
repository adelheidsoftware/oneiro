package org.adelheid.oneiro.java;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.integer;
import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Java comparators as the Lua test of whether one value comes before another, which {@code table.sort} takes and a
 * loop that finds a minimum compares with: {@code Comparator.comparingInt(npc -> npc.level)} is
 * {@code a.level < b.level}, {@code (a, b) -> Integer.compare(a.size(), b.size())} is {@code #a < #b}.
 */
final class Comparators {

	private final BodyLowering body;
	private final UnitLowering unit;

	Comparators(BodyLowering body) {
		this.body = body;
		this.unit = body.unit;
	}

	/**
	 * The comparator as Lua's "comes before" function, {@code function(a, b) return a.level < b.level end}. A
	 * comparator lambda's own parameters name the function's.
	 */
	Expression function(ExpressionTree comparator) {
		List<? extends VariableTree> own = ownParameters(comparator);
		List<String> compared = own != null
				? List.of(unit.codeLocalName(unit.element(own.get(0)), own.get(0)),
						unit.codeLocalName(unit.element(own.get(1)), own.get(1)))
				: List.of(unit.freshLocal("a", comparator), unit.freshLocal("b", comparator));
		return MethodReferences.function(compared, comesBefore(comparator, compared.get(0), compared.get(1)), false);
	}

	/** The two parameters a comparator lambda or anonymous comparator names; null for any other comparator. */
	private static @Nullable List<? extends VariableTree> ownParameters(ExpressionTree comparator) {
		ExpressionTree inner = BodyLowering.withoutParentheses(comparator);
		if (inner instanceof LambdaExpressionTree lambda && lambda.getParameters().size() == 2) {
			return lambda.getParameters();
		}
		if (inner instanceof NewClassTree creation && creation.getClassBody() != null) {
			for (Tree member : creation.getClassBody().getMembers()) {
				if (member instanceof MethodTree method && method.getName().contentEquals("compare")
						&& method.getParameters().size() == 2) {
					return method.getParameters();
				}
			}
		}
		return null;
	}

	/**
	 * Whether the value named {@code a} comes before the one named {@code b}. A null comparator, or a literal
	 * {@code null}, is natural order: {@code a < b}.
	 */
	Expression comesBefore(@Nullable ExpressionTree comparator, String a, String b) {
		if (comparator == null || (comparator instanceof LiteralTree literal && literal.getValue() == null)) {
			return binary(name(a), BinaryOperator.LESS, name(b));
		}
		ExpressionTree inner = BodyLowering.withoutParentheses(comparator);
		if (inner instanceof MethodInvocationTree call && call.getMethodSelect() instanceof MemberSelectTree select) {
			String method = select.getIdentifier().toString();
			if (method.equals("reversed") && call.getArguments().isEmpty()) {
				return comesBefore(select.getExpression(), b, a);
			}
			if (method.startsWith("thenComparing") && call.getArguments().size() == 1) {
				return thenComparing(call, select.getExpression(), call.getArguments().getFirst(), a, b);
			}
			if (isComparatorFactory(call)) {
				return switch (method) {
					case "naturalOrder" -> binary(name(a), BinaryOperator.LESS, name(b));
					case "reverseOrder" -> binary(name(b), BinaryOperator.LESS, name(a));
					case "comparing", "comparingInt", "comparingLong", "comparingDouble" -> {
						if (call.getArguments().size() != 1) {
							throw Rejection.notYet(call, "Comparator." + method + " with a second comparator");
						}
						ExpressionTree key = call.getArguments().getFirst();
						yield binary(key(key, a), BinaryOperator.LESS, key(key, b));
					}
					default -> throw Rejection.notYet(call, "Comparator." + method);
				};
			}
		}
		if (inner instanceof MemberReferenceTree reference && isNumberCompare(reference)) {
			return binary(name(a), BinaryOperator.LESS, name(b));
		}
		if (inner instanceof LambdaExpressionTree lambda && lambda.getParameters().size() == 2
				&& lambda.getBody() instanceof ExpressionTree comparison) {
			body.declare(lambda.getParameters().get(0));
			body.declare(lambda.getParameters().get(1));
			body.alias(lambda.getParameters().get(0), a);
			body.alias(lambda.getParameters().get(1), b);
			return comparisonBefore(comparison);
		}
		// Any other comparator is a function, called on the two, or opened where it is a small one
		return binary(body.lambdas().applied(body.expression(comparator), List.of(name(a), name(b))),
				BinaryOperator.LESS, integer(0));
	}

	/**
	 * A comparison's sign as a test: {@code Integer.compare(x, y)} and {@code x.compareTo(y)} are {@code x < y}, and
	 * any other is {@code comparison < 0}.
	 */
	private Expression comparisonBefore(ExpressionTree comparison) {
		if (comparison instanceof MethodInvocationTree compare
				&& compare.getMethodSelect() instanceof MemberSelectTree select
				&& unit.element(compare) instanceof ExecutableElement method) {
			String name = select.getIdentifier().toString();
			TypeElement owner = JavaTypes.owner(method);
			if (name.equals("compare") && method.getModifiers().contains(Modifier.STATIC)
					&& LibraryCalls.isNumberBox(owner)) {
				return binary(body.expression(compare.getArguments().get(0)), BinaryOperator.LESS,
						body.expression(compare.getArguments().get(1)));
			}
			if (name.equals("compareTo") && compare.getArguments().size() == 1
					&& owner.getQualifiedName().contentEquals("java.lang.Enum")) {
				// Enum constants compare by their order
				Expression left = Expression.field(body.expression(select.getExpression()), "ordinal");
				Expression right = Expression.field(body.expression(compare.getArguments().getFirst()), "ordinal");
				return binary(left, BinaryOperator.LESS, right);
			}
			if (name.equals("compareTo") && compare.getArguments().size() == 1
					&& (LibraryCalls.isNumberBox(owner) || owner.getQualifiedName().contentEquals("java.lang.String")
							|| owner.getQualifiedName().contentEquals("java.lang.Character"))) {
				return binary(body.expression(select.getExpression()), BinaryOperator.LESS,
						body.expression(compare.getArguments().getFirst()));
			}
		}
		return binary(body.expression(comparison), BinaryOperator.LESS, integer(0));
	}

	/**
	 * {@code first.thenComparing(next)}: the first comparator decides, and the next where it finds the two equal. A
	 * first comparator by key reads {@code a.level < b.level or (a.level == b.level and ...)}; any other tests both
	 * ways round.
	 */
	private Expression thenComparing(MethodInvocationTree call, ExpressionTree first, ExpressionTree next, String a,
			String b) {
		Expression second;
		if (unit.element(call) instanceof ExecutableElement method
				&& body.types().isSubtype(method.getParameters().getFirst().asType(), "java.util.Comparator")) {
			second = comesBefore(next, a, b);
		} else {
			second = binary(key(next, a), BinaryOperator.LESS, key(next, b));
		}
		ExpressionTree firstKey = keyOf(first);
		if (firstKey != null) {
			Expression keyA = key(firstKey, a);
			Expression keyB = key(firstKey, b);
			Expression before = binary(keyA, BinaryOperator.LESS, keyB);
			Expression tie = binary(binary(keyA, BinaryOperator.EQUAL, keyB), BinaryOperator.AND, second);
			return binary(before, BinaryOperator.OR, new Expression.Parenthesized(tie));
		}
		Expression notAfter = new Expression.Unary(UnaryOperator.NOT, comesBefore(first, b, a));
		return binary(comesBefore(first, a, b), BinaryOperator.OR,
				new Expression.Parenthesized(binary(notAfter, BinaryOperator.AND, second)));
	}

	/** The key function of {@code Comparator.comparing(key)} and its kin; null for any other comparator. */
	private @Nullable ExpressionTree keyOf(ExpressionTree comparator) {
		if (BodyLowering.withoutParentheses(comparator) instanceof MethodInvocationTree call
				&& call.getMethodSelect() instanceof MemberSelectTree select
				&& select.getIdentifier().toString().startsWith("comparing") && call.getArguments().size() == 1
				&& isComparatorFactory(call)) {
			return call.getArguments().getFirst();
		}
		return null;
	}

	/**
	 * The key a {@code Comparator.comparing} function gives for the value named: the lambda's expression, or a call.
	 */
	private Expression key(ExpressionTree function, String value) {
		if (BodyLowering.withoutParentheses(function) instanceof LambdaExpressionTree lambda) {
			if (lambda.getParameters().size() != 1 || !(lambda.getBody() instanceof ExpressionTree key)) {
				throw new Rejection(function, "A comparator's key is compared inside one expression; write it as one,"
						+ " x -> key.");
			}
			body.declare(lambda.getParameters().getFirst());
			body.alias(lambda.getParameters().getFirst(), value);
			return body.expression(key);
		}
		return body.lambdas().applied(body.expression(function), List.of(name(value)));
	}

	private boolean isComparatorFactory(MethodInvocationTree call) {
		if (!(unit.element(call) instanceof ExecutableElement method)
				|| !method.getModifiers().contains(Modifier.STATIC)) {
			return false;
		}
		String owner = JavaTypes.owner(method).getQualifiedName().toString();
		return owner.equals("java.util.Comparator") || owner.equals("java.util.Collections");
	}

	/** {@code Integer::compare} and its kin, which order numbers naturally. */
	private boolean isNumberCompare(MemberReferenceTree reference) {
		return reference.getName().contentEquals("compare")
				&& unit.element(reference) instanceof ExecutableElement method
				&& method.getModifiers().contains(Modifier.STATIC)
				&& LibraryCalls.isNumberBox(JavaTypes.owner(method));
	}
}
