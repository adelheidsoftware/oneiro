package org.adelheid.oneiro.java;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.field;
import static org.adelheid.oneiro.lua.Expression.integer;
import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.Statement;
import org.adelheid.oneiro.lua.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Streams fused into loops: one pipeline, a source, then {@code filter} and {@code map} stages, then one terminal
 * operation, becomes one Lua loop, with the terminal's result built up in a local.
 *
 * <pre>{@code
 * List<String> names = npcs.stream().filter(npc -> npc.level > 10).map(npc -> npc.name).toList();
 * }</pre>
 *
 * <pre>{@code
 * local names = {}
 * for _, npc in ipairs(npcs) do
 *     if npc.level > 10 then
 *         names[#names + 1] = npc.name
 *     end
 * end
 * }</pre>
 *
 * <p>A loop is statements, so a stream is the whole value of a local, an assignment or a return, or a
 * {@code forEach} statement of its own. A {@code return} of {@code anyMatch} or {@code findFirst} returns from inside
 * the loop. Each lambda's parameter stands for the loop's current element, so the lambdas' code reads as written.
 */
final class StreamLoops {

	private static final String SUPPORTED = "Oneiro makes one loop of a stream: a list, set, array, map view,"
			+ " Stream.of or IntStream.range, then filter and map, then forEach, toList, collect to a list, set or"
			+ " joined string, count, sum, min, max, findFirst, anyMatch, allMatch or noneMatch";

	/** Where a stream's result goes. */
	sealed interface Destination {
		/** Into a new local of this name. */
		record Local(String name) implements Destination {
		}

		/** Returned from the function. */
		record Returned() implements Destination {
		}

		/** To the sink, which assigns it. */
		record Sink(Function<Expression, Statement> sink) implements Destination {
		}

		/** Nowhere: the stream ends in {@code forEach}. */
		record Effect() implements Destination {
		}
	}

	/**
	 * A stream from its source to its terminal operation, and the {@code Optional} method called on a terminal that
	 * gives an {@code Optional}, such as {@code findFirst().orElse(d)}.
	 */
	record Pipeline(ExpressionTree source, List<MethodInvocationTree> stages, MethodInvocationTree terminal,
			@Nullable MethodInvocationTree then) {
	}

	private final BodyLowering body;

	StreamLoops(BodyLowering body) {
		this.body = body;
	}

	private JavaTypes types() {
		return body.types();
	}

	private UnitLowering unit() {
		return body.unit;
	}

	/** The pipeline a value is, or null when it is not a stream's result. */
	@Nullable Pipeline pipeline(ExpressionTree value) {
		ExpressionTree current = BodyLowering.withoutParentheses(value);
		MethodInvocationTree then = null;
		ExpressionTree optional = receiver(current);
		if (current instanceof MethodInvocationTree call && optional != null && !isTypeName(optional)
				&& types().isOptional(unit().type(optional))) {
			then = call;
			current = BodyLowering.withoutParentheses(optional);
		}
		if (!(current instanceof MethodInvocationTree terminal) || !isStreamValue(receiver(terminal))
				|| types().isStream(unit().type(terminal))) {
			return null;
		}
		List<MethodInvocationTree> stages = new ArrayList<>();
		ExpressionTree source = BodyLowering.withoutParentheses(receiverOf(terminal));
		while (source instanceof MethodInvocationTree stage && isStreamValue(receiver(stage))) {
			stages.addFirst(stage);
			source = BodyLowering.withoutParentheses(receiverOf(stage));
		}
		return new Pipeline(source, stages, terminal, then);
	}

	/** The statements that run a pipeline and hand its result to the destination. */
	List<Statement> lower(Pipeline pipeline, Destination destination) {
		return new Fusion(pipeline, destination).lower();
	}

	private static @Nullable ExpressionTree receiver(ExpressionTree tree) {
		return tree instanceof MethodInvocationTree call && call.getMethodSelect() instanceof MemberSelectTree select
				? select.getExpression()
				: null;
	}

	/** The receiver of a call known to have one. */
	private static ExpressionTree receiverOf(MethodInvocationTree call) {
		ExpressionTree receiver = receiver(call);
		if (receiver == null) {
			throw new IllegalStateException("a call without a receiver: " + call);
		}
		return receiver;
	}

	private static String method(MethodInvocationTree call) {
		return call.getMethodSelect() instanceof MemberSelectTree select
				? select.getIdentifier().toString()
				: call.getMethodSelect().toString();
	}

	/** Whether a tree is a stream value, not the name of a stream class such as {@code IntStream} in a static call. */
	private boolean isStreamValue(@Nullable ExpressionTree tree) {
		return tree != null && !isTypeName(tree) && types().isStream(unit().type(tree));
	}

	private boolean isTypeName(ExpressionTree tree) {
		return (tree instanceof IdentifierTree || tree instanceof MemberSelectTree)
				&& unit().element(tree) instanceof TypeElement;
	}

	/** The loop over a source, made once the element's name is known, and the name to give it otherwise. */
	private record Source(String defaultName, BiFunction<String, Block, Statement> loop) {
	}

	/** One step inside the loop, before the terminal's statements. */
	private sealed interface Step {
		/** {@code if condition then}, around everything after it. */
		record Guard(Expression condition) implements Step {
		}

		/** {@code local name = value}, a mapped value that later steps read. */
		record Bind(String name, Expression value) implements Step {
		}
	}

	/** One pipeline being lowered. */
	private final class Fusion {

		private final Pipeline pipeline;
		private final Destination destination;
		// The locals of functions the loop calls, which go before it
		private final List<Statement> functions = new ArrayList<>();
		private final List<Step> steps = new ArrayList<>();
		// The names the loop's variable and its mapped values have taken
		private final Set<String> used = new HashSet<>();
		private String element = "_";
		// A mapped value no step has read yet, which goes straight into the terminal when that reads it once
		private @Nullable Expression pending;
		private boolean sorted;
		private @Nullable ExpressionTree sortedBy;

		Fusion(Pipeline pipeline, Destination destination) {
			this.pipeline = pipeline;
			this.destination = destination;
		}

		List<Statement> lower() {
			Source source = source(pipeline.source());
			element = elementName(source.defaultName());
			String loopVariable = element;
			used.add(element);
			List<MethodInvocationTree> stages = pipeline.stages();
			for (int index = 0; index < stages.size(); index++) {
				stage(stages.get(index), index == stages.size() - 1);
			}
			List<Statement> init = new ArrayList<>();
			List<Statement> after = new ArrayList<>();
			Block innermost = terminal(init, after);
			List<Statement> lowered = new ArrayList<>(functions);
			lowered.addAll(init);
			lowered.add(source.loop().apply(loopVariable, nested(innermost)));
			lowered.addAll(after);
			return lowered;
		}

		// ---- The source

		private Source source(ExpressionTree source) {
			if (source instanceof MethodInvocationTree call
					&& call.getMethodSelect() instanceof MemberSelectTree select) {
				String method = select.getIdentifier().toString();
				ExpressionTree owner = select.getExpression();
				List<? extends ExpressionTree> arguments = call.getArguments();
				if (isTypeName(owner)) {
					String typeName = ((TypeElement) unit().element(owner)).getQualifiedName().toString();
					Source made = staticSource(typeName.replace("java.util.stream.", "") + "." + method, arguments);
					if (made != null) {
						return made;
					}
				} else if (method.equals("stream") && arguments.isEmpty()) {
					Source made = collectionSource(owner);
					if (made != null) {
						return made;
					}
				} else if ((method.equals("chars") || method.equals("codePoints"))
						&& types().isString(unit().type(owner))) {
					throw new Rejection(call, LibraryCalls.NO_CHAR_STREAM);
				}
			}
			throw new Rejection(source, SUPPORTED + "; write a for loop for this one.");
		}

		private @Nullable Source staticSource(String method, List<? extends ExpressionTree> arguments) {
			return switch (method) {
				case "java.util.Arrays.stream" ->
						arguments.size() == 1 ? listed(body.expression(arguments.getFirst())) : null;
				case "Stream.of", "IntStream.of", "LongStream.of", "DoubleStream.of" -> arguments.size() == 1
						&& unit().type(arguments.getFirst()).getKind() == TypeKind.ARRAY
						? listed(body.expression(arguments.getFirst()))
						: listed(new Expression.Table(arguments.stream()
								.map(argument -> (Expression.TableField) new Expression.TableField.Positional(
										body.expression(argument)))
								.toList()));
				case "IntStream.range", "LongStream.range" -> counted(arguments.get(0), lastBefore(arguments.get(1)));
				case "IntStream.rangeClosed", "LongStream.rangeClosed" ->
						counted(arguments.get(0), body.expression(arguments.get(1)));
				default -> null;
			};
		}

		/** A list's or a set's elements, or a map's keys or values, which walk the map itself. */
		private @Nullable Source collectionSource(ExpressionTree owner) {
			TypeMirror type = unit().type(owner);
			if (types().isList(type) || types().isDeque(type)) {
				return listed(body.expression(owner));
			}
			if (owner instanceof MethodInvocationTree view && view.getArguments().isEmpty()
					&& view.getMethodSelect() instanceof MemberSelectTree select
					&& types().isMap(unit().type(select.getExpression()))) {
				Expression map = call(name("pairs"), body.expression(select.getExpression()));
				return switch (select.getIdentifier().toString()) {
					case "keySet" -> new Source("key", (item, loop) -> new Statement.GenericFor(List.of(item),
							List.of(map), loop));
					case "values" -> new Source("value", (item, loop) -> new Statement.GenericFor(
							item.equals("_") ? List.of("_") : List.of("_", item), List.of(map), loop));
					case "entrySet" -> throw new Rejection(view, "A stream of a map's entries has no Lua form;"
							+ " loop over entrySet() with a for loop, which reads each key and value.");
					default -> null;
				};
			}
			if (types().isSet(type)) {
				Expression set = call(name("pairs"), body.expression(owner));
				return new Source("item", (item, loop) -> new Statement.GenericFor(List.of(item), List.of(set), loop));
			}
			return null;
		}

		private Source listed(Expression list) {
			Expression elements = call(name("ipairs"), list);
			return new Source("item", (item, loop) -> new Statement.GenericFor(
					item.equals("_") ? List.of("_") : List.of("_", item), List.of(elements), loop));
		}

		private Source counted(ExpressionTree start, Expression last) {
			Expression first = body.expression(start);
			return new Source("i", (item, loop) -> new Statement.NumericFor(item, first, last, null, loop));
		}

		/** The last number of {@code range(a, b)}: {@code b - 1}, folded for a literal. */
		private Expression lastBefore(ExpressionTree end) {
			if (end instanceof LiteralTree literal && literal.getValue() instanceof Integer bound) {
				return integer(bound - 1L);
			}
			return binary(body.expression(end), BinaryOperator.SUBTRACT, integer(1));
		}

		/**
		 * The loop variable's name: the parameter of the first lambda that reads the element, so that its code reads as
		 * written; otherwise the source's name for its elements, or {@code _} when nothing reads them.
		 */
		private String elementName(String defaultName) {
			List<MethodInvocationTree> consumers = new ArrayList<>(pipeline.stages());
			consumers.add(pipeline.terminal());
			for (MethodInvocationTree consumer : consumers) {
				String method = method(consumer);
				if (List.of("boxed", "asLongStream", "asDoubleStream", "sorted").contains(method)) {
					continue;
				}
				if (!method.equals("collect") && !consumer.getArguments().isEmpty()
						&& BodyLowering.withoutParentheses(consumer.getArguments().getFirst())
								instanceof LambdaExpressionTree lambda
						&& lambda.getParameters().size() == 1) {
					return body.declare(lambda.getParameters().getFirst());
				}
				break;
			}
			if (pipeline.stages().isEmpty() && method(pipeline.terminal()).equals("count")) {
				return "_";
			}
			return unit().freshLocal(defaultName, pipeline.source());
		}

		// ---- Stages

		private void stage(MethodInvocationTree stage, boolean last) {
			List<? extends ExpressionTree> arguments = stage.getArguments();
			switch (method(stage)) {
				case "filter" -> steps.add(new Step.Guard(test(arguments.getFirst())));
				case "map", "mapToInt", "mapToLong", "mapToDouble", "mapToObj" ->
						pending = mapped(arguments.getFirst());
				case "boxed", "asLongStream", "asDoubleStream" -> {
					// The same numbers in Lua
				}
				case "sorted" -> {
					if (!last || !collectsList(pipeline.terminal())) {
						throw new Rejection(stage, "A stream's loop sees one element at a time, so it sorts only what"
								+ " it collects; sort just before toList(), or sort the list afterwards.");
					}
					sorted = true;
					sortedBy = arguments.isEmpty() ? null : arguments.getFirst();
				}
				default -> throw unsupported(stage);
			}
		}

		private Rejection unsupported(MethodInvocationTree call) {
			return new Rejection(call, SUPPORTED + "; write a for loop for " + method(call) + ".");
		}

		/** A predicate's test of the current element: the lambda's expression, or a call of the function. */
		private Expression test(ExpressionTree predicate) {
			if (BodyLowering.withoutParentheses(predicate) instanceof LambdaExpressionTree lambda) {
				if (!(lambda.getBody() instanceof ExpressionTree condition)) {
					throw new Rejection(predicate, "A stream's test becomes an if inside the loop; write it as an"
							+ " expression, x -> condition.");
				}
				bindElement(lambda.getParameters().getFirst());
				return body.expression(condition);
			}
			bindPending("value", predicate);
			return applied(predicate);
		}

		/** A function's value for the current element: the lambda's expression, or a call of the function. */
		private Expression mapped(ExpressionTree function) {
			if (BodyLowering.withoutParentheses(function) instanceof LambdaExpressionTree lambda) {
				if (!(lambda.getBody() instanceof ExpressionTree value)) {
					throw new Rejection(function, "A stream's map becomes a value inside the loop; write it as an"
							+ " expression, x -> value.");
				}
				bindElement(lambda.getParameters().getFirst());
				return body.expression(value);
			}
			return applied(function);
		}

		/** The current element: a pending mapped value, which this reads once, or the element's name. */
		private Expression current() {
			Expression value = pending;
			pending = null;
			return value != null ? value : name(element);
		}

		/** Gives a pending mapped value a local, so that it can be read more than once. */
		private void bindPending(String preferred, Tree where) {
			Expression value = pending;
			if (value != null) {
				String local = used.contains(preferred) ? body.invent(preferred, where) : preferred;
				steps.add(new Step.Bind(local, value));
				used.add(local);
				element = local;
				pending = null;
			}
		}

		/** Makes a lambda's parameter stand for the current element, which a pending value is bound for first. */
		private void bindElement(VariableTree parameter) {
			String own = body.declare(parameter);
			bindPending(own, parameter);
			body.alias(parameter, element);
		}

		/** A function called on the current element; a function expression the call cannot open goes into a local. */
		private Expression applied(ExpressionTree function) {
			Expression lowered = body.expression(function);
			Expression argument = current();
			Expression applied = body.lambdas().applied(lowered, List.of(argument));
			if (applied instanceof Expression.Call call && call.function() instanceof Expression.FunctionExpression) {
				String local = body.invent("action", function);
				functions.add(new Statement.Local(List.of(local), List.of(lowered)));
				return new Expression.Call(name(local), List.of(argument));
			}
			return applied;
		}

		// ---- The terminal operation

		/** The terminal's statements: its locals before the loop, its code inside it, and its result after it. */
		private Block terminal(List<Statement> init, List<Statement> after) {
			MethodInvocationTree terminal = pipeline.terminal();
			String operation = method(terminal);
			List<? extends ExpressionTree> arguments = terminal.getArguments();
			if (destination instanceof Destination.Effect && !operation.equals("forEach")
					&& !operation.equals("forEachOrdered")) {
				throw new Rejection(terminal, "The result of this stream is unused; use it, or remove the line.");
			}
			return switch (operation) {
				case "forEach", "forEachOrdered" -> forEach(arguments.getFirst());
				case "toList" -> collected("toList", init, after);
				case "collect" -> {
					String collector = collector(arguments.getFirst());
					if (collector == null) {
						throw unsupported(terminal);
					}
					yield collector.equals("joining")
							? joined((MethodInvocationTree) BodyLowering.withoutParentheses(arguments.getFirst()), init,
									after)
							: collected(collector, init, after);
				}
				case "count" -> {
					String count = accumulator("count");
					init.add(local(count, integer(0)));
					deliver(after, name(count));
					yield Block.of(assign(count, binary(name(count), BinaryOperator.ADD, integer(1))));
				}
				case "sum" -> {
					String sum = accumulator("sum");
					boolean floating = types().is(unit().type(receiverOf(terminal)), "java.util.stream.DoubleStream");
					init.add(local(sum, floating ? new Expression.NumberLiteral("0.0") : integer(0)));
					deliver(after, name(sum));
					yield Block.of(assign(sum, binary(name(sum), BinaryOperator.ADD, current())));
				}
				case "min", "max" -> extreme(operation, arguments.isEmpty() ? null : arguments.getFirst(), init, after);
				case "findFirst", "findAny" -> first(init, after);
				case "anyMatch", "allMatch", "noneMatch" -> match(operation, arguments.getFirst(), init, after);
				default -> throw unsupported(terminal);
			};
		}

		private Block forEach(ExpressionTree action) {
			if (BodyLowering.withoutParentheses(action) instanceof LambdaExpressionTree lambda
					&& !Lambdas.returnsEarly(lambda)) {
				bindElement(lambda.getParameters().getFirst());
				Lambdas.InlinedLambda inlined = body.lambdas().inline(action, List.of(element));
				if (inlined != null) {
					return inlined.body();
				}
			}
			return Block.of(new Statement.CallStatement(applied(action)));
		}

		/** {@code toList()}, {@code collect(Collectors.toList())} or {@code toSet()}: a table the loop fills. */
		private Block collected(String collector, List<Statement> init, List<Statement> after) {
			String table = accumulator(collector.equals("toSet") ? "set" : "list");
			init.add(local(table, new Expression.Table(List.of())));
			if (sorted) {
				ExpressionTree comparator = sortedBy;
				Expression sort = comparator == null
						? call(field(name("table"), "sort"), name(table))
						: call(field(name("table"), "sort"), name(table), body.comparators().function(comparator));
				after.add(new Statement.CallStatement(sort));
			}
			deliver(after, name(table));
			if (collector.equals("toSet")) {
				return Block.of(new Statement.Assignment(List.of(new Expression.Index(name(table), current())),
						List.of(new Expression.BooleanLiteral(true))));
			}
			return Block.of(new Statement.Assignment(List.of(new Expression.Index(name(table),
					binary(new Expression.Unary(UnaryOperator.LENGTH, name(table)), BinaryOperator.ADD, integer(1)))),
					List.of(current())));
		}

		/** {@code collect(Collectors.joining(", "))}: the parts in a list, joined with {@code table.concat}. */
		private Block joined(MethodInvocationTree joining, List<Statement> init, List<Statement> after) {
			String parts = body.invent("parts", joining);
			init.add(local(parts, new Expression.Table(List.of())));
			List<? extends ExpressionTree> arguments = joining.getArguments();
			Expression joinedParts = arguments.isEmpty()
					? call(field(name("table"), "concat"), name(parts))
					: call(field(name("table"), "concat"), name(parts), body.expression(arguments.getFirst()));
			if (arguments.size() == 3) {
				joinedParts = binary(body.expression(arguments.get(1)), BinaryOperator.CONCAT,
						binary(joinedParts, BinaryOperator.CONCAT, body.expression(arguments.get(2))));
			}
			deliver(after, joinedParts);
			return Block.of(new Statement.Assignment(List.of(new Expression.Index(name(parts),
					binary(new Expression.Unary(UnaryOperator.LENGTH, name(parts)), BinaryOperator.ADD, integer(1)))),
					List.of(current())));
		}

		/** {@code min} or {@code max}: the best element so far, nil until the first. */
		private Block extreme(String operation, @Nullable ExpressionTree comparator, List<Statement> init,
				List<Statement> after) {
			bindPending("value", pipeline.terminal());
			String best = accumulator(operation);
			init.add(new Statement.Local(List.of(best), List.of()));
			Expression better = operation.equals("min")
					? body.comparators().comesBefore(comparator, element, best)
					: body.comparators().comesBefore(comparator, best, element);
			deliver(after, name(best));
			return Block.of(new Statement.If(List.of(new Statement.If.Branch(
					binary(binary(name(best), BinaryOperator.EQUAL, new Expression.Nil()), BinaryOperator.OR, better),
					Block.of(assign(best, name(element))))), null));
		}

		/** {@code findFirst()}: returned from inside the loop when the stream is returned, otherwise kept. */
		private Block first(List<Statement> init, List<Statement> after) {
			MethodInvocationTree then = pipeline.then();
			if (destination instanceof Destination.Returned
					&& (then == null || method(then).equals("orElse"))) {
				ExpressionTree fallback = then == null ? null : then.getArguments().getFirst();
				after.add(new Statement.Return(List.of(fallback == null
						|| (fallback instanceof LiteralTree literal && literal.getValue() == null)
						? new Expression.Nil()
						: body.expression(fallback))));
				return Block.of(new Statement.Return(List.of(current())));
			}
			String found = accumulator("first");
			init.add(new Statement.Local(List.of(found), List.of()));
			deliver(after, name(found));
			return Block.of(assign(found, current()), new Statement.Break());
		}

		/**
		 * {@code anyMatch}, {@code allMatch} or {@code noneMatch}: a test that ends the loop once it decides, returning
		 * the answer from inside the loop when the stream is returned.
		 */
		private Block match(String operation, ExpressionTree predicate, List<Statement> init, List<Statement> after) {
			Expression test = test(predicate);
			boolean any = operation.equals("anyMatch");
			steps.add(new Step.Guard(operation.equals("allMatch") ? negated(test) : test));
			if (destination instanceof Destination.Returned) {
				after.add(new Statement.Return(List.of(new Expression.BooleanLiteral(!any))));
				return Block.of(new Statement.Return(List.of(new Expression.BooleanLiteral(any))));
			}
			String answer = accumulator(operation.replace("Match", ""));
			init.add(local(answer, new Expression.BooleanLiteral(!any)));
			deliver(after, name(answer));
			return Block.of(assign(answer, new Expression.BooleanLiteral(any)), new Statement.Break());
		}

		/** The collector a {@code collect} takes: toList, toSet or joining; null for any other. */
		private @Nullable String collector(ExpressionTree argument) {
			if (!(BodyLowering.withoutParentheses(argument) instanceof MethodInvocationTree call)
					|| !(unit().element(call) instanceof ExecutableElement method)
					|| !JavaTypes.owner(method).getQualifiedName().contentEquals("java.util.stream.Collectors")) {
				return null;
			}
			return switch (method.getSimpleName().toString()) {
				case "toList", "toUnmodifiableList" -> "toList";
				case "toSet", "toUnmodifiableSet" -> "toSet";
				case "joining" -> "joining";
				default -> null;
			};
		}

		private boolean collectsList(MethodInvocationTree terminal) {
			return method(terminal).equals("toList")
					|| (method(terminal).equals("collect")
					&& "toList".equals(collector(terminal.getArguments().getFirst())));
		}

		/**
		 * The local a terminal builds its result in: the destination's own local when the result goes there as it is,
		 * otherwise a new one.
		 */
		private String accumulator(String preferred) {
			if (destination instanceof Destination.Local local && pipeline.then() == null) {
				return local.name();
			}
			return body.invent(preferred, pipeline.terminal());
		}

		/** Hands the result to the destination after the loop, through the Optional method called on it, if any. */
		private void deliver(List<Statement> after, Expression result) {
			MethodInvocationTree then = pipeline.then();
			Expression value = result;
			if (then != null) {
				value = body.library().optionalMethod(then, method(then), result, unit().type(receiverOf(then)),
						then.getArguments());
			}
			switch (destination) {
				case Destination.Local local -> {
					if (!(value instanceof Expression.Name built && built.name().equals(local.name()))) {
						after.add(local(local.name(), value));
					}
				}
				case Destination.Returned _ -> after.add(new Statement.Return(List.of(value)));
				case Destination.Sink sink -> after.add(sink.sink().apply(value));
				case Destination.Effect _ -> {
				}
			}
		}

		// ---- The loop's body

		/** The steps around the terminal's code: each guard an if around what follows, consecutive guards one if. */
		private Block nested(Block innermost) {
			Block block = innermost;
			for (int index = steps.size() - 1; index >= 0; index--) {
				switch (steps.get(index)) {
					case Step.Bind bind -> block = LibraryCalls.prepended(local(bind.name(), bind.value()), block);
					case Step.Guard guard -> {
						Expression condition = guard.condition();
						// filter(a).filter(b) is if a and b
						while (index > 0 && steps.get(index - 1) instanceof Step.Guard earlier) {
							condition = binary(earlier.condition(), BinaryOperator.AND, condition);
							index--;
						}
						block = Block.of(new Statement.If(List.of(new Statement.If.Branch(condition, block)), null));
					}
				}
			}
			return block;
		}
	}

	private static Statement local(String name, Expression value) {
		return new Statement.Local(List.of(name), List.of(value));
	}

	private static Statement assign(String name, Expression value) {
		return new Statement.Assignment(List.of(name(name)), List.of(value));
	}

	/** The test turned around: {@code a ~= b} for {@code a == b}, otherwise {@code not test}. */
	private static Expression negated(Expression test) {
		if (test instanceof Expression.Binary comparison && (comparison.operator() == BinaryOperator.EQUAL
				|| comparison.operator() == BinaryOperator.NOT_EQUAL)) {
			return binary(comparison.left(), comparison.operator() == BinaryOperator.EQUAL
					? BinaryOperator.NOT_EQUAL
					: BinaryOperator.EQUAL, comparison.right());
		}
		if (test instanceof Expression.Unary unary && unary.operator() == UnaryOperator.NOT) {
			return unary.operand();
		}
		return new Expression.Unary(UnaryOperator.NOT, test);
	}
}
