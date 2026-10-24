package org.adelheid.oneiro.java;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.integer;
import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.adelheid.oneiro.lowering.IndexArithmetic;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Java's operators in Lua: arithmetic, comparison, logic and bits, the conditional operator, casts, and the
 * indexing and creation of arrays. Integer division and remainder keep Java's rounding toward zero, and an
 * index, which Java counts from 0, counts from 1.
 */
final class Operators {

	private final BodyLowering body;
	private final UnitLowering unit;
	private final SourceFacts facts;
	private final StringBuilding strings;
	private final JavaTypes types;

	Operators(BodyLowering body) {
		this.body = body;
		this.unit = body.unit;
		this.facts = body.facts();
		this.strings = body.strings();
		this.types = body.types();
	}

	Expression binaryOperation(BinaryTree operation) {
		if (operation.getKind() == Tree.Kind.PLUS && types.isString(unit.type(operation))) {
			return strings.concatenation(operation);
		}
		if (operation.getKind() == Tree.Kind.PLUS) {
			// i + 1, where Lua's loop variable already is Java's i + 1
			ExpressionTree counted = countedFromOne(operation.getLeftOperand(), operation.getRightOperand());
			if (counted == null) {
				counted = countedFromOne(operation.getRightOperand(), operation.getLeftOperand());
			}
			if (counted != null) {
				return body.expression(counted);
			}
		}
		Expression left = body.expression(operation.getLeftOperand());
		return operation(operation.getKind(), operation.getLeftOperand(), left, operation.getRightOperand(),
				unit.type(operation.getLeftOperand()), operation);
	}

	/**
	 * A Java operator applied to an already lowered left operand: arithmetic, comparison, logic or bits. Integer
	 * {@code /} and {@code %} keep Java's rounding toward zero, which Lua's {@code //} and {@code %} share only for
	 * non-negative operands.
	 */
	Expression operation(Tree.Kind kind, ExpressionTree leftTree, Expression left, ExpressionTree rightTree,
			TypeMirror leftType, Tree where) {
		Expression right = body.expression(rightTree);
		TypeMirror rightType = unit.type(rightTree);
		// A char in arithmetic is its number, a whole one
		boolean integral = (types.isIntegral(leftType) || types.isChar(leftType))
				&& (types.isIntegral(rightType) || types.isChar(rightType));
		boolean bothNonNegative = integral && facts.knownNonNegative(leftTree) && facts.knownNonNegative(rightTree);
		boolean logical = types.isBoolean(leftType);
		return switch (kind) {
			case PLUS -> binary(left, BinaryOperator.ADD, right);
			case MINUS -> binary(left, BinaryOperator.SUBTRACT, right);
			case MULTIPLY -> binary(left, BinaryOperator.MULTIPLY, right);
			case DIVIDE -> {
				if (!integral) {
					yield binary(left, BinaryOperator.DIVIDE, right);
				}
				if (bothNonNegative) {
					yield binary(left, BinaryOperator.FLOOR_DIVIDE, right);
				}
				unit.use(Helper.QUOTIENT);
				yield Helper.QUOTIENT.invoke(left, right);
			}
			case REMAINDER -> bothNonNegative
					? binary(left, BinaryOperator.MODULO, right)
					: call(Expression.field(name("math"), "fmod"), left, right);
			case LESS_THAN -> binary(left, BinaryOperator.LESS, right);
			case GREATER_THAN -> binary(left, BinaryOperator.GREATER, right);
			case LESS_THAN_EQUAL -> binary(left, BinaryOperator.LESS_EQUAL, right);
			case GREATER_THAN_EQUAL -> binary(left, BinaryOperator.GREATER_EQUAL, right);
			case EQUAL_TO -> binary(left, BinaryOperator.EQUAL, right);
			case NOT_EQUAL_TO -> binary(left, BinaryOperator.NOT_EQUAL, right);
			case CONDITIONAL_AND -> binary(left, BinaryOperator.AND, right);
			case CONDITIONAL_OR -> binary(left, BinaryOperator.OR, right);
			case AND, OR -> {
				if (!logical) {
					yield binary(left, kind == Tree.Kind.AND ? BinaryOperator.BIT_AND : BinaryOperator.BIT_OR, right);
				}
				if (SourceFacts.containsCall(rightTree)) {
					throw new Rejection(where, "Java evaluates both sides of & and |, but Lua's and/or skip the right"
							+ " side when the left decides; evaluate the right side into a local first.");
				}
				yield binary(left, kind == Tree.Kind.AND ? BinaryOperator.AND : BinaryOperator.OR, right);
			}
			case XOR -> logical
					? binary(left, BinaryOperator.NOT_EQUAL, right)
					: binary(left, BinaryOperator.BIT_XOR, right);
			case LEFT_SHIFT -> binary(left, BinaryOperator.SHIFT_LEFT, right);
			case RIGHT_SHIFT -> facts.knownNonNegative(leftTree)
					? binary(left, BinaryOperator.SHIFT_RIGHT, right)
					// Java's >> keeps the sign, which is floor division by a power of two; Lua's >> is a logical shift
					: binary(left, BinaryOperator.FLOOR_DIVIDE, powerOfTwo(right));
			case UNSIGNED_RIGHT_SHIFT -> types.isLong(leftType)
					? binary(left, BinaryOperator.SHIFT_RIGHT, right)
					: binary(binary(left, BinaryOperator.BIT_AND, new Expression.NumberLiteral("0xFFFFFFFF")),
							BinaryOperator.SHIFT_RIGHT, right);
			default -> throw Rejection.notYet(where, "The operator " + kind);
		};
	}

	/** The variable of {@code variable + 1} when it is a loop variable that counts from 1 in Lua; otherwise null. */
	private @Nullable ExpressionTree countedFromOne(ExpressionTree variable, ExpressionTree one) {
		return variable instanceof IdentifierTree identifier && unit.countsFromOne(unit.element(identifier))
				&& one instanceof LiteralTree literal && Integer.valueOf(1).equals(literal.getValue())
				? variable : null;
	}

	/** {@code 1 << n}, folded to a number when n is a literal. */
	private static Expression powerOfTwo(Expression exponent) {
		Long literal = IndexArithmetic.integerValue(exponent);
		if (literal != null && literal >= 0 && literal < 63) {
			return integer(1L << literal);
		}
		return binary(integer(1), BinaryOperator.SHIFT_LEFT, exponent);
	}

	Expression unaryOperation(UnaryTree operation) {
		ExpressionTree operand = operation.getExpression();
		return switch (operation.getKind()) {
			case UNARY_MINUS -> {
				// javac already folds the minus into the literals -2147483648 and -9223372036854775808
				if (operand instanceof LiteralTree literal && literal.getValue() instanceof Number number
						&& number.longValue() < 0) {
					yield body.expression(operand);
				}
				yield new Expression.Unary(UnaryOperator.NEGATE, body.expression(operand));
			}
			case UNARY_PLUS -> body.expression(operand);
			case LOGICAL_COMPLEMENT -> {
				Expression negated = body.expression(operand);
				// not (a == b) reads better as a ~= b, and always means the same
				Expression inverted = invertedEquality(negated);
				yield inverted != null ? inverted : new Expression.Unary(UnaryOperator.NOT, negated);
			}
			case BITWISE_COMPLEMENT -> new Expression.Unary(UnaryOperator.BIT_NOT, body.expression(operand));
			default -> throw new Rejection(operation, "Lua has no increment or decrement expressions; put i++ on its"
					+ " own line.");
		};
	}

	/** {@code a ~= b} for {@code a == b}, and {@code a == b} for {@code a ~= b}; null for any other expression. */
	static @Nullable Expression invertedEquality(Expression expression) {
		if (!(expression instanceof Expression.Binary comparison)) {
			return null;
		}
		return switch (comparison.operator()) {
			case EQUAL -> binary(comparison.left(), BinaryOperator.NOT_EQUAL, comparison.right());
			case NOT_EQUAL -> binary(comparison.left(), BinaryOperator.EQUAL, comparison.right());
			default -> null;
		};
	}

	/**
	 * {@code c ? a : b} as {@code c and a or b}, which Lua evaluates the same way only when {@code a} can be neither
	 * false nor nil. Other conditionals are lowered to an if statement where they are a statement's value; anywhere
	 * else they are rejected.
	 */
	Expression conditional(ConditionalExpressionTree conditional) {
		if (!safeAsAndOr(conditional)) {
			throw new Rejection(conditional, "In Lua this conditional would need its true branch never to be false or"
					+ " nil; assign the conditional to a local first.");
		}
		return binary(
				binary(body.expression(conditional.getCondition()), BinaryOperator.AND,
						body.expression(conditional.getTrueExpression())),
				BinaryOperator.OR,
				body.expression(conditional.getFalseExpression()));
	}

	boolean safeAsAndOr(ConditionalExpressionTree conditional) {
		ExpressionTree whenTrue = conditional.getTrueExpression();
		TypeMirror type = unit.type(whenTrue);
		if (type.getKind().isPrimitive()) {
			return type.getKind() != TypeKind.BOOLEAN;
		}
		return switch (whenTrue) {
			case LiteralTree literal -> literal.getValue() != null && !Boolean.FALSE.equals(literal.getValue());
			case NewClassTree _, NewArrayTree _, LambdaExpressionTree _ -> true;
			case BinaryTree binary -> binary.getKind() == Tree.Kind.PLUS && types.isString(type);
			default -> false;
		};
	}

	Expression cast(TypeCastTree cast) {
		TypeMirror target = unit.type(cast.getType());
		TypeMirror source = unit.type(cast.getExpression());
		// A cast between char and number is one of the conversions expression() adds
		if (types.isIntegral(target) && types.isFloating(source)) {
			unit.use(Helper.TO_INTEGER);
			return Helper.TO_INTEGER.invoke(body.expression(cast.getExpression()));
		}
		return body.expression(cast.getExpression());
	}

	/** A Java index as a Lua one: one more, folded where it can be, unless the loop already counts from 1. */
	Expression index(ExpressionTree javaIndex) {
		if (javaIndex instanceof IdentifierTree identifier && unit.countsFromOne(unit.element(identifier))) {
			return name(unit.codeLocalName(unit.element(identifier), identifier));
		}
		return IndexArithmetic.plusOne(body.expression(javaIndex));
	}

	/** The element of {@code list.get(i)}: {@code list[i + 1]}, or {@code list[i]} when the loop counts from 1. */
	Expression listElement(Expression list, ExpressionTree javaIndex) {
		return new Expression.Index(list, index(javaIndex));
	}

	Expression newArray(NewArrayTree array) {
		if (array.getInitializers() != null) {
			List<Expression.TableField> fields = new ArrayList<>();
			for (ExpressionTree element : array.getInitializers()) {
				fields.add(new Expression.TableField.Positional(body.expression(element)));
			}
			return new Expression.Table(fields);
		}
		TypeMirror component = ((ArrayType) unit.type(array)).getComponentType();
		for (int dimension = 1; dimension < array.getDimensions().size(); dimension++) {
			component = ((ArrayType) component).getComponentType();
		}
		Expression filler;
		if (types.isIntegral(component)) {
			filler = integer(0);
		} else if (types.isFloating(component)) {
			filler = new Expression.NumberLiteral("0.0");
		} else if (types.isBoolean(component)) {
			filler = new Expression.BooleanLiteral(false);
		} else if (types.isChar(component)) {
			filler = Expression.string("\0");
		} else {
			throw new Rejection(array, "A new reference array is full of null, and Lua arrays cannot hold nil; use a"
					+ " List and add elements.");
		}
		if (array.getDimensions().size() > 1) {
			// new int[3][4] is three rows of four zeros
			List<Expression> arguments = new ArrayList<>(List.of(filler));
			array.getDimensions().forEach(size -> arguments.add(body.expression(size)));
			unit.use(Helper.GRID);
			return Helper.GRID.invoke(arguments.toArray(Expression[]::new));
		}
		unit.use(Helper.FILLED);
		return Helper.FILLED.invoke(body.expression(array.getDimensions().getFirst()), filler);
	}
}
