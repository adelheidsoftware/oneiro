package org.adelheid.oneiro.lua;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The Lua statements Oneiro emits. Comments, blank lines and source positions are not part of a statement; they live
 * on the {@link Block.Entry} that holds it.
 */
public sealed interface Statement {

	/** {@code local a, b = x, y}, or without values {@code local a}. */
	record Local(List<String> names, List<Expression> values) implements Statement {

		public Local {
			names = List.copyOf(names);
			values = List.copyOf(values);
			if (names.isEmpty()) {
				throw new IllegalArgumentException("A local declares at least one name.");
			}
			names.forEach(LuaNames::requireName);
		}
	}

	/** {@code a, b.c = x, y}. */
	record Assignment(List<Expression> targets, List<Expression> values) implements Statement {

		public Assignment {
			targets = List.copyOf(targets);
			values = List.copyOf(values);
			if (targets.isEmpty() || values.isEmpty()) {
				throw new IllegalArgumentException("An assignment needs targets and values.");
			}
			for (Expression target : targets) {
				if (!(target instanceof Expression.Name || target instanceof Expression.Field
						|| target instanceof Expression.Index)) {
					throw new IllegalArgumentException("Not an assignable expression: " + target);
				}
			}
		}
	}

	/** A function or method call used as a statement. */
	record CallStatement(Expression call) implements Statement {

		public CallStatement {
			if (!(call instanceof Expression.Call || call instanceof Expression.MethodCall)) {
				throw new IllegalArgumentException("Only a call can stand as a statement: " + call);
			}
		}
	}

	/** {@code if ... then ... elseif ... then ... else ... end}. */
	record If(List<Branch> branches, @Nullable Block orElse) implements Statement {

		public If {
			branches = List.copyOf(branches);
			if (branches.isEmpty()) {
				throw new IllegalArgumentException("An if statement has at least one branch.");
			}
		}

		/** The {@code if} or an {@code elseif}: a condition and the block it guards. */
		public record Branch(Expression condition, Block body) {
		}
	}

	record While(Expression condition, Block body) implements Statement {
	}

	/** {@code do ... end}: a block of its own, whose locals end with it. */
	record Do(Block body) implements Statement {
	}

	/** {@code repeat ... until condition}; the condition can see the body's locals. */
	record Repeat(Block body, Expression condition) implements Statement {
	}

	/**
	 * {@code for variable = start, limit, step do ... end}.
	 *
	 * @param step the step, or null when it is 1 and left out
	 */
	record NumericFor(String variable, Expression start, Expression limit, @Nullable Expression step, Block body)
			implements Statement {

		public NumericFor {
			LuaNames.requireName(variable);
		}
	}

	/** {@code for name, other in expressions do ... end}, such as {@code for _, x in ipairs(list) do}. */
	record GenericFor(List<String> names, List<Expression> expressions, Block body) implements Statement {

		public GenericFor {
			names = List.copyOf(names);
			expressions = List.copyOf(expressions);
			if (names.isEmpty() || expressions.isEmpty()) {
				throw new IllegalArgumentException("A generic for needs names and expressions.");
			}
			names.forEach(LuaNames::requireName);
		}
	}

	/**
	 * {@code function Row.complete(...)}, or with a method name {@code function Prototyping:confirm(...)}, whose body
	 * sees {@code self}.
	 *
	 * @param path the table path and function name, such as {@code Row} and {@code complete}
	 * @param method the method name after the colon, or null for a plain function
	 */
	record FunctionDeclaration(List<String> path, @Nullable String method, FunctionBody function)
			implements Statement {

		public FunctionDeclaration {
			path = List.copyOf(path);
			if (path.isEmpty()) {
				throw new IllegalArgumentException("A function declaration needs a name.");
			}
			path.forEach(LuaNames::requireName);
			if (method != null) {
				LuaNames.requireName(method);
			}
		}
	}

	/** {@code local function name(...)}, which can call itself. */
	record LocalFunction(String name, FunctionBody function) implements Statement {

		public LocalFunction {
			LuaNames.requireName(name);
		}
	}

	record Return(List<Expression> values) implements Statement {

		public Return {
			values = List.copyOf(values);
		}
	}

	record Break() implements Statement {
	}

	/** {@code goto label}; Oneiro emits it only for the {@code continue} idiom. */
	record Goto(String label) implements Statement {

		public Goto {
			LuaNames.requireName(label);
		}
	}

	/** {@code ::name::}. */
	record Label(String name) implements Statement {

		public Label {
			LuaNames.requireName(name);
		}
	}

	/**
	 * Lines of Lua written out as they are, each at the indentation of the block that holds them: the body of a helper
	 * function, which reads more plainly as Lua than as a tree. The lines may use only Lua's own globals and the
	 * function's parameters and locals.
	 */
	record Verbatim(List<String> lines) implements Statement {

		public Verbatim {
			lines = List.copyOf(lines);
		}
	}

	/** {@code target = value}. */
	static Assignment assignment(Expression target, Expression value) {
		return new Assignment(List.of(target), List.of(value));
	}

	/** {@code if condition then ... end}, without {@code elseif} or {@code else}. */
	static If ifThen(Expression condition, Block then) {
		return new If(List.of(new If.Branch(condition, then)), null);
	}
}
