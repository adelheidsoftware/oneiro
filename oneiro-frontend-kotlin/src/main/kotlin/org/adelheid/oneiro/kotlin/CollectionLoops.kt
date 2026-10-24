package org.adelheid.oneiro.kotlin

import com.intellij.psi.PsiElement
import org.adelheid.oneiro.lowering.Helper
import org.adelheid.oneiro.lowering.Inlining
import org.adelheid.oneiro.lua.BinaryOperator
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.Statement
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtPsiUtil

/**
 * Kotlin's collection functions fused into loops, as the Java front end fuses streams: a chain of calls on a
 * collection, `filter` and `map` steps and then the call that gives the result, becomes one Lua loop, with the result
 * built up in a local.
 *
 * ```kotlin
 * val names = npcs.filter { it.level > 10 }.map { it.name }
 * ```
 *
 * ```lua
 * local names = {}
 * for _, it in ipairs(npcs) do
 *     if it.level > 10 then
 *         names[#names + 1] = it.name
 *     end
 * end
 * ```
 *
 * Kotlin runs each step over the whole collection before the next, and the loop runs every step on one element
 * before the next element, which differs only for lambdas whose side effects depend on that order. A loop is
 * statements, so a chain is the whole value of a local, an assignment or a return, or a `forEach` statement of its
 * own. Each lambda's parameter stands for the loop's current element, so the lambdas' code reads as written.
 */
internal class CollectionLoops(private val body: BodyLowering) {

	private val unit = body.unit
	private val types = body.types

	/** Where a chain's result goes. */
	sealed interface Destination {

		/** Into a new local of this name. */
		class Local(val name: String) : Destination

		/** Returned from the function. */
		data object Returned : Destination

		/** To the sink, which assigns it. */
		class Sink(val sink: (Expression) -> Statement) : Destination

		/** Nowhere: the chain ends in `forEach`. */
		data object Effect : Destination
	}

	/** One call of a chain: the function's name, the call and its arguments, and the whole `receiver.call(...)`. */
	class Operation(
		val name: String,
		val call: KtCallExpression,
		val arguments: LibraryArguments,
		val expression: KtDotQualifiedExpression,
	)

	/**
	 * A chain from its collection to the call that gives its result, and the default `?:` gives when that call finds
	 * nothing, as in `firstOrNull { ... } ?: "none"`.
	 */
	class Chain(
		val source: KtExpression,
		val stages: List<Operation>,
		val terminal: Operation,
		val default: KtExpression?,
	)

	/**
	 * The chain a value is; null for any other value, and for a call that Lua has a form of without a loop, such as
	 * `list.first()`.
	 */
	fun chain(value: KtExpression): Chain? {
		var current = KtPsiUtil.safeDeparenthesize(value)
		var default: KtExpression? = null
		if (current is KtBinaryExpression && current.operationToken == KtTokens.ELVIS) {
			default = current.right ?: return null
			current = current.left?.let(KtPsiUtil::safeDeparenthesize) ?: return null
		}
		val terminal = operation(current) ?: return null
		if (terminal.name !in TERMINALS || default != null && terminal.name !in FINDS) {
			return null
		}
		val stages = mutableListOf<Operation>()
		var source = KtPsiUtil.safeDeparenthesize(terminal.expression.receiverExpression)
		while (true) {
			val stage = operation(source)?.takeIf { it.name in STAGES } ?: break
			stages.add(0, stage)
			source = KtPsiUtil.safeDeparenthesize(stage.expression.receiverExpression)
		}
		val chain = Chain(source, stages, terminal, default)
		return if (stages.isEmpty() && isDirect(chain)) null else chain
	}

	/** The statements that run a chain and hand its result to the destination. */
	fun lower(chain: Chain, destination: Destination): List<Statement> = Fusion(chain, destination).lower()

	/** `receiver.function(...)` for one of Kotlin's functions on collections; null for any other expression. */
	private fun operation(expression: KtExpression?): Operation? = with(unit.session) {
		val qualified = expression as? KtDotQualifiedExpression ?: return null
		val call = qualified.selectorExpression as? KtCallExpression ?: return null
		val resolved = call.resolveToCall()?.successfulFunctionCallOrNull() ?: return null
		val id = resolved.symbol.callableId ?: return null
		if (id.packageName.asString() != "kotlin.collections" || id.classId != null) {
			return null
		}
		Operation(id.callableName.asString(), call, body.libraryArguments(resolved, call), qualified)
	}

	/** Whether a call on a list, set or map, with no steps before it, has a form of its own without a loop. */
	private fun isDirect(chain: Chain): Boolean {
		val terminal = chain.terminal
		val kind = body.library.kind(type(chain.source))
		if (kind == null || body.library.mapView(chain.source) != null) {
			return false
		}
		val takesFunction = argument(terminal) != null
		return when (terminal.name) {
			"forEach" -> true
			"first", "last" -> !takesFunction && kind != LibraryCalls.Kind.MAP
			"firstOrNull", "lastOrNull", "toList", "toMutableList" ->
				!takesFunction && kind == LibraryCalls.Kind.SEQUENCE
			"count", "any", "none" -> !takesFunction
			"joinToString" -> !takesFunction && kind == LibraryCalls.Kind.SEQUENCE &&
				terminal.arguments.byName.keys.all { it == "separator" }
			else -> false
		}
	}

	/** The function a call takes, a lambda or a reference: `joinToString`'s transform, any other's one argument. */
	private fun argument(operation: Operation): KtExpression? {
		val argument = if (operation.name == "joinToString") operation.arguments.byName["transform"]
		else operation.arguments.singleOrNull()
		return argument?.let(KtPsiUtil::safeDeparenthesize)
	}

	private fun type(expression: KtExpression): KaType? = with(unit.session) { expression.expressionType }

	/** The loop over a source, made once the element's name is known, and the name to give it otherwise. */
	private class Source(val defaultName: String, val loop: (String, Block) -> Statement)

	/** One step inside the loop, before the terminal's statements. */
	private sealed interface Step {

		/** `if condition then`, around everything after it. */
		class Guard(val condition: Expression) : Step

		/** `local name = value`, a mapped value that later steps read. */
		class Bind(val name: String, val value: Expression) : Step
	}

	/** The table a chain collects its elements into. */
	private enum class Collected { LIST, SET, ORDERED_SET }

	/** One chain being lowered. */
	private inner class Fusion(private val chain: Chain, private val destination: Destination) {

		// The locals of functions the loop calls, which go before it
		private val functions = mutableListOf<Statement>()
		private val steps = mutableListOf<Step>()
		// The names the loop's variable and its mapped values have taken
		private val used = mutableSetOf<String>()
		private var element = "_"
		// A mapped value no step has read yet, which goes straight into the terminal when that reads it once
		private var pending: Expression? = null

		fun lower(): List<Statement> {
			if (chain.stages.isEmpty() && chain.terminal.name in SORTS && body.isStable(chain.source) &&
				body.library.kind(type(chain.source)) == LibraryCalls.Kind.SEQUENCE) {
				return sortedCopy()
			}
			val source = source(chain.source)
			element = elementName(source.defaultName)
			val loopVariable = element
			used += element
			chain.stages.forEach(::stage)
			val init = mutableListOf<Statement>()
			val after = mutableListOf<Statement>()
			val innermost = terminal(init, after)
			return functions + init + source.loop(loopVariable, nested(innermost)) + after
		}

		// ---- The source

		/** A list's or a set's elements, a range's integers, or a map's keys or values, which walk the map itself. */
		private fun source(source: KtExpression): Source {
			if (operation(source)?.name in SORTS) {
				throw Rejection(source, "A chain's loop sees one element at a time, so it sorts only what it collects;"
					+ " sort at the end of the chain, or sort the list afterwards.")
			}
			CountingRange.of(source)?.let { range ->
				return Source("i") { item, loop -> body.loops.numericFor(item, range) { loop } }
			}
			val view = body.library.mapView(source)
			if (view != null) {
				val map = Expression.call(Expression.name("pairs"), body.expression(view.map))
				return when (view.selector) {
					"keys" -> Source("key") { item, loop -> Statement.GenericFor(listOf(item), listOf(map), loop) }
					"values" -> Source("value") { item, loop ->
						Statement.GenericFor(valueNames(item), listOf(map), loop)
					}
					else -> throw Rejection.notYet(source, ENTRIES)
				}
			}
			return when (body.library.kind(type(source))) {
				LibraryCalls.Kind.SEQUENCE -> {
					val list = Expression.call(Expression.name("ipairs"), body.expression(source))
					Source("item") { item, loop -> Statement.GenericFor(valueNames(item), listOf(list), loop) }
				}
				LibraryCalls.Kind.SET -> {
					val set = Expression.call(Expression.name("pairs"), body.expression(source))
					Source("item") { item, loop -> Statement.GenericFor(listOf(item), listOf(set), loop) }
				}
				LibraryCalls.Kind.MAP -> throw Rejection.notYet(source, ENTRIES)
				null -> throw Rejection(source, "$SUPPORTED; write a for loop for this one.")
			}
		}

		/** The variables of a loop that reads only values: `_, item`, or `_` when nothing reads them. */
		private fun valueNames(item: String): List<String> = if (item == "_") listOf("_") else listOf("_", item)

		/**
		 * The loop variable's name: the parameter of the first lambda that reads the element, so that its code reads
		 * as written; otherwise the source's name for its elements, or `_` when nothing reads them.
		 */
		private fun elementName(defaultName: String): String {
			for (consumer in chain.stages + chain.terminal) {
				val function = argument(consumer)
				if (consumer.name in ELEMENT_LAMBDAS && function is KtLambdaExpression) {
					val parameter = body.lambdas.parameter(function)
					return unit.localName(parameter.declaration, parameter.name)
				}
				if (function != null || consumer.name != "filterNotNull") {
					break
				}
			}
			if (chain.stages.isEmpty() && chain.terminal.name == "count" && argument(chain.terminal) == null) {
				return "_"
			}
			return fresh(defaultName, chain.source)
		}

		// ---- Steps

		private fun stage(stage: Operation) {
			val function = argument(stage)
			when (stage.name) {
				"filter" -> steps += Step.Guard(test(function!!))
				"filterNot" -> steps += Step.Guard(body.operators.negation(test(function!!)))
				"filterNotNull" -> notNil(stage)
				"map" -> pending = mapped(function!!)
				"mapNotNull" -> {
					pending = mapped(function!!)
					notNil(stage)
				}
				else -> throw unsupported(stage)
			}
		}

		private fun unsupported(operation: Operation): Rejection =
			Rejection(operation.call, "$SUPPORTED; write a for loop for ${operation.name}.")

		/** Leaves out nil values: a mapped value is tested, while a collection's own elements are never nil in Lua. */
		private fun notNil(where: Operation) {
			if (pending != null) {
				bindPending("value", where.call)
				steps += Step.Guard(Expression.binary(Expression.name(element), BinaryOperator.NOT_EQUAL,
					Expression.Nil()))
			}
		}

		/** A predicate's test of the current element: the lambda's expression, or a call of the function. */
		private fun test(predicate: KtExpression): Expression {
			if (predicate is KtLambdaExpression) {
				val condition = body.lambdas.value(predicate, "A chain's test becomes an if inside the loop; write it"
					+ " as one expression, { condition }.")
				bindElement(predicate)
				return body.expression(condition)
			}
			bindPending("value", predicate)
			return applied(predicate)
		}

		/** A function's value for the current element: the lambda's expression, or a call of the function. */
		private fun mapped(function: KtExpression): Expression {
			if (function is KtLambdaExpression) {
				val value = body.lambdas.value(function, "A chain's map becomes a value inside the loop; write it as"
					+ " one expression, { value }.")
				bindElement(function)
				return body.expression(value)
			}
			return applied(function)
		}

		/** The current element: a pending mapped value, which this reads once, or the element's name. */
		private fun current(): Expression {
			val value = pending
			pending = null
			return value ?: Expression.name(element)
		}

		/** Gives a pending mapped value a local, so that it can be read more than once. */
		private fun bindPending(preferred: String, where: PsiElement) {
			val value = pending ?: return
			val local = if (preferred in used) fresh(preferred, where) else preferred
			steps += Step.Bind(local, value)
			used += local
			element = local
			pending = null
		}

		/** Makes a lambda's parameter stand for the current element, which a pending value is bound for first. */
		private fun bindElement(lambda: KtLambdaExpression) {
			val parameter = body.lambdas.parameter(lambda)
			bindPending(unit.localName(parameter.declaration, parameter.name), lambda)
			unit.alias(parameter.declaration, element)
		}

		/**
		 * A function called on the current element, opened where it is a reference's small function; a function value
		 * the call cannot open goes into a local before the loop.
		 */
		private fun applied(function: KtExpression): Expression {
			val lowered = body.expression(function)
			if (lowered is Expression.FunctionExpression) {
				bindPending("value", function)
			}
			val argument = current()
			Inlining.value(lowered, listOf(argument))?.let { return it }
			if (lowered is Expression.FunctionExpression) {
				val local = fresh("action", function)
				functions += Statement.Local(listOf(local), listOf(lowered))
				return Expression.Call(Expression.name(local), listOf(argument))
			}
			return Expression.Call(lowered, listOf(argument))
		}

		/** A name of the function's own the loop's variables have not taken. */
		private fun fresh(preferred: String, where: PsiElement): String {
			var name = body.invent(preferred, where)
			var number = 2
			while (name in used) {
				name = preferred + number++
			}
			return name
		}

		// ---- The terminal call

		/** The terminal's statements: its locals before the loop, its code inside it, and its result after it. */
		private fun terminal(init: MutableList<Statement>, after: MutableList<Statement>): Block {
			val terminal = chain.terminal
			if (destination is Destination.Effect && terminal.name != "forEach") {
				throw Rejection(terminal.expression, "The result of this chain is unused; use it, or remove the line.")
			}
			val function = argument(terminal)
			return when (terminal.name) {
				"forEach" -> forEach(function!!)
				in STAGES -> {
					stage(terminal)
					collected(Collected.LIST, init, after)
				}
				"toList", "toMutableList" -> collected(Collected.LIST, init, after)
				// Kotlin's toSet() keeps the order the elements came in
				"toSet", "toMutableSet" -> collected(Collected.ORDERED_SET, init, after)
				"toHashSet" -> collected(Collected.SET, init, after)
				in SORTS -> collected(Collected.LIST, init, after)
				"count" -> counted(function, init, after)
				"sum", "sumOf" -> summed(function, init, after)
				in EXTREMES -> extreme(terminal, function, init, after)
				"first", "firstOrNull", "find" -> first(function, init, after)
				"last", "lastOrNull", "findLast" -> last(function, init, after)
				"any", "all", "none" -> match(terminal.name, function, init, after)
				"joinToString" -> joined(terminal, init, after)
				else -> throw unsupported(terminal)
			}
		}

		private fun forEach(action: KtExpression): Block {
			if (action is KtLambdaExpression) {
				bindElement(action)
				return body.lambdas.inline(action, "forEach").code
			}
			val call = applied(action)
			if (call !is Expression.Call && call !is Expression.MethodCall) {
				throw Rejection(action, "In Lua a statement is a call; this function's value would go nowhere.")
			}
			return Block.of(Statement.CallStatement(call))
		}

		/** `toList()`, `toSet()` or a sort, and the list a chain ending in a step gives: a table the loop fills. */
		private fun collected(kind: Collected, init: MutableList<Statement>, after: MutableList<Statement>): Block {
			val table = accumulator(if (kind == Collected.LIST) "list" else "set")
			val empty = if (kind == Collected.ORDERED_SET) helper(Helper.ORDERED_TABLE) else Expression.Table(listOf())
			init += local(table, empty)
			if (chain.terminal.name in SORTS) {
				after += sort(table)
			}
			deliver(after, Expression.name(table))
			if (kind == Collected.LIST) {
				val slot = Expression.Index(Expression.name(table), LibraryCalls.nextIndex(Expression.name(table)))
				return Block.of(Statement.assignment(slot, current()))
			}
			body.library.checkKey(chain.terminal.expression, types.typeArgumentOf(resultType()!!, 0))
			return Block.of(Statement.assignment(Expression.Index(Expression.name(table), current()),
				Expression.BooleanLiteral(true)))
		}

		/** `list.sorted()` and its kin on a list, with no steps before: a copy of the list, sorted. */
		private fun sortedCopy(): List<Statement> {
			val list = accumulator("list")
			val source = body.expression(chain.source)
			val copy = tableCall("move", source, Expression.integer(1), LibraryCalls.length(source),
				Expression.integer(1), Expression.Table(listOf()))
			val statements = mutableListOf(local(list, copy), sort(list))
			deliver(statements, Expression.name(list))
			return statements
		}

		/** `table.sort(list)`, in the order the terminal sorts by. */
		private fun sort(list: String): Statement {
			val terminal = chain.terminal
			val function = argument(terminal)
			val order = when (terminal.name) {
				"sorted" -> null
				"sortedDescending" -> body.comparators.reversed(terminal.call)
				"sortedBy" -> body.comparators.byKey(function!!, false)
				"sortedByDescending" -> body.comparators.byKey(function!!, true)
				else -> body.comparators.lessThan(function!!)
			}
			return Statement.CallStatement(tableCall("sort", *listOfNotNull(Expression.name(list), order)
				.toTypedArray()))
		}

		/** `count()` or `count { ... }`: the elements that pass, counted. */
		private fun counted(predicate: KtExpression?, init: MutableList<Statement>, after: MutableList<Statement>):
			Block {
			predicate?.let { steps += Step.Guard(test(it)) }
			val count = accumulator("count")
			init += local(count, Expression.integer(0))
			deliver(after, Expression.name(count))
			return Block.of(assign(count, Expression.binary(Expression.name(count), BinaryOperator.ADD,
				Expression.integer(1))))
		}

		/** `sum()` or `sumOf { ... }`: a total from 0, or from 0.0 for decimals. */
		private fun summed(selector: KtExpression?, init: MutableList<Statement>, after: MutableList<Statement>):
			Block {
			selector?.let { pending = mapped(it) }
			val sum = accumulator("sum")
			val floating = resultType()?.let(types::isFloating) == true
			init += local(sum, if (floating) Expression.NumberLiteral("0.0") else Expression.integer(0))
			deliver(after, Expression.name(sum))
			return Block.of(assign(sum, Expression.binary(Expression.name(sum), BinaryOperator.ADD, current())))
		}

		/**
		 * `max()`, `minOf { ... }`, `maxBy { ... }`, `minWith(comparator)` and their kin: the best value so far, nil
		 * until the first, which is also what an empty collection gives, where Kotlin's forms without OrNull throw.
		 */
		private fun extreme(terminal: Operation, function: KtExpression?, init: MutableList<Statement>,
			after: MutableList<Statement>): Block {
			val max = terminal.name.startsWith("max")
			val byKey = terminal.name.startsWith("maxBy") || terminal.name.startsWith("minBy")
			val withComparator = "With" in terminal.name
			if (!byKey && !withComparator && function != null) {
				pending = mapped(function)
			}
			bindPending("value", terminal.call)
			val best = accumulator(if (max) "max" else "min")
			init += Statement.Local(listOf(best), listOf())
			val (first, second) = if (max) best to element else element to best
			val better = when {
				byKey -> body.comparators.keyBefore(function!!, first, second)
				withComparator -> body.comparators.comesBefore(function, first, second)
				else -> Expression.binary(Expression.name(first), BinaryOperator.LESS, Expression.name(second))
			}
			deliver(after, Expression.name(best))
			val unset = Expression.binary(Expression.name(best), BinaryOperator.EQUAL, Expression.Nil())
			return Block.of(Statement.ifThen(Expression.binary(unset, BinaryOperator.OR, better),
				Block.of(assign(best, Expression.name(element)))))
		}

		/**
		 * `first { ... }`, `firstOrNull()` and `find { ... }`: returned from inside the loop when the chain is
		 * returned, otherwise kept. Nothing found is nil, where Kotlin's `first` throws.
		 */
		private fun first(predicate: KtExpression?, init: MutableList<Statement>, after: MutableList<Statement>):
			Block {
			predicate?.let { steps += Step.Guard(test(it)) }
			if (destination is Destination.Returned) {
				val default = chain.default
				after += when {
					default == null -> listOf(Statement.Return(listOf(Expression.Nil())))
					Branches.jumps(default) -> body.statement(default)
					else -> listOf(Statement.Return(listOf(body.expression(default))))
				}
				return Block.of(Statement.Return(listOf(current())))
			}
			val found = accumulator("first")
			init += Statement.Local(listOf(found), listOf())
			deliver(after, Expression.name(found))
			return Block.of(assign(found, current()), Statement.Break())
		}

		/** `last { ... }`, `lastOrNull()` and `findLast { ... }`: each element that passes, kept until the end. */
		private fun last(predicate: KtExpression?, init: MutableList<Statement>, after: MutableList<Statement>):
			Block {
			predicate?.let { steps += Step.Guard(test(it)) }
			val found = accumulator("last")
			init += Statement.Local(listOf(found), listOf())
			deliver(after, Expression.name(found))
			return Block.of(assign(found, current()))
		}

		/**
		 * `any`, `all` or `none`: a test that ends the loop once it decides, returning the answer from inside the loop
		 * when the chain is returned.
		 */
		private fun match(name: String, predicate: KtExpression?, init: MutableList<Statement>,
			after: MutableList<Statement>): Block {
			val test = predicate?.let(::test)
			if (test != null) {
				steps += Step.Guard(if (name == "all") body.operators.negation(test) else test)
			}
			val found = name == "any"
			if (destination is Destination.Returned) {
				after += Statement.Return(listOf(Expression.BooleanLiteral(!found)))
				return Block.of(Statement.Return(listOf(Expression.BooleanLiteral(found))))
			}
			val answer = accumulator(name)
			init += local(answer, Expression.BooleanLiteral(!found))
			deliver(after, Expression.name(answer))
			return Block.of(assign(answer, Expression.BooleanLiteral(found)), Statement.Break())
		}

		/**
		 * `joinToString(", ") { ... }`: the parts in a list, joined with `table.concat`, between the prefix and postfix
		 * when the call gives them. Without a transform the elements must be strings or numbers, which `table.concat`
		 * writes as Kotlin does.
		 */
		private fun joined(terminal: Operation, init: MutableList<Statement>, after: MutableList<Statement>): Block {
			val arguments = terminal.arguments.byName
			if (arguments.keys.any { it !in JOIN_PARAMETERS }) {
				throw Rejection.notYet(terminal.call, "joinToString with a limit")
			}
			val transform = argument(terminal)
			if (transform != null) {
				pending = text(transform)
			} else if (!joinable(terminal)) {
				throw Rejection.notYet(terminal.call, "Joining values other than strings and numbers without a"
					+ " transform")
			}
			val parts = fresh("parts", terminal.call)
			init += local(parts, Expression.Table(listOf()))
			val separator = arguments["separator"]?.let(body::expression) ?: Expression.string(", ")
			var joinedParts = tableCall("concat", Expression.name(parts), separator)
			arguments["postfix"]?.let {
				joinedParts = Expression.binary(joinedParts, BinaryOperator.CONCAT, body.expression(it))
			}
			arguments["prefix"]?.let {
				joinedParts = Expression.binary(body.expression(it), BinaryOperator.CONCAT, joinedParts)
			}
			deliver(after, joinedParts)
			val slot = Expression.Index(Expression.name(parts), LibraryCalls.nextIndex(Expression.name(parts)))
			return Block.of(Statement.assignment(slot, current()))
		}

		/** A transform's text for the current element, as `table.concat` takes it. */
		private fun text(transform: KtExpression): Expression {
			if (transform is KtLambdaExpression) {
				val value = body.lambdas.value(transform, "A chain's transform becomes a value inside the loop; write"
					+ " it as one expression, { text }.")
				bindElement(transform)
				return body.strings.asText(value)
			}
			return applied(transform)
		}

		/** Whether the elements joined are strings or numbers, which a range's integers are. */
		private fun joinable(terminal: Operation): Boolean = with(unit.session) {
			if (chain.stages.isEmpty() && CountingRange.of(chain.source) != null) {
				return true
			}
			val receiver = type(terminal.expression.receiverExpression) ?: return false
			val element = types.elementClass(receiver) ?: types.typeArgumentOf(receiver, 0)?.expandedSymbol?.classId
			element in LibraryCalls.JOINABLE
		}

		/**
		 * The local a terminal builds its result in: the destination's own local when the result goes there as it is,
		 * otherwise a new one.
		 */
		private fun accumulator(preferred: String): String {
			val default = chain.default
			if (destination is Destination.Local && (default == null || Branches.jumps(default))) {
				return destination.name
			}
			return fresh(preferred, chain.terminal.call)
		}

		/** The type of the terminal call's value. */
		private fun resultType(): KaType? = type(chain.terminal.expression)

		/**
		 * Hands the result to the destination after the loop: or the default `?:` gives, or when that leaves, after
		 * leaving where the result is nil.
		 */
		private fun deliver(after: MutableList<Statement>, result: Expression) {
			val default = chain.default
			var value = result
			if (default != null && Branches.jumps(default)) {
				val absent = Expression.binary(result, BinaryOperator.EQUAL, Expression.Nil())
				after += Statement.ifThen(absent, Block(body.statement(default).map(Block.Entry::of), listOf()))
			} else if (default != null) {
				value = body.operators.orElse(result, resultType(), default)
			}
			when (destination) {
				is Destination.Local -> if ((value as? Expression.Name)?.name() != destination.name) {
					after += local(destination.name, value)
				}
				Destination.Returned -> after += Statement.Return(listOf(value))
				is Destination.Sink -> after += destination.sink(value)
				Destination.Effect -> {}
			}
		}

		// ---- The loop's body

		/** The steps around the terminal's code: each guard an if around what follows, consecutive guards one if. */
		private fun nested(innermost: Block): Block {
			var block = innermost
			var index = steps.lastIndex
			while (index >= 0) {
				when (val step = steps[index]) {
					is Step.Bind -> {
						val bound = Block.Entry.of(local(step.name, step.value))
						block = Block(listOf(bound) + block.entries(), block.closingComments())
					}
					is Step.Guard -> {
						var condition = step.condition
						// filter { a }.filter { b } is if a and b
						while (index > 0 && steps[index - 1] is Step.Guard) {
							index--
							condition = Expression.binary((steps[index] as Step.Guard).condition, BinaryOperator.AND,
								condition)
						}
						block = Block.of(Statement.ifThen(condition, block))
					}
				}
				index--
			}
			return block
		}
	}

	private fun local(name: String, value: Expression): Statement = Statement.Local(listOf(name), listOf(value))

	private fun assign(name: String, value: Expression): Statement = Statement.assignment(Expression.name(name), value)

	private fun tableCall(name: String, vararg arguments: Expression): Expression {
		unit.useGlobal("table")
		return Expression.Call(Expression.field(Expression.name("table"), name), arguments.toList())
	}

	private fun helper(helper: Helper, vararg arguments: Expression): Expression {
		unit.use(helper)
		return helper.invoke(*arguments)
	}

	companion object {

		private const val SUPPORTED = "Oneiro makes one loop of a chain of collection functions: a list, set, array, " +
			"range or a map's keys or values, then filter, filterNot, filterNotNull, map and mapNotNull, then " +
			"forEach, toList, toSet, a sort, count, sum, sumOf, min, max and their By, Of and With forms, first, " +
			"last, find, any, all, none or joinToString"

		private const val ENTRIES = "A chain over a map's entries"

		/** The steps a loop runs on each element before the terminal call. */
		private val STAGES = setOf("filter", "filterNot", "filterNotNull", "map", "mapNotNull")

		/** The sorts that collect a sorted list. */
		private val SORTS = setOf("sorted", "sortedDescending", "sortedBy", "sortedByDescending", "sortedWith")

		/** The calls that keep the smallest or biggest value. */
		private val EXTREMES = setOf("max", "min", "maxOrNull", "minOrNull", "maxOf", "minOf", "maxOfOrNull",
			"minOfOrNull", "maxBy", "minBy", "maxByOrNull", "minByOrNull", "maxWith", "minWith", "maxWithOrNull",
			"minWithOrNull")

		/** The calls that find one element or value, or nothing, which `?:` gives a default for. */
		private val FINDS = setOf("first", "firstOrNull", "find", "last", "lastOrNull", "findLast") + EXTREMES

		/** The calls a chain ends in. */
		private val TERMINALS = STAGES + SORTS + FINDS + setOf("forEach", "toList", "toMutableList", "toSet",
			"toMutableSet", "toHashSet", "count", "sum", "sumOf", "any", "all", "none", "joinToString")

		/** Every function a chain is made of, which [LibraryCalls] refuses outside a chain's place, with the reason. */
		val FUNCTIONS = TERMINALS

		/** The calls whose lambda's parameter is the element, which can name the loop's variable. */
		private val ELEMENT_LAMBDAS = STAGES + setOf("forEach", "count", "sumOf", "maxOf", "minOf", "maxOfOrNull",
			"minOfOrNull", "maxBy", "minBy", "maxByOrNull", "minByOrNull", "first", "firstOrNull", "find", "last",
			"lastOrNull", "findLast", "any", "all", "none", "joinToString")

		/** The parameters of `joinToString` a chain's join takes. */
		private val JOIN_PARAMETERS = setOf("separator", "prefix", "postfix", "transform")
	}
}
