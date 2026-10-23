package org.adelheid.oneiro.java;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ConstantCaseLabelTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.YieldTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import org.jspecify.annotations.Nullable;

/**
 * Finds the places where Java turns a char into a number, or a number into a char, without a method call. A char is a
 * one-character string in Lua, so each of those places needs {@code string.byte} or {@code string.char}: an int set
 * from a char, a char passed for an int parameter, a char in arithmetic, compared with a number or used as an index,
 * a cast either way, and a char set from a number.
 */
final class CharConversions {

	/** Which way a value changes. */
	enum Conversion {
		/** A char where Java reads its number: {@code string.byte(c)}. */
		TO_NUMBER,
		/** A number where Java stores a char: {@code string.char(n)}. */
		TO_CHAR
	}

	/** What a place takes: a number or a char; null for anything else, where a char stays a char. */
	private enum Wants {
		NUMBER,
		CHAR
	}

	private final Map<Tree, Conversion> conversions = new HashMap<>();

	private CharConversions() {
	}

	/** The conversions of every expression in the file. */
	static CharConversions find(UnitLowering unit) {
		CharConversions found = new CharConversions();
		new TreePathScanner<Void, Void>() {
			@Override
			public Void scan(Tree tree, Void unused) {
				// While a child is scanned, the current path is still its parent's
				if (tree instanceof ExpressionTree expression && getCurrentPath() != null) {
					Wants wants = wants(unit, expression, getCurrentPath());
					if (wants != null) {
						TypeMirror type = typeOf(unit, new TreePath(getCurrentPath(), expression));
						Conversion conversion = conversion(unit.types, type, wants);
						if (conversion != null) {
							found.conversions.put(expression, conversion);
						}
					}
				}
				return super.scan(tree, null);
			}
		}.scan(unit.unit, null);
		return found;
	}

	/** How the expression changes where it stands, or null when it stays as it is. */
	@Nullable Conversion of(ExpressionTree expression) {
		return conversions.get(expression);
	}

	private static @Nullable Conversion conversion(JavaTypes types, TypeMirror type, Wants wants) {
		if (wants == Wants.NUMBER && types.isChar(type)) {
			return Conversion.TO_NUMBER;
		}
		if (wants == Wants.CHAR && (types.isIntegral(type) || types.isFloating(type))) {
			return Conversion.TO_CHAR;
		}
		return null;
	}

	/** What the place of an expression, a child of the parent, takes. */
	private static @Nullable Wants wants(UnitLowering unit, ExpressionTree expression, TreePath parentPath) {
		Tree parent = parentPath.getLeaf();
		return switch (parent) {
			case VariableTree variable when expression.equals(variable.getInitializer()) ->
					wants(unit, typeOf(unit, parentPath));
			case AssignmentTree assignment when expression.equals(assignment.getExpression()) ->
					wants(unit, typeOf(unit, new TreePath(parentPath, assignment.getVariable())));
			case ReturnTree _ -> wants(unit, returnType(unit, parentPath));
			case LambdaExpressionTree lambda when expression.equals(lambda.getBody()) ->
					wants(unit, lambdaReturnType(unit, parentPath));
			case MethodInvocationTree call -> argumentWants(unit, call.getArguments(), expression, parentPath);
			case NewClassTree creation -> argumentWants(unit, creation.getArguments(), expression, parentPath);
			case ConditionalExpressionTree conditional when !expression.equals(conditional.getCondition()) ->
					wants(unit, typeOf(unit, parentPath));
			case NewArrayTree array when array.getInitializers() != null && array.getInitializers().contains(expression)
					&& typeOf(unit, parentPath) instanceof ArrayType type -> wants(unit, type.getComponentType());
			case ArrayAccessTree access when expression.equals(access.getIndex()) -> Wants.NUMBER;
			case TypeCastTree cast -> wants(unit, typeOf(unit, new TreePath(parentPath, cast.getType())));
			case UnaryTree unary -> switch (unary.getKind()) {
				case UNARY_MINUS, UNARY_PLUS, BITWISE_COMPLEMENT -> Wants.NUMBER;
				default -> null;
			};
			case BinaryTree binary -> operandWants(unit, binary, expression, parentPath);
			case CompoundAssignmentTree compound when expression.equals(compound.getExpression()) ->
					compound.getKind() == Tree.Kind.PLUS_ASSIGNMENT
							&& unit.types.isString(typeOf(unit, new TreePath(parentPath, compound.getVariable())))
							? null
							: Wants.NUMBER;
			case ConstantCaseLabelTree _ -> wants(unit, selectorType(unit, parentPath));
			case CaseTree caseTree when expression.equals(caseTree.getBody()) -> wants(unit, switchType(unit, parentPath));
			case YieldTree _ -> wants(unit, switchType(unit, parentPath));
			default -> null;
		};
	}

	private static @Nullable Wants wants(UnitLowering unit, @Nullable TypeMirror type) {
		if (type == null) {
			return null;
		}
		if (unit.types.isChar(type)) {
			return Wants.CHAR;
		}
		return unit.types.isIntegral(type) || unit.types.isFloating(type) ? Wants.NUMBER : null;
	}

	/**
	 * An operand of arithmetic is a number; so is a char compared with a number. Two chars compare as the strings
	 * they are, and a char added to a string is text.
	 */
	private static @Nullable Wants operandWants(UnitLowering unit, BinaryTree binary, ExpressionTree operand,
			TreePath parentPath) {
		ExpressionTree other = operand.equals(binary.getLeftOperand()) ? binary.getRightOperand() : binary.getLeftOperand();
		TypeMirror otherType = typeOf(unit, new TreePath(parentPath, other));
		return switch (binary.getKind()) {
			case PLUS -> unit.types.isString(typeOf(unit, parentPath)) ? null : Wants.NUMBER;
			case EQUAL_TO, NOT_EQUAL_TO, LESS_THAN, GREATER_THAN, LESS_THAN_EQUAL, GREATER_THAN_EQUAL ->
					unit.types.isIntegral(otherType) || unit.types.isFloating(otherType) ? Wants.NUMBER : null;
			case CONDITIONAL_AND, CONDITIONAL_OR -> null;
			default -> Wants.NUMBER;
		};
	}

	/** The parameter an argument is passed for; the element type of a variable-arity one, unless an array fills it. */
	private static @Nullable Wants argumentWants(UnitLowering unit, List<? extends ExpressionTree> arguments,
			ExpressionTree argument, TreePath callPath) {
		int index = arguments.indexOf(argument);
		if (index < 0 || !(unit.trees.getElement(callPath) instanceof ExecutableElement method)) {
			return null;
		}
		int last = method.getParameters().size() - 1;
		if (last < 0) {
			return null;
		}
		TypeMirror parameter = method.getParameters().get(Math.min(index, last)).asType();
		if (method.isVarArgs() && index >= last && parameter instanceof ArrayType array
				&& !(typeOf(unit, new TreePath(callPath, argument)) instanceof ArrayType)) {
			parameter = array.getComponentType();
		}
		return wants(unit, parameter);
	}

	/** The return type of the method or lambda a return statement leaves. */
	private static @Nullable TypeMirror returnType(UnitLowering unit, TreePath path) {
		for (TreePath current = path; current != null; current = current.getParentPath()) {
			if (current.getLeaf() instanceof MethodTree method) {
				return ((ExecutableElement) unit.element(method)).getReturnType();
			}
			if (current.getLeaf() instanceof LambdaExpressionTree) {
				return lambdaReturnType(unit, current);
			}
		}
		return null;
	}

	/**
	 * The return type of the method a lambda implements, as its interface declares it: {@code int} for a
	 * {@code ToIntFunction}, a type variable, which takes a char as it is, for a {@code Function}.
	 */
	private static @Nullable TypeMirror lambdaReturnType(UnitLowering unit, TreePath lambdaPath) {
		if (!(unit.trees.getTypeMirror(lambdaPath) instanceof DeclaredType target)
				|| !(target.asElement() instanceof TypeElement type)) {
			return null;
		}
		for (ExecutableElement method : ElementFilter.methodsIn(unit.elements.getAllMembers(type))) {
			if (method.getModifiers().contains(Modifier.ABSTRACT) && !isObjectMethod(method)) {
				return method.getReturnType();
			}
		}
		return null;
	}

	/** {@code equals}, {@code hashCode} or {@code toString}, which an interface may redeclare without implementing. */
	private static boolean isObjectMethod(ExecutableElement method) {
		String name = method.getSimpleName().toString();
		return (name.equals("equals") && method.getParameters().size() == 1)
				|| ((name.equals("hashCode") || name.equals("toString")) && method.getParameters().isEmpty());
	}

	/** The type of the selector of the switch a case label belongs to. */
	private static @Nullable TypeMirror selectorType(UnitLowering unit, TreePath labelPath) {
		for (TreePath current = labelPath; current != null; current = current.getParentPath()) {
			if (current.getLeaf() instanceof SwitchTree switched) {
				return typeOf(unit, new TreePath(current, switched.getExpression()));
			}
			if (current.getLeaf() instanceof SwitchExpressionTree switched) {
				return typeOf(unit, new TreePath(current, switched.getExpression()));
			}
		}
		return null;
	}

	/** The type of the switch expression a case's value or a {@code yield} gives its value to. */
	private static @Nullable TypeMirror switchType(UnitLowering unit, TreePath path) {
		for (TreePath current = path; current != null; current = current.getParentPath()) {
			if (current.getLeaf() instanceof SwitchExpressionTree) {
				return typeOf(unit, current);
			}
			if (current.getLeaf() instanceof SwitchTree || current.getLeaf() instanceof MethodTree
					|| current.getLeaf() instanceof LambdaExpressionTree) {
				return null;
			}
		}
		return null;
	}

	private static TypeMirror typeOf(UnitLowering unit, TreePath path) {
		TypeMirror type = unit.trees.getTypeMirror(path);
		if (type == null) {
			throw new IllegalStateException("javac gave no type for " + path.getLeaf());
		}
		return type;
	}
}
