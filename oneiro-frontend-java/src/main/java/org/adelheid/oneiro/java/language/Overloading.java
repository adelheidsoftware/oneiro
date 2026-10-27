package org.adelheid.oneiro.java.language;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import org.adelheid.oneiro.java.core.BodyLowering;
import org.adelheid.oneiro.java.core.JavaTypes;
import org.adelheid.oneiro.java.core.Rejection;
import org.adelheid.oneiro.java.core.UnitLowering;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.Statement;
import org.adelheid.oneiro.lua.Trivia;
import org.jspecify.annotations.Nullable;

/**
 * Methods that share a name, which a Lua table cannot hold apart, since Lua functions have no parameter types. The
 * one exception is a default chain, where each shorter overload only calls the next longer one, passing constants
 * for the rest: the chain becomes one function that fills in the arguments a shorter overload leaves out.
 */
public final class Overloading {

	private final UnitLowering unit;
	private final JavaTypes types;

	public Overloading(UnitLowering unit) {
		this.unit = unit;
		this.types = unit.types;
	}

	/**
	 * A default chain: overloads where each shorter one only calls the next longer one, passing its own parameters
	 * first and constants for the rest. Lua keeps the longest, which fills in what the shorter ones would have passed.
	 *
	 * @param defaults the value each filled-in parameter of the longest gets, by position; a {@code null} literal
	 *     leaves the parameter nil
	 */
	public record DefaultChain(MethodTree longest, List<MethodTree> shorter, Map<Integer, ExpressionTree> defaults) {
	}

	/**
	 * The default chains among a class's methods and constructors.
	 *
	 * @param chains each chain by its longest overload
	 * @param longestOf the longest overload of each shorter one, which Lua merges into it
	 */
	public record Overloads(Map<MethodTree, DefaultChain> chains, Map<MethodTree, MethodTree> longestOf) {

		public @Nullable DefaultChain chain(@Nullable MethodTree longest) {
			return longest == null ? null : chains.get(longest);
		}
	}

	/**
	 * Names a Lua table cannot hold apart: two methods with one name (Lua functions have no parameter types), unless
	 * they form a default chain, and a field and a method with one name (a Lua object keeps both in the same table).
	 * Private methods are locals, so only clash with each other.
	 */
	public Overloads checkNames(ClassTree type) {
		Set<String> fields = new HashSet<>();
		Map<String, List<MethodTree>> groups = new LinkedHashMap<>();
		for (Tree member : type.getMembers()) {
			if (member instanceof VariableTree field) {
				fields.add(types.memberName(unit.element(field)));
			} else if (member instanceof MethodTree method && !unit.isSynthetic(method)) {
				String key = UnitLowering.isConstructor(method) ? "<init>" : types.memberName(unit.element(method));
				groups.computeIfAbsent(key, _ -> new ArrayList<>()).add(method);
			}
		}
		if (type.getKind() == Tree.Kind.ENUM) {
			for (Tree member : type.getMembers()) {
				if (member instanceof VariableTree field && unit.element(field).getKind() == ElementKind.FIELD
						&& (fields.contains("name") || fields.contains("ordinal"))
						&& (field.getName().contentEquals("name") || field.getName().contentEquals("ordinal"))) {
					unit.error(unit.start(field), "In Lua an enum constant keeps its name and ordinal in the fields"
							+ " name and ordinal; rename the field " + field.getName() + ", for example to label.");
				}
			}
		}
		Map<MethodTree, DefaultChain> chains = new HashMap<>();
		Map<MethodTree, MethodTree> longestOf = new HashMap<>();
		groups.forEach((name, methods) -> {
			MethodTree first = methods.getFirst();
			boolean constructors = UnitLowering.isConstructor(first);
			if (methods.size() > 1) {
				DefaultChain chain = defaultChain(methods);
				if (chain == null) {
					String message = constructors
							? "Lua functions have no parameter types, so a class has one constructor; turn the"
									+ " others into static methods with names of their own, or make the shorter"
									+ " ones a default chain (see " + Rejection.SUPPORTED_JAVA + ")."
							: "Lua functions have no parameter types, so two methods cannot share a name;"
									+ " rename one (for example " + name + "All, " + name + "One), or make the"
									+ " shorter ones a default chain (see " + Rejection.SUPPORTED_JAVA + ").";
					for (MethodTree method : methods.subList(1, methods.size())) {
						unit.error(unit.start(method), message);
					}
					return;
				}
				chains.put(chain.longest(), chain);
				chain.shorter().forEach(shorter -> longestOf.put(shorter, chain.longest()));
			}
			if (!constructors && fields.contains(name) && !first.getModifiers().getFlags().contains(Modifier.PRIVATE)) {
				unit.error(unit.start(first), "The field " + name + " and the method " + name
						+ "() would share one slot in the Lua table; rename one.");
			}
		});
		return new Overloads(chains, longestOf);
	}

	/** The overloads as a default chain, or null when they are not one. */
	private @Nullable DefaultChain defaultChain(List<MethodTree> methods) {
		List<MethodTree> sorted = methods.stream()
				.sorted(Comparator.comparingInt(method -> method.getParameters().size()))
				.toList();
		MethodTree longest = sorted.getLast();
		Set<Modifier> longestFlags = longest.getModifiers().getFlags();
		Map<Integer, ExpressionTree> defaults = new HashMap<>();
		for (int index = 0; index + 1 < sorted.size(); index++) {
			MethodTree shorter = sorted.get(index);
			MethodTree longer = sorted.get(index + 1);
			Set<Modifier> flags = shorter.getModifiers().getFlags();
			if (shorter.getParameters().size() == longer.getParameters().size()
					|| UnitLowering.isStatic(flags) != UnitLowering.isStatic(longestFlags)
					|| flags.contains(Modifier.PRIVATE) != longestFlags.contains(Modifier.PRIVATE)) {
				return null;
			}
			List<? extends ExpressionTree> arguments = delegation(shorter, longer);
			if (arguments == null) {
				return null;
			}
			for (int position = 0; position < arguments.size(); position++) {
				ExpressionTree argument = arguments.get(position);
				if (position < shorter.getParameters().size()) {
					if (!(argument instanceof IdentifierTree passed)
							|| !unit.element(passed).equals(unit.element(shorter.getParameters().get(position)))) {
						return null;
					}
					continue;
				}
				boolean isNull = argument instanceof LiteralTree literal
						&& literal.getKind() == Tree.Kind.NULL_LITERAL;
				// A filled-in parameter must not accept null, because a nil passed for it would get the default
				if (!isConstant(argument)
						|| (!isNull && types.isNullable(unit.type(longest.getParameters().get(position))))) {
					return null;
				}
				defaults.put(position, argument);
			}
		}
		return new DefaultChain(longest, sorted.subList(0, sorted.size() - 1), defaults);
	}

	/** The arguments of the one call a shorter overload makes to the next longer one, or null when it does more. */
	private @Nullable List<? extends ExpressionTree> delegation(MethodTree shorter, MethodTree longer) {
		BlockTree body = shorter.getBody();
		if (body == null || body.getStatements().size() != 1) {
			return null;
		}
		ExpressionTree value = switch (body.getStatements().getFirst()) {
			case ExpressionStatementTree statement -> statement.getExpression();
			case ReturnTree returned -> returned.getExpression();
			default -> null;
		};
		if (!(value instanceof MethodInvocationTree call)
				|| !unit.element(longer).equals(unit.trees.getElement(unit.path(call)))) {
			return null;
		}
		boolean ownReceiver = switch (call.getMethodSelect()) {
			case IdentifierTree _ -> true;
			case MemberSelectTree select -> (select.getExpression() instanceof IdentifierTree receiver
					&& (receiver.getName().contentEquals("this")
							|| JavaTypes.owner(unit.element(longer))
									.equals(unit.trees.getElement(unit.path(receiver)))));
			default -> false;
		};
		return ownReceiver ? call.getArguments() : null;
	}

	/**
	 * Whether a default argument is a constant: a literal, a negated number, an enum constant or a static final field.
	 */
	private boolean isConstant(ExpressionTree argument) {
		return switch (argument) {
			case LiteralTree _ -> true;
			case UnaryTree negation when negation.getKind() == Tree.Kind.UNARY_MINUS ->
					negation.getExpression() instanceof LiteralTree;
			case ParenthesizedTree parenthesized -> isConstant(parenthesized.getExpression());
			case IdentifierTree _, MemberSelectTree _ ->
					unit.trees.getElement(unit.path(argument)) instanceof VariableElement variable
							&& (variable.getKind() == ElementKind.ENUM_CONSTANT
									|| variable.getModifiers().containsAll(Set.of(Modifier.STATIC, Modifier.FINAL)));
			default -> false;
		};
	}

	/**
	 * What a default chain's shorter overloads would have passed, filled in at the start of the longest:
	 * {@code n = n or 1}, or for a boolean, which {@code or} would get wrong, {@code if flag == nil then ... end}.
	 */
	public List<Block.Entry> defaultFills(@Nullable MethodTree method, @Nullable DefaultChain chain,
			BodyLowering lowering) {
		if (method == null || chain == null) {
			return List.of();
		}
		List<Block.Entry> fills = new ArrayList<>();
		for (Map.Entry<Integer, ExpressionTree> filled : new TreeMap<>(chain.defaults()).entrySet()) {
			ExpressionTree value = filled.getValue();
			if (value instanceof LiteralTree literal && literal.getKind() == Tree.Kind.NULL_LITERAL) {
				continue;
			}
			VariableTree parameter = method.getParameters().get(filled.getKey());
			Expression local = name(unit.codeLocalName(unit.element(parameter), parameter));
			Expression lowered = lowering.expression(value);
			Statement fill = types.isBoolean(unit.type(parameter))
					? Statement.ifThen(
							binary(local, BinaryOperator.EQUAL, new Expression.Nil()),
							Block.of(Statement.assignment(local, lowered)))
					: Statement.assignment(local, binary(local, BinaryOperator.OR, lowered));
			fills.add(new Block.Entry(fill, Trivia.NONE, unit.origin(value)));
		}
		return fills;
	}

	/**
	 * Adds a default chain's shorter overloads to the declarations its longest one stands for, and the declarations its
	 * filled-in values use to the references.
	 */
	public void addChain(@Nullable DefaultChain chain, List<Element> declared, Set<Element> references) {
		if (chain == null) {
			return;
		}
		chain.shorter().forEach(method -> declared.add(unit.element(method)));
		chain.defaults().values().forEach(value -> references.addAll(unit.assembly().references(value)));
	}
}
