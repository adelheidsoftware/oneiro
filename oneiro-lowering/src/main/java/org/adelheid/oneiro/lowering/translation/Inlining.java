package org.adelheid.oneiro.lowering.translation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.FunctionBody;
import org.adelheid.oneiro.lua.Statement;
import org.jspecify.annotations.Nullable;

/**
 * Opens a small function a method reference becomes where it is called, putting the arguments in for its
 * parameters: {@code function(text) return tonumber(text, 10) end} called on {@code item} is
 * {@code tonumber(item, 10)}. It opens only a function of one statement whose arguments are names, which read the
 * same however often and in whatever order the opened code reads them.
 */
public final class Inlining {

	private Inlining() {
	}

	/** The function's value for the arguments, opened; null when it cannot be opened. */
	public static @Nullable Expression value(Expression function, List<Expression> arguments) {
		Statement only = only(function, arguments);
		Map<String, Expression> values = values(function, arguments);
		if (only == null || values == null) {
			return null;
		}
		return switch (only) {
			case Statement.Return returned when returned.values().size() == 1 ->
					substituted(returned.values().getFirst(), values);
			case Statement.CallStatement statement -> substituted(statement.call(), values);
			default -> null;
		};
	}

	/** The function's one statement for the arguments, opened, for a call whose value nothing reads; or null. */
	public static @Nullable Statement statement(Expression function, List<Expression> arguments) {
		Statement only = only(function, arguments);
		Map<String, Expression> values = values(function, arguments);
		if (only == null || values == null) {
			return null;
		}
		return switch (only) {
			case Statement.Return returned when returned.values().size() == 1 -> {
				Expression call = substituted(returned.values().getFirst(), values);
				yield call instanceof Expression.Call || call instanceof Expression.MethodCall
						? new Statement.CallStatement(call)
						: null;
			}
			case Statement.CallStatement statement -> {
				Expression call = substituted(statement.call(), values);
				yield call == null ? null : new Statement.CallStatement(call);
			}
			case Statement.Assignment assignment -> {
				List<Expression> targets = substitutedAll(assignment.targets(), values);
				List<Expression> assigned = substitutedAll(assignment.values(), values);
				yield targets == null || assigned == null ? null : new Statement.Assignment(targets, assigned);
			}
			default -> null;
		};
	}

	private static @Nullable Statement only(Expression function, List<Expression> arguments) {
		if (!(function instanceof Expression.FunctionExpression wrapper)) {
			return null;
		}
		FunctionBody body = wrapper.function();
		Block block = body.body();
		if (body.varargs() || body.parameters().size() != arguments.size() || block.entries().size() != 1) {
			return null;
		}
		return block.entries().getFirst().statement();
	}

	private static @Nullable Map<String, Expression> values(Expression function, List<Expression> arguments) {
		if (!(function instanceof Expression.FunctionExpression wrapper)
				|| !arguments.stream().allMatch(Expression.Name.class::isInstance)) {
			return null;
		}
		Map<String, Expression> values = new HashMap<>();
		List<String> parameters = wrapper.function().parameters();
		for (int index = 0; index < parameters.size(); index++) {
			values.put(parameters.get(index), arguments.get(index));
		}
		return values;
	}

	/** The expression with each parameter's name replaced by its argument; null for code it does not open. */
	private static @Nullable Expression substituted(Expression expression, Map<String, Expression> values) {
		return switch (expression) {
			case Expression.Name name -> values.getOrDefault(name.name(), name);
			case Expression.Nil _, Expression.BooleanLiteral _, Expression.NumberLiteral _,
					Expression.StringLiteral _ -> expression;
			case Expression.Index index -> {
				Expression target = substituted(index.target(), values);
				Expression key = substituted(index.key(), values);
				yield target == null || key == null ? null : new Expression.Index(target, key);
			}
			case Expression.Field field -> {
				Expression target = substituted(field.target(), values);
				yield target == null ? null : new Expression.Field(target, field.name());
			}
			case Expression.Call call -> {
				Expression function = substituted(call.function(), values);
				List<Expression> arguments = substitutedAll(call.arguments(), values);
				yield function == null || arguments == null ? null : new Expression.Call(function, arguments);
			}
			case Expression.MethodCall call -> {
				Expression receiver = substituted(call.receiver(), values);
				List<Expression> arguments = substitutedAll(call.arguments(), values);
				yield receiver == null || arguments == null
						? null
						: new Expression.MethodCall(receiver, call.method(), arguments);
			}
			case Expression.Binary binary -> {
				Expression left = substituted(binary.left(), values);
				Expression right = substituted(binary.right(), values);
				yield left == null || right == null ? null : new Expression.Binary(binary.operator(), left, right);
			}
			case Expression.Unary unary -> {
				Expression operand = substituted(unary.operand(), values);
				yield operand == null ? null : new Expression.Unary(unary.operator(), operand);
			}
			case Expression.Parenthesized parenthesized -> {
				Expression inner = substituted(parenthesized.inner(), values);
				yield inner == null ? null : new Expression.Parenthesized(inner);
			}
			case Expression.Table table -> {
				List<Expression.TableField> fields = new ArrayList<>();
				for (Expression.TableField field : table.fields()) {
					Expression.TableField opened = substitutedField(field, values);
					if (opened == null) {
						yield null;
					}
					fields.add(opened);
				}
				yield new Expression.Table(fields);
			}
			// A function inside, whose own parameters could hide the names, and a vararg stay closed
			case Expression.FunctionExpression _, Expression.Vararg _ -> null;
		};
	}

	private static Expression.@Nullable TableField substitutedField(Expression.TableField field,
			Map<String, Expression> values) {
		return switch (field) {
			case Expression.TableField.Positional positional -> {
				Expression value = substituted(positional.value(), values);
				yield value == null ? null : new Expression.TableField.Positional(value);
			}
			case Expression.TableField.Named named -> {
				Expression value = substituted(named.value(), values);
				yield value == null ? null : new Expression.TableField.Named(named.name(), value);
			}
			case Expression.TableField.Keyed keyed -> {
				Expression key = substituted(keyed.key(), values);
				Expression value = substituted(keyed.value(), values);
				yield key == null || value == null ? null : new Expression.TableField.Keyed(key, value);
			}
			case Expression.TableField.Commented commented -> {
				Expression.TableField inner = substitutedField(commented.field(), values);
				yield inner == null ? null : new Expression.TableField.Commented(commented.comments(), inner);
			}
		};
	}

	private static @Nullable List<Expression> substitutedAll(List<Expression> expressions,
			Map<String, Expression> values) {
		List<Expression> opened = new ArrayList<>();
		for (Expression expression : expressions) {
			Expression one = substituted(expression, values);
			if (one == null) {
				return null;
			}
			opened.add(one);
		}
		return opened;
	}
}
