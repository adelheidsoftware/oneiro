package org.adelheid.oneiro.java.library;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.field;
import static org.adelheid.oneiro.lua.Expression.integer;
import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.adelheid.oneiro.java.core.BodyLowering;
import org.adelheid.oneiro.java.core.JavaTypes;
import org.adelheid.oneiro.java.core.Rejection;
import org.adelheid.oneiro.java.core.UnitLowering;
import org.adelheid.oneiro.java.language.Lambdas;
import org.adelheid.oneiro.java.language.Operators;
import org.adelheid.oneiro.lowering.translation.Inlining;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Comment;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.Statement;
import org.adelheid.oneiro.lua.Trivia;
import org.adelheid.oneiro.lua.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Streams fused into loops: one pipeline, a source, then stages such as {@code filter}, {@code map} and {@code limit},
 * then one terminal operation, becomes one Lua loop, with the terminal's result built up in a local. A
 * {@code sorted} with stages after it ends a loop of its own, whose list the rest of the stream walks once sorted.
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
 * <p>A loop is statements, so a stream inside an expression runs before its statement, into a local; see
 * {@link BodyLowering#hoistedStream}. A {@code return} of {@code anyMatch} or {@code findFirst} returns from inside
 * the loop. Each lambda's parameter stands for the loop's current element, so the lambdas' code reads as written,
 * and the statements of a lambda's block run in the loop before the value it returns.
 */
public final class StreamLoops {

	private static final String SUPPORTED = "Oneiro makes one loop of a stream: a list, set, array, map view,"
			+ " Stream.of or IntStream.range, then filter, map, sorted, limit and skip, then forEach, toList, collect"
			+ " to a list, set or joined string, count, sum, min, max, findFirst, anyMatch, allMatch or noneMatch";

	/** Where a stream's result goes. */
	public sealed interface Destination {
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
	public record Pipeline(ExpressionTree source, List<MethodInvocationTree> stages, MethodInvocationTree terminal,
			@Nullable MethodInvocationTree then) {
	}

	private final BodyLowering body;
	private final UnitLowering unit;
	private final JavaTypes types;

	public StreamLoops(BodyLowering body) {
		this.body = body;
		this.unit = body.unit;
		this.types = body.types();
	}

	/** The pipeline a value is, or null when it is not a stream's result. */
	public @Nullable Pipeline pipeline(ExpressionTree value) {
		ExpressionTree current = BodyLowering.withoutParentheses(value);
		MethodInvocationTree then = null;
		ExpressionTree optional = receiver(current);
		if (current instanceof MethodInvocationTree call && optional != null && !isTypeName(optional)
				&& types.isOptional(unit.type(optional))) {
			then = call;
			current = BodyLowering.withoutParentheses(optional);
		}
		if (!(current instanceof MethodInvocationTree terminal) || !isStreamValue(receiver(terminal))
				|| types.isStream(unit.type(terminal))) {
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
	public List<Statement> lower(Pipeline pipeline, Destination destination) {
		return new Fusion(pipeline, destination, pipeline.stages(), null, null).lower();
	}

	/**
	 * The name of a local that holds a pipeline's result, after the terminal that gives it, as the loop's own locals
	 * are named: {@code count}, {@code sum}, {@code min}, {@code max}, {@code first}, {@code any}, {@code all},
	 * {@code none}, {@code text} for a joined string, a {@code set} or a {@code list}.
	 */
	public String resultName(Pipeline pipeline) {
		MethodInvocationTree terminal = pipeline.terminal();
		return switch (method(terminal)) {
			case "count", "sum", "min", "max" -> method(terminal);
			case "findFirst", "findAny" -> "first";
			case "anyMatch", "allMatch", "noneMatch" -> method(terminal).replace("Match", "");
			case "collect" -> switch (String.valueOf(collector(terminal.getArguments().getFirst()))) {
				case "toSet" -> "set";
				case "joining" -> "text";
				default -> "list";
			};
			default -> "list";
		};
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
		return tree != null && !isTypeName(tree) && types.isStream(unit.type(tree));
	}

	private boolean isTypeName(ExpressionTree tree) {
		return (tree instanceof IdentifierTree || tree instanceof MemberSelectTree)
				&& unit.element(tree) instanceof TypeElement;
	}

	/** The collector a {@code collect} takes: toList, toSet or joining; null for any other. */
	private @Nullable String collector(ExpressionTree argument) {
		if (!(BodyLowering.withoutParentheses(argument) instanceof MethodInvocationTree call)
				|| !(unit.element(call) instanceof ExecutableElement method)
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
		return method(terminal).equals("toList") || (method(terminal).equals("collect")
				&& "toList".equals(collector(terminal.getArguments().getFirst())));
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

		/** A statement for each element that gets this far, such as counting it. */
		record Do(Statement statement) implements Step {
		}

		/**
		 * The statements of a lambda before its value, for each element that gets this far; the comments that close
		 * the block go before what comes after it.
		 */
		record Code(Block code) implements Step {
		}
	}

	/**
	 * One loop of a pipeline being lowered: its stages, from the pipeline's source or from a list an earlier loop
	 * sorted, to the pipeline's terminal or to a sort.
	 */
	private final class Fusion {

		private final Pipeline pipeline;
		private final Destination destination;
		private final List<MethodInvocationTree> stages;
		// The local holding the list an earlier loop sorted, which this loop walks instead of the pipeline's source
		private final @Nullable String sortedList;
		// The sort this loop ends in, which collects a list and sorts it, instead of the pipeline's terminal
		private final @Nullable MethodInvocationTree sort;
		// The locals the loop needs, which go before it: functions it calls, and what limit and skip count
		private final List<Statement> before = new ArrayList<>();
		private final List<Step> steps = new ArrayList<>();
		// The names the loop's variable and its mapped values have taken
		private final Set<String> used = new HashSet<>();
		private String element = "_";
		// A mapped value no step has read yet, which goes straight into the terminal when that reads it once
		private @Nullable Expression pending;

		Fusion(Pipeline pipeline, Destination destination, List<MethodInvocationTree> stages,
				@Nullable String sortedList, @Nullable MethodInvocationTree sort) {
			this.pipeline = pipeline;
			this.destination = destination;
			this.stages = stages;
			this.sortedList = sortedList;
			this.sort = sort;
		}

		List<Statement> lower() {
			int sortAt = sortAt();
			if (sortAt >= 0) {
				return sortedFirst(sortAt);
			}
			if (sort != null && stages.isEmpty()) {
				Expression list = sourceList();
				if (list != null) {
					return sortedCopy(list);
				}
			}
			Source source = sortedList != null ? listed(name(sortedList)) : source(pipeline.source());
			element = elementName(source.defaultName());
			String loopVariable = element;
			used.add(element);
			stages.forEach(this::stage);
			List<Statement> init = new ArrayList<>();
			List<Statement> after = new ArrayList<>();
			Block innermost = terminal(init, after);
			List<Statement> lowered = new ArrayList<>(before);
			lowered.addAll(init);
			lowered.add(source.loop().apply(loopVariable, nested(innermost)));
			lowered.addAll(after);
			return lowered;
		}

		// ---- Sorts

		/**
		 * The position of the first {@code sorted} among the stages, which a loop of its own must end, or -1 for none:
		 * a {@code sorted} just before a terminal that collects a list ends this loop, which sorts what it collects.
		 */
		private int sortAt() {
			for (int index = 0; index < stages.size(); index++) {
				if (method(stages.get(index)).equals("sorted")) {
					return index;
				}
			}
			return -1;
		}

		/**
		 * The stream up to its first sort, as a loop of its own that collects and sorts a list, and then the rest of
		 * the stream, as a loop over that list: one loop for {@code sorted().toList()}, whose list is the result.
		 */
		private List<Statement> sortedFirst(int sortAt) {
			MethodInvocationTree first = stages.get(sortAt);
			List<MethodInvocationTree> stagesBefore = stages.subList(0, sortAt);
			if (sortAt == stages.size() - 1 && collectsList(pipeline.terminal())) {
				return new Fusion(pipeline, destination, stagesBefore, sortedList, first).lower();
			}
			String sorted = body.invent("sorted", first);
			List<MethodInvocationTree> stagesAfter = stages.subList(sortAt + 1, stages.size());
			List<Statement> lowered = new ArrayList<>(
					new Fusion(pipeline, new Destination.Local(sorted), stagesBefore, sortedList, first).lower());
			lowered.addAll(new Fusion(pipeline, destination, stagesAfter, sorted, null).lower());
			return lowered;
		}

		/** The list the stream walks, where that is a list it can copy whole; null for any other source. */
		private @Nullable Expression sourceList() {
			if (sortedList != null) {
				return name(sortedList);
			}
			if (pipeline.source() instanceof MethodInvocationTree call && call.getArguments().isEmpty()
					&& call.getMethodSelect() instanceof MemberSelectTree select
					&& select.getIdentifier().contentEquals("stream")) {
				TypeMirror type = unit.type(select.getExpression());
				if (types.isList(type) || types.isDeque(type)) {
					return body.reused(select.getExpression(), "Lua reads this list twice, to copy it");
				}
			}
			return null;
		}

		/** A sort of a list with no stages before it: a copy of the list, sorted. */
		private List<Statement> sortedCopy(Expression list) {
			String table = accumulator("list");
			Expression copy = call(field(name("table"), "move"), list, integer(1), LibraryCalls.length(list),
					integer(1), new Expression.Table(List.of()));
			List<Statement> lowered = new ArrayList<>();
			lowered.add(local(table, copy));
			lowered.add(sorting(table));
			deliver(lowered, name(table));
			return lowered;
		}

		/** {@code table.sort(list)}, in the order the sort's comparator gives. */
		private Statement sorting(String table) {
			List<? extends ExpressionTree> arguments = Objects.requireNonNull(sort).getArguments();
			Expression tableSort = field(name("table"), "sort");
			return new Statement.CallStatement(arguments.isEmpty()
					? call(tableSort, name(table))
					: call(tableSort, name(table), body.comparators().function(arguments.getFirst())));
		}

		// ---- The source

		private Source source(ExpressionTree source) {
			if (source instanceof MethodInvocationTree call
					&& call.getMethodSelect() instanceof MemberSelectTree select) {
				String method = select.getIdentifier().toString();
				ExpressionTree owner = select.getExpression();
				List<? extends ExpressionTree> arguments = call.getArguments();
				if (isTypeName(owner)) {
					String typeName = ((TypeElement) unit.element(owner)).getQualifiedName().toString();
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
						&& types.isString(unit.type(owner))) {
					throw new Rejection(call, LibraryCalls.NO_CHAR_STREAM);
				}
			}
			throw new Rejection(source, SUPPORTED + "; write a for loop for this one.");
		}

		private @Nullable Source staticSource(String method, List<? extends ExpressionTree> arguments) {
			return switch (method) {
				case "java.util.Arrays.stream" ->
						arguments.size() == 1 ? listed(body.expression(arguments.getFirst())) : null;
				case "Stream.of", "IntStream.of", "LongStream.of", "DoubleStream.of" -> streamOf(arguments);
				case "IntStream.range", "LongStream.range" -> counted(arguments.get(0), lastBefore(arguments.get(1)));
				case "IntStream.rangeClosed", "LongStream.rangeClosed" ->
						counted(arguments.get(0), body.expression(arguments.get(1)));
				default -> null;
			};
		}

		/** {@code Stream.of(array)} walks the array, and {@code Stream.of(a, b, c)} a table of the values. */
		private Source streamOf(List<? extends ExpressionTree> arguments) {
			if (arguments.size() == 1 && unit.type(arguments.getFirst()).getKind() == TypeKind.ARRAY) {
				return listed(body.expression(arguments.getFirst()));
			}
			List<Expression.TableField> values = new ArrayList<>();
			for (ExpressionTree argument : arguments) {
				values.add(new Expression.TableField.Positional(body.expression(argument)));
			}
			return listed(new Expression.Table(values));
		}

		/** A list's or a set's elements, or a map's keys or values, which walk the map itself. */
		private @Nullable Source collectionSource(ExpressionTree owner) {
			TypeMirror type = unit.type(owner);
			if (types.isList(type) || types.isDeque(type)) {
				return listed(body.expression(owner));
			}
			if (owner instanceof MethodInvocationTree view && view.getArguments().isEmpty()
					&& view.getMethodSelect() instanceof MemberSelectTree select
					&& types.isMap(unit.type(select.getExpression()))) {
				Expression map = call(name("pairs"), body.expression(select.getExpression()));
				return switch (select.getIdentifier().toString()) {
					case "keySet" -> new Source("key",
							(item, loop) -> new Statement.GenericFor(List.of(item), List.of(map), loop));
					case "values" -> new Source("value",
							(item, loop) -> new Statement.GenericFor(valueNames(item), List.of(map), loop));
					case "entrySet" -> throw new Rejection(view, "A stream of a map's entries has no Lua form;"
							+ " loop over entrySet() with a for loop, which reads each key and value.");
					default -> null;
				};
			}
			if (types.isSet(type)) {
				Expression set = call(name("pairs"), body.expression(owner));
				return new Source("item", (item, loop) -> new Statement.GenericFor(List.of(item), List.of(set), loop));
			}
			return null;
		}

		private Source listed(Expression list) {
			Expression elements = call(name("ipairs"), list);
			return new Source("item",
					(item, loop) -> new Statement.GenericFor(valueNames(item), List.of(elements), loop));
		}

		/** The variables of a loop that reads only values: {@code _, item}, or {@code _} when nothing reads them. */
		private static List<String> valueNames(String item) {
			return item.equals("_") ? List.of("_") : List.of("_", item);
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
			List<MethodInvocationTree> consumers = new ArrayList<>(stages);
			if (sort == null) {
				consumers.add(pipeline.terminal());
			}
			for (MethodInvocationTree consumer : consumers) {
				String method = method(consumer);
				if (List.of("boxed", "asLongStream", "asDoubleStream", "limit", "skip").contains(method)) {
					continue;
				}
				ExpressionTree function = method.equals("collect") || consumer.getArguments().isEmpty()
						? null
						: BodyLowering.withoutParentheses(consumer.getArguments().getFirst());
				if (function instanceof LambdaExpressionTree lambda && lambda.getParameters().size() == 1) {
					return body.declare(lambda.getParameters().getFirst());
				}
				break;
			}
			if (stages.isEmpty() && sort == null && method(pipeline.terminal()).equals("count")) {
				return "_";
			}
			return body.inventInner(defaultName, pipeline.source());
		}

		// ---- Stages

		private void stage(MethodInvocationTree stage) {
			List<? extends ExpressionTree> arguments = stage.getArguments();
			switch (method(stage)) {
				case "filter" -> steps.add(new Step.Guard(test(arguments.getFirst())));
				case "map", "mapToInt", "mapToLong", "mapToDouble", "mapToObj" ->
						pending = mapped(arguments.getFirst());
				case "boxed", "asLongStream", "asDoubleStream" -> {
					// The same numbers in Lua
				}
				case "limit" -> limit(stage);
				case "skip" -> skip(stage);
				default -> throw unsupported(stage);
			}
		}

		/** {@code limit(n)}: the first n elements that get this far, after which the loop ends. */
		private void limit(MethodInvocationTree stage) {
			Expression limit = limitOf(stage);
			String taken = localBefore("taken", integer(0), stage);
			Expression full = binary(name(taken), BinaryOperator.EQUAL, limit);
			steps.add(new Step.Do(Statement.ifThen(full, Block.of(new Statement.Break()))));
			steps.add(new Step.Do(increment(taken)));
		}

		/** {@code skip(n)}: the elements that get this far after the first n. */
		private void skip(MethodInvocationTree stage) {
			Expression limit = limitOf(stage);
			String dropped = localBefore("dropped", integer(0), stage);
			steps.add(new Step.Do(increment(dropped)));
			steps.add(new Step.Guard(binary(name(dropped), BinaryOperator.GREATER, limit)));
		}

		/**
		 * The count {@code limit} or {@code skip} takes, which the loop reads each time round: one that reading again
		 * gives the same as it is, and any other read once, before the statement, as Java reads it once.
		 */
		private Expression limitOf(MethodInvocationTree stage) {
			ExpressionTree count = stage.getArguments().getFirst();
			return body.reused(body.expression(count), count, "The loop reads this count each time round", "limit");
		}

		/** A local the loop needs, which goes before it, with a name of its own. */
		private String localBefore(String preferred, Expression value, Tree where) {
			String name = body.invent(preferred, where);
			before.add(local(name, value));
			return name;
		}

		private Rejection unsupported(MethodInvocationTree call) {
			return new Rejection(call, SUPPORTED + "; write a for loop for " + method(call) + ".");
		}

		/** A predicate's test of the current element: the lambda's value, or a call of the function. */
		private Expression test(ExpressionTree predicate) {
			if (BodyLowering.withoutParentheses(predicate) instanceof LambdaExpressionTree lambda) {
				return lambdaValue(lambda, "A stream's test becomes an if inside the loop, after the lambda's"
						+ " statements; return the test only at the lambda's end.");
			}
			bindPending("value", predicate);
			return applied(predicate);
		}

		/** A function's value for the current element: the lambda's value, or a call of the function. */
		private Expression mapped(ExpressionTree function) {
			if (BodyLowering.withoutParentheses(function) instanceof LambdaExpressionTree lambda) {
				return lambdaValue(lambda, "A stream's map becomes a value inside the loop, after the lambda's"
						+ " statements; return the value only at the lambda's end.");
			}
			return applied(function);
		}

		/**
		 * A lambda's value for the current element: its expression, or the value a block returns at its end, whose
		 * statements run first, in the loop, as do the statements the value needs; see {@link BodyLowering#valueOf}.
		 *
		 * @param instead the message for a block that returns anywhere but at its end
		 */
		private Expression lambdaValue(LambdaExpressionTree lambda, String instead) {
			bindElement(lambda.getParameters().getFirst());
			Block code = Block.of();
			ExpressionTree value;
			if (lambda.getBody() instanceof BlockTree block) {
				ReturnTree end = Lambdas.returnAtEnd(lambda);
				if (end == null) {
					throw new Rejection(lambda, instead);
				}
				List<? extends StatementTree> statements = block.getStatements();
				code = body.statements(statements.subList(0, statements.size() - 1), unit.start(block) + 1,
						unit.start(end));
				value = Objects.requireNonNull(end.getExpression());
			} else {
				value = (ExpressionTree) lambda.getBody();
			}
			List<Statement> needed = new ArrayList<>();
			Expression lowered = body.valueOf(value, needed);
			Block all = ahead(code, BodyLowering.blockOf(needed));
			if (!all.entries().isEmpty() || !all.closingComments().isEmpty()) {
				steps.add(new Step.Code(all));
			}
			return lowered;
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

		/** A function called on the current element, opened where it is a method reference's small function. */
		private Expression applied(ExpressionTree function) {
			Expression lowered = body.expression(function);
			Expression argument = current();
			Expression opened = Inlining.value(lowered, List.of(argument));
			return opened != null ? opened : called(lowered, argument, function);
		}

		/** A function value called on the argument; a function expression goes into a local before the loop first. */
		private Expression called(Expression function, Expression argument, Tree where) {
			if (function instanceof Expression.FunctionExpression) {
				return new Expression.Call(name(localBefore("action", function, where)), List.of(argument));
			}
			return new Expression.Call(function, List.of(argument));
		}

		// ---- The terminal operation

		/** The terminal's statements: its locals before the loop, its code inside it, and its result after it. */
		private Block terminal(List<Statement> init, List<Statement> after) {
			if (sort != null) {
				return collected("toList", init, after);
			}
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
							? joined(arguments.getFirst(), init, after)
							: collected(collector, init, after);
				}
				case "count" -> {
					String count = accumulator("count");
					init.add(local(count, integer(0)));
					deliver(after, name(count));
					yield Block.of(increment(count));
				}
				case "sum" -> {
					String sum = accumulator("sum");
					boolean floating = types.is(unit.type(receiverOf(terminal)), "java.util.stream.DoubleStream");
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
			Expression lowered = body.expression(action);
			Expression argument = current();
			// A method reference's small function is opened into what it does: a call, or an assignment such as
			// list::add's
			Statement opened = Inlining.statement(lowered, List.of(argument));
			if (opened != null) {
				return Block.of(opened);
			}
			if (Inlining.value(lowered, List.of(argument)) != null) {
				throw new Rejection(action, "In Lua a statement is a call; this method's value would go nowhere.");
			}
			return Block.of(new Statement.CallStatement(called(lowered, argument, action)));
		}

		/**
		 * {@code toList()}, {@code collect(Collectors.toList())} or {@code toSet()}: a table the loop fills, sorted
		 * after it for a loop that ends in a sort.
		 */
		private Block collected(String collector, List<Statement> init, List<Statement> after) {
			String table = accumulator(collector.equals("toSet") ? "set" : "list");
			init.add(local(table, new Expression.Table(List.of())));
			if (sort != null) {
				after.add(sorting(table));
			}
			deliver(after, name(table));
			if (collector.equals("toSet")) {
				return Block.of(Statement.assignment(new Expression.Index(name(table), current()),
						new Expression.BooleanLiteral(true)));
			}
			Expression slot = new Expression.Index(name(table), LibraryCalls.nextIndex(name(table)));
			return Block.of(Statement.assignment(slot, current()));
		}

		/** {@code collect(Collectors.joining(", "))}: the parts in a list, joined with {@code table.concat}. */
		private Block joined(ExpressionTree collector, List<Statement> init, List<Statement> after) {
			MethodInvocationTree joining = (MethodInvocationTree) BodyLowering.withoutParentheses(collector);
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
			Expression slot = new Expression.Index(name(parts), LibraryCalls.nextIndex(name(parts)));
			return Block.of(Statement.assignment(slot, current()));
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
			Expression unset = binary(name(best), BinaryOperator.EQUAL, new Expression.Nil());
			Block replace = Block.of(assign(best, name(element)));
			return Block.of(Statement.ifThen(binary(unset, BinaryOperator.OR, better), replace));
		}

		/** {@code findFirst()}: returned from inside the loop when the stream is returned, otherwise kept. */
		private Block first(List<Statement> init, List<Statement> after) {
			MethodInvocationTree then = then();
			if (destination instanceof Destination.Returned
					&& (then == null || method(then).equals("orElse"))) {
				ExpressionTree fallback = then == null ? null : then.getArguments().getFirst();
				boolean nullFallback = fallback instanceof LiteralTree literal && literal.getValue() == null;
				Expression notFound = fallback == null || nullFallback
						? new Expression.Nil()
						: body.expression(fallback);
				after.add(new Statement.Return(List.of(notFound)));
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

		/**
		 * The local a terminal builds its result in: the destination's own local when the result goes there as it is,
		 * otherwise a new one, which for a list or a set is named after the field it goes to, where that is free; see
		 * {@link BodyLowering#collectionName}.
		 */
		private String accumulator(String preferred) {
			if (destination instanceof Destination.Local local && then() == null) {
				return local.name();
			}
			if (BodyLowering.COLLECTIONS.contains(preferred)) {
				String field = destination instanceof Destination.Sink sink
						? BodyLowering.destinationName(sink.sink())
						: null;
				return body.collectionName(preferred, field, pipeline.terminal(), Set.of());
			}
			return body.invent(preferred, pipeline.terminal());
		}

		/** The Optional method called on the terminal's result, which a loop that ends in a sort has none of. */
		private @Nullable MethodInvocationTree then() {
			return sort == null ? pipeline.then() : null;
		}

		/** Hands the result to the destination after the loop, through the Optional method called on it, if any. */
		private void deliver(List<Statement> after, Expression result) {
			MethodInvocationTree then = then();
			Expression value = result;
			if (then != null) {
				value = body.library().optionalMethod(then, method(then), result, unit.type(receiverOf(then)),
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
					case Step.Do done -> block = LibraryCalls.prepended(done.statement(), block);
					case Step.Code code -> block = ahead(code.code(), block);
					case Step.Guard guard -> {
						Expression condition = guard.condition();
						// filter(a).filter(b) is if a and b
						while (index > 0 && steps.get(index - 1) instanceof Step.Guard earlier) {
							condition = binary(earlier.condition(), BinaryOperator.AND, condition);
							index--;
						}
						block = Block.of(Statement.ifThen(condition, block));
					}
				}
			}
			return block;
		}
	}

	/** A lambda's statements before the code that reads its value, which takes the comments that close them. */
	private static Block ahead(Block code, Block block) {
		List<Block.Entry> entries = new ArrayList<>(code.entries());
		List<Comment> comments = code.closingComments();
		if (block.entries().isEmpty() || comments.isEmpty()) {
			entries.addAll(block.entries());
			List<Comment> closing = new ArrayList<>(comments);
			closing.addAll(block.closingComments());
			return new Block(entries, closing);
		}
		Block.Entry first = block.entries().getFirst();
		Trivia trivia = first.trivia();
		List<Comment> leading = new ArrayList<>(comments);
		leading.addAll(trivia.leading());
		entries.add(new Block.Entry(first.statement(), new Trivia(trivia.blankLineBefore(), leading, trivia.trailing()),
				first.origin()));
		entries.addAll(block.entries().subList(1, block.entries().size()));
		return new Block(entries, block.closingComments());
	}

	private static Statement local(String name, Expression value) {
		return new Statement.Local(List.of(name), List.of(value));
	}

	private static Statement assign(String name, Expression value) {
		return Statement.assignment(name(name), value);
	}

	private static Statement increment(String name) {
		return assign(name, binary(name(name), BinaryOperator.ADD, integer(1)));
	}

	/** The test turned around: {@code a ~= b} for {@code a == b}, otherwise {@code not test}. */
	private static Expression negated(Expression test) {
		Expression inverted = Operators.invertedEquality(test);
		if (inverted != null) {
			return inverted;
		}
		if (test instanceof Expression.Unary unary && unary.operator() == UnaryOperator.NOT) {
			return unary.operand();
		}
		return new Expression.Unary(UnaryOperator.NOT, test);
	}
}
