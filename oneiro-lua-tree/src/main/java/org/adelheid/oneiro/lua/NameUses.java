package org.adelheid.oneiro.lua;

import java.util.List;

/**
 * Whether Lua code mentions a name anywhere, inside nested functions too. It answers conservatively: a local of the
 * same name that shadows the outer one still counts as a mention, which is enough for deciding whether code may move
 * past the outer one's definition.
 */
public final class NameUses {

	private NameUses() {
	}

	public static boolean mentions(Expression expression, String name) {
		return switch (expression) {
			case Expression.Nil _, Expression.BooleanLiteral _, Expression.NumberLiteral _, Expression.StringLiteral _,
					Expression.Vararg _ -> false;
			case Expression.Name named -> named.name().equals(name);
			case Expression.Index index -> mentions(index.target(), name) || mentions(index.key(), name);
			case Expression.Field field -> mentions(field.target(), name);
			case Expression.Call call -> mentions(call.function(), name) || any(call.arguments(), name);
			case Expression.MethodCall call -> mentions(call.receiver(), name) || any(call.arguments(), name);
			case Expression.FunctionExpression function -> mentions(function.function().body(), name);
			case Expression.Binary binary -> mentions(binary.left(), name) || mentions(binary.right(), name);
			case Expression.Unary unary -> mentions(unary.operand(), name);
			case Expression.Table table -> table.fields().stream().anyMatch(field -> mentions(field, name));
			case Expression.Parenthesized parenthesized -> mentions(parenthesized.inner(), name);
		};
	}

	private static boolean mentions(Expression.TableField field, String name) {
		return switch (field) {
			case Expression.TableField.Positional positional -> mentions(positional.value(), name);
			case Expression.TableField.Named named -> mentions(named.value(), name);
			case Expression.TableField.Keyed keyed -> mentions(keyed.key(), name) || mentions(keyed.value(), name);
			case Expression.TableField.Commented commented -> mentions(commented.field(), name);
		};
	}

	public static boolean mentions(Block block, String name) {
		return block.entries().stream().anyMatch(entry -> mentions(entry.statement(), name));
	}

	public static boolean mentions(Statement statement, String name) {
		return switch (statement) {
			case Statement.Local local -> local.names().contains(name) || any(local.values(), name);
			case Statement.Assignment assignment -> any(assignment.targets(), name) || any(assignment.values(), name);
			case Statement.CallStatement call -> mentions(call.call(), name);
			case Statement.If branch -> branch.branches().stream()
					.anyMatch(each -> mentions(each.condition(), name) || mentions(each.body(), name))
					|| (branch.orElse() != null && mentions(branch.orElse(), name));
			case Statement.While loop -> mentions(loop.condition(), name) || mentions(loop.body(), name);
			case Statement.Do block -> mentions(block.body(), name);
			case Statement.Repeat loop -> mentions(loop.body(), name) || mentions(loop.condition(), name);
			case Statement.NumericFor loop -> loop.variable().equals(name) || mentions(loop.start(), name)
					|| mentions(loop.limit(), name) || (loop.step() != null && mentions(loop.step(), name))
					|| mentions(loop.body(), name);
			case Statement.GenericFor loop -> loop.names().contains(name) || any(loop.expressions(), name)
					|| mentions(loop.body(), name);
			case Statement.FunctionDeclaration declaration -> declaration.path().getFirst().equals(name)
					|| mentions(declaration.function().body(), name);
			case Statement.LocalFunction function -> function.name().equals(name)
					|| mentions(function.function().body(), name);
			case Statement.Return returned -> any(returned.values(), name);
			case Statement.Break _, Statement.Goto _, Statement.Label _, Statement.Verbatim _ -> false;
		};
	}

	private static boolean any(List<Expression> expressions, String name) {
		return expressions.stream().anyMatch(expression -> mentions(expression, name));
	}
}
