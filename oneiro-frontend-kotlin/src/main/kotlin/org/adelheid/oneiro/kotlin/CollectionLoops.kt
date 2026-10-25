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
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtQualifiedExpression

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
	 * A chain from its collection to the call that gives its result, the default `?:` gives when that call finds
	 * nothing, as in `firstOrNull { ... } ?: "none"`, and the part of a map's entry it gives, `key` or `value`, when it
	 * finds an entry, as in `maxByOrNull { it.value }?.key`.
	 */
	class Chain(
		val source: KtExpression,
		val stages: List<Operation>,
		val terminal: Operation,
		val default: KtExpression?,
		val part: String?,
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
		val part = entryPart(current)
		if (part != null) {
			current = KtPsiUtil.safeDeparenthesize((current as KtQualifiedExpression).receiverExpression)
		}
		val terminal = operation(current) ?: return null
		if (terminal.name !in TERMINALS || (default != null || part != null) && terminal.name !in FINDS) {
			return null
		}
		val stages = mutableListOf<Operation>()
		var source = KtPsiUtil.safeDeparenthesize(terminal.expression.receiverExpression)
		while (true) {
			val stage = operation(source)?.takeIf { it.name in STAGES } ?: break
			stages.add(0, stage)
			source = KtPsiUtil.safeDeparenthesize(stage.expression.receiverExpression)
		}
		val chain = Chain(source, stages, terminal, default, part)
		return if (stages.isEmpty() && isDirect(chain)) null else chain
	}

	/** `key` or `value` for `found.key` or `found?.value` of a map's entry, or null for any other expression. */
	private fun entryPart(expression: KtExpression): String? = with(unit.session) {
		val qualified = expression as? KtQualifiedExpression ?: return null
		val part = (qualified.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
		val entry = qualified.receiverExpression.expressionType?.expandedSymbol?.classId
		val isEntry = entry == StandardClassIds.MapEntry || entry == StandardClassIds.MutableMapEntry
		if (isEntry && part in setOf("key", "value")) part else null
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

	/**
	 * The function a call takes, a lambda or a reference: `joinToString`'s transform, `groupBy`'s key selector, none
	 * for `take` and `drop`, which take a count, and any other's one argument.
	 */
	private fun argument(operation: Operation): KtExpression? {
		val argument = when (operation.name) {
			"joinToString" -> operation.arguments.byName["transform"]
			"groupBy" -> operation.arguments.byName["keySelector"]
			"take", "drop" -> null
			else -> operation.arguments.singleOrNull()
		}
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

		/** A statement for each element that gets this far, such as counting it. */
		class Do(val statement: Statement) : Step

		/** A loop around everything after it, over the collection `flatMap` gives for each element. */
		class Loop(val loop: (Block) -> Statement) : Step
	}

	/** The table a chain collects its elements into. */
	private enum class Collected { LIST, SET, ORDERED_SET }

	/** One chain being lowered. */
	private inner class Fusion(private val chain: Chain, private val destination: Destination) {

		// The locals the loop needs, which go before it: functions it calls, and what distinct, take and drop keep
		private val before = mutableListOf<Statement>()
		private val steps = mutableListOf<Step>()
		// The names the loop's variable and its mapped values have taken
		private val used = mutableSetOf<String>()
		private var element = "_"
		// A mapped value no step has read yet, which goes straight into the terminal when that reads it once
		private var pending: Expression? = null
		// The loops inside the source's, one for each flatMap, which leaving the chain early must leave too
		private var innerLoops = 0
		// The label after the loops that leaving them all jumps to, once something does
		private var exit: String? = null
		// Over a map's entries, the loop's names for the key and the value; while the element is an entry, element is
		// the key's name
		private var entryKey = "_"
		private var entryValue = "_"
		private var isEntry = false

		fun lower(): List<Statement> {
			if (chain.stages.isEmpty() && chain.terminal.name in SORTS && body.isStable(chain.source) &&
				body.library.kind(type(chain.source)) == LibraryCalls.Kind.SEQUENCE) {
				return sortedCopy()
			}
			val entries = entryMap(chain.source)
			val source = if (entries != null) entrySource(entries) else source(chain.source)
			if (entries == null) {
				element = elementName(0, source.defaultName)
			}
			val loopVariable = element
			used += element
			chain.stages.forEach(::stage)
			val init = mutableListOf<Statement>()
			val after = mutableListOf<Statement>()
			val innermost = terminal(init, after)
			val exitLabel = listOfNotNull(exit?.let { Statement.Label(it) })
			return before + init + source.loop(loopVariable, nested(innermost)) + exitLabel + after
		}

		/** Leaves the chain's loops: `break`, or with loops inside the source's, a `goto` to the label after them. */
		private fun stop(): Statement {
			if (innerLoops == 0) {
				return Statement.Break()
			}
			val label = exit ?: unit.exitLabel().also { exit = it }
			return Statement.Goto(label)
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
					else -> throw IllegalStateException("a map's ${view.selector}, which entrySource walks")
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
				LibraryCalls.Kind.MAP -> throw IllegalStateException("a map, whose entries entrySource walks")
				null -> throw Rejection(source, "$SUPPORTED; write a for loop for this one.")
			}
		}

		/** The map whose entries a chain walks: a map itself, or a map's `entries`; null for any other source. */
		private fun entryMap(source: KtExpression): KtExpression? {
			val view = body.library.mapView(source)
			if (view != null) {
				return if (view.selector == "entries") view.map else null
			}
			return if (body.library.kind(type(source)) == LibraryCalls.Kind.MAP) source else null
		}

		/** A map's entries, which the loop walks as its keys and values: `for key, value in pairs(map) do`. */
		private fun entrySource(map: KtExpression): Source {
			val (key, value) = entryNames()
			entryKey = key
			entryValue = value
			isEntry = true
			element = key
			used += value
			val entries = Expression.call(Expression.name("pairs"), body.expression(map))
			return Source(key) { _, loop ->
				Statement.GenericFor(LibraryCalls.entryVariables(key, value, loop), listOf(entries), loop)
			}
		}

		/**
		 * The names of the loop's key and value: the first a lambda destructures the entry into, other than `_`, so
		 * that the lambdas' code reads as written; otherwise key and value.
		 */
		private fun entryNames(): Pair<String, String> {
			var key: String? = null
			var value: String? = null
			for (consumer in chain.stages + chain.terminal) {
				val lambda = argument(consumer) as? KtLambdaExpression
				val parts = lambda?.functionLiteral?.valueParameters?.singleOrNull()?.destructuringDeclaration?.entries
				if (consumer.name in ENTRY_LAMBDAS && parts != null && parts.size == 2) {
					key = key ?: named(parts[0])
					value = value ?: named(parts[1])
				}
				// After a map, the lambdas take values rather than entries
				if (consumer.name in MAKES_VALUES) {
					break
				}
			}
			val where = chain.terminal.call
			return (key ?: fresh("key", where)) to (value ?: fresh("value", where))
		}

		/** The Lua name of a destructured part of an entry, or null for `_`, which names nothing. */
		private fun named(part: KtDestructuringDeclarationEntry): String? =
			part.name?.takeIf { it != "_" }?.let { unit.localName(part, it) }

		/** The variables of a loop that reads only values: `_, item`, or `_` when nothing reads them. */
		private fun valueNames(item: String): List<String> = if (item == "_") listOf("_") else listOf("_", item)

		/**
		 * The name of a loop's variable, for the calls from [start] on: the parameter of the first lambda that reads
		 * the element, so that its code reads as written; otherwise the default name for its elements, or `_` when
		 * nothing reads them.
		 */
		private fun elementName(start: Int, defaultName: String): String {
			val consumers = (chain.stages + chain.terminal).drop(start)
			for (consumer in consumers) {
				val function = argument(consumer)
				if (consumer.name in ELEMENT_LAMBDAS && function is KtLambdaExpression) {
					val parameter = body.lambdas.parameter(function)
					return unit.localName(parameter.declaration, parameter.name)
				}
				if (function != null || consumer.name !in KEEPS_ELEMENT) {
					break
				}
			}
			if (consumers.size == 1 && chain.terminal.name == "count" && argument(chain.terminal) == null) {
				return "_"
			}
			return fresh(defaultName, consumers.firstOrNull()?.call ?: chain.terminal.call)
		}

		// ---- Steps

		private fun stage(stage: Operation) {
			val function = argument(stage)
			when (stage.name) {
				"filter" -> steps += Step.Guard(test(function!!))
				"filterNot" -> steps += Step.Guard(body.operators.negation(test(function!!)))
				"filterKeys" -> steps += Step.Guard(partTest(function!!, entryKey))
				"filterValues" -> steps += Step.Guard(partTest(function!!, entryValue))
				"filterNotNull" -> notNil(stage)
				"map" -> {
					pending = mapped(function!!)
					isEntry = false
				}
				"mapNotNull" -> {
					pending = mapped(function!!)
					isEntry = false
					notNil(stage)
				}
				"distinct" -> {
					if (isEntry) {
						throw Rejection(stage.call, ENTRY_PARTS)
					}
					distinct(stage, null)
				}
				"distinctBy" -> distinct(stage, function!!)
				"take" -> take(stage)
				"drop" -> drop(stage)
				"takeWhile" -> steps += Step.Do(Statement.ifThen(body.operators.negation(test(function!!)),
					Block.of(stop())))
				"dropWhile" -> dropWhile(stage, function!!)
				"flatMap" -> flatten(stage, function!!)
				else -> throw unsupported(stage)
			}
		}

		/**
		 * `distinct()` and `distinctBy { ... }`: each element whose value, or key, no element before it had, which a
		 * table of those seen keeps.
		 */
		private fun distinct(stage: Operation, selector: KtExpression?) {
			val key = if (selector == null) {
				bindPending("value", stage.call)
				body.library.checkKey(stage.call, type(stage.expression)?.let { types.typeArgumentOf(it, 0) })
				element
			} else {
				selected(selector, "key", stage.call)
			}
			val seen = localBefore("seen", Expression.Table(listOf()), stage.call)
			val slot = Expression.Index(Expression.name(seen), Expression.name(key))
			steps += Step.Guard(Expression.binary(slot, BinaryOperator.EQUAL, Expression.Nil()))
			steps += Step.Do(Statement.assignment(slot, Expression.BooleanLiteral(true)))
		}

		/** `take(n)`: the first n elements that get this far, after which the loop ends. */
		private fun take(stage: Operation) {
			val limit = limit(stage)
			val taken = localBefore("taken", Expression.integer(0), stage.call)
			val full = Expression.binary(Expression.name(taken), BinaryOperator.EQUAL, limit)
			steps += Step.Do(Statement.ifThen(full, Block.of(stop())))
			steps += Step.Do(increment(taken))
		}

		/** `drop(n)`: the elements that get this far after the first n. */
		private fun drop(stage: Operation) {
			val limit = limit(stage)
			val dropped = localBefore("dropped", Expression.integer(0), stage.call)
			steps += Step.Do(increment(dropped))
			steps += Step.Guard(Expression.binary(Expression.name(dropped), BinaryOperator.GREATER, limit))
		}

		/** `dropWhile { ... }`: the elements from the first that fails the test on. */
		private fun dropWhile(stage: Operation, predicate: KtExpression) {
			val dropping = localBefore("dropping", Expression.BooleanLiteral(true), stage.call)
			// Once one element fails the test, and stops the dropping, the test is no longer run
			val stillDropping = Expression.binary(Expression.name(dropping), BinaryOperator.AND, test(predicate))
			steps += Step.Do(assign(dropping, stillDropping))
			steps += Step.Guard(body.operators.negation(Expression.name(dropping)))
		}

		/**
		 * `take`'s and `drop`'s count: a literal or a name as it is, anything else read once into a local before the
		 * loop, as Kotlin reads it once.
		 */
		private fun limit(stage: Operation): Expression {
			val count = KtPsiUtil.safeDeparenthesize(stage.arguments[0])
			val value = body.expression(count)
			if (count is KtConstantExpression || body.isStable(count)) {
				return value
			}
			return Expression.name(localBefore("limit", value, stage.call))
		}

		/**
		 * `flatMap { ... }`: a loop over the collection the lambda gives for each element, inside which that
		 * collection's elements go through the rest of the chain.
		 */
		private fun flatten(stage: Operation, function: KtExpression) {
			val lambda = function as? KtLambdaExpression
				?: throw Rejection.notYet(function, "flatMap with a function reference")
			val collection = body.lambdas.value(lambda, "A chain's flatMap becomes a loop inside the loop; write the"
				+ " collection as one expression, { it.items }.")
			bindElement(lambda)
			val name = elementName((chain.stages + chain.terminal).indexOf(stage) + 1, "item")
			steps += Step.Loop(innerLoop(collection, name))
			innerLoops++
			element = name
			isEntry = false
			used += name
		}

		/** `filterKeys { ... }` and `filterValues { ... }`: a test of the entry's key or value. */
		private fun partTest(predicate: KtExpression, part: String): Expression {
			if (predicate !is KtLambdaExpression) {
				return applied(predicate, Expression.name(part))
			}
			val condition = body.lambdas.value(predicate, "A chain's test becomes an if inside the loop; write it as"
				+ " one expression, { condition }.")
			unit.alias(body.lambdas.parameter(predicate).declaration, part)
			return body.expression(condition)
		}

		/** The loop over one element's collection, a list's, an array's, a set's or a range's elements. */
		private fun innerLoop(collection: KtExpression, item: String): (Block) -> Statement {
			CountingRange.of(collection)?.let { range ->
				return { loop -> body.loops.numericFor(item, range) { loop } }
			}
			val lowered = body.expression(collection)
			return when (body.library.kind(type(collection))) {
				LibraryCalls.Kind.SEQUENCE -> { loop ->
					Statement.GenericFor(valueNames(item), listOf(Expression.call(Expression.name("ipairs"), lowered)),
						loop)
				}
				LibraryCalls.Kind.SET -> { loop ->
					Statement.GenericFor(listOf(item), listOf(Expression.call(Expression.name("pairs"), lowered)), loop)
				}
				else -> throw Rejection(collection, "flatMap's lambda gives the collection the loop inside the loop"
					+ " walks; give it a list, set, array or range.")
			}
		}

		/**
		 * A selector's value for the current element, in a local of its own that later code reads, `local key =
		 * it.item`; a key's must be one Lua tables find.
		 */
		private fun selected(selector: KtExpression, preferred: String, where: PsiElement): String {
			val value = if (selector is KtLambdaExpression) {
				val key = body.lambdas.value(selector, "A chain's selector becomes a value inside the loop; write it as"
					+ " one expression, { key }.")
				bindElement(selector)
				body.library.checkKey(key, type(key))
				body.expression(key)
			} else {
				bindPending("value", selector)
				applied(selector)
			}
			val name = fresh(preferred, where)
			used += name
			steps += Step.Bind(name, value)
			return name
		}

		/** A local before the loop, under a name of its own. */
		private fun localBefore(preferred: String, value: Expression, where: PsiElement): String {
			val name = fresh(preferred, where)
			used += name
			before += local(name, value)
			return name
		}

		private fun increment(name: String): Statement =
			assign(name, Expression.binary(Expression.name(name), BinaryOperator.ADD, Expression.integer(1)))

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

		/**
		 * The current element: a pending mapped value, which this reads once, or the element's name. A map's entry is
		 * no one value Lua could take.
		 */
		private fun current(): Expression {
			val value = pending
			if (value == null && isEntry) {
				throw Rejection(chain.terminal.call, ENTRY_PARTS)
			}
			pending = null
			return value ?: Expression.name(element)
		}

		/**
		 * What a find keeps of the current element: the element, or of a map's entry the part the chain reads, `?.key`
		 * or `?.value`.
		 */
		private fun given(): Expression {
			if (!isEntry || pending != null) {
				return current()
			}
			val part = chain.part ?: throw Rejection(chain.terminal.call, ENTRY_PARTS)
			return Expression.name(if (part == "key") entryKey else entryValue)
		}

		/** Gives a pending mapped value a local, so that it can be read more than once. */
		private fun bindPending(preferred: String, where: PsiElement) {
			val value = pending ?: return
			val local = if (preferred in used) fresh(preferred, where) else preferred
			steps += Step.Bind(local, value)
			used += local
			element = local
			isEntry = false
			pending = null
		}

		/** Makes a lambda's parameter stand for the current element, which a pending value is bound for first. */
		private fun bindElement(lambda: KtLambdaExpression) {
			if (isEntry) {
				bindEntry(lambda)
				return
			}
			val parameter = body.lambdas.parameter(lambda)
			bindPending(unit.localName(parameter.declaration, parameter.name), lambda)
			unit.alias(parameter.declaration, element)
		}

		/**
		 * Makes a lambda's parameter that is a map's entry stand for the loop's key and value: the names it
		 * destructures the entry into, or `entry.key` and `entry.value`.
		 */
		private fun bindEntry(lambda: KtLambdaExpression) {
			val literal = lambda.functionLiteral
			val parameter = literal.valueParameters.singleOrNull()
			val parts = parameter?.destructuringDeclaration?.entries
			if (parts == null) {
				unit.aliasEntry(parameter ?: literal, entryKey, entryValue)
				return
			}
			parts.getOrNull(0)?.let { unit.alias(it, entryKey) }
			parts.getOrNull(1)?.let { unit.alias(it, entryValue) }
		}

		/**
		 * A function called on the current element, or on the argument given, opened where it is a reference's small
		 * function; a function value the call cannot open goes into a local before the loop.
		 */
		private fun applied(function: KtExpression, argument: Expression? = null): Expression {
			val lowered = body.expression(function)
			if (lowered is Expression.FunctionExpression && argument == null) {
				bindPending("value", function)
			}
			val value = argument ?: current()
			Inlining.value(lowered, listOf(value))?.let { return it }
			if (lowered is Expression.FunctionExpression) {
				val local = fresh("action", function)
				before += Statement.Local(listOf(local), listOf(lowered))
				return Expression.Call(Expression.name(local), listOf(value))
			}
			return Expression.Call(lowered, listOf(value))
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
					// A map's filter gives a map, as Kotlin's does
					if (isEntry && resultType()?.let(types::isMap) == true) {
						stored(Expression.name(entryKey), Expression.name(entryValue), init, after)
					} else {
						collected(Collected.LIST, init, after)
					}
				}
				"mapValues", "mapKeys" -> {
					if (!isEntry) {
						throw unsupported(terminal)
					}
					val key = Expression.name(entryKey)
					val value = Expression.name(entryValue)
					val made = mapped(function!!)
					if (terminal.name == "mapValues") {
						stored(key, made, init, after)
					} else {
						stored(made, value, init, after)
					}
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
				"groupBy" -> grouped(terminal, function!!, init, after)
				"associate" -> associated(terminal, function!!, init, after)
				"associateBy" -> {
					// The element is stored as the value, so it needs a name of its own first
					keepElement(function!!)
					stored(mapped(function), Expression.name(element), init, after)
				}
				"associateWith" -> {
					keepElement(function!!)
					// The lambda names the element as it binds it, so the value comes first
					val value = mapped(function)
					stored(Expression.name(element), value, init, after)
				}
				else -> throw unsupported(terminal)
			}
		}

		/** Gives the current element a name before a function reference reads it; a lambda's parameter gives one. */
		private fun keepElement(function: KtExpression) {
			if (function !is KtLambdaExpression) {
				bindPending("value", function)
			}
		}

		/**
		 * `groupBy { ... }`: a map from each key to the list of the elements with that key, or of what the second
		 * function makes of them, which keeps its keys in the order they came, as Kotlin's does.
		 */
		private fun grouped(terminal: Operation, keySelector: KtExpression, init: MutableList<Statement>,
			after: MutableList<Statement>): Block {
			body.library.checkKey(terminal.call, keyType())
			val key = selected(keySelector, "key", terminal.call)
			val value = terminal.arguments.byName["valueTransform"]?.let { mapped(KtPsiUtil.safeDeparenthesize(it)) }
				?: current()
			val groups = accumulator("groups")
			init += local(groups, helper(Helper.ORDERED_TABLE))
			deliver(after, Expression.name(groups))
			val group = fresh("group", terminal.call)
			used += group
			val slot = Expression.Index(Expression.name(groups), Expression.name(key))
			val absent = Expression.binary(Expression.name(group), BinaryOperator.EQUAL, Expression.Nil())
			val next = Expression.Index(Expression.name(group), LibraryCalls.nextIndex(Expression.name(group)))
			return Block.of(
				local(group, slot),
				Statement.ifThen(absent, Block.of(assign(group, Expression.Table(listOf())),
					Statement.assignment(slot, Expression.name(group)))),
				Statement.assignment(next, value))
		}

		/** `associate { it.name to it.level }`: a map of the pairs, a later pair's value replacing an earlier's. */
		private fun associated(terminal: Operation, function: KtExpression, init: MutableList<Statement>,
			after: MutableList<Statement>): Block {
			val lambda = function as? KtLambdaExpression
				?: throw Rejection.notYet(function, "associate with a function reference")
			val pair = body.lambdas.value(lambda, "A chain's associate becomes an assignment inside the loop; write the"
				+ " pair as one expression, { key to value }.")
			bindElement(lambda)
			val (key, value) = pairOf(pair)
				?: throw Rejection.notYet(pair, "associate with a pair that is not written key to value")
			return stored(body.expression(key), body.expression(value), init, after)
		}

		/**
		 * The key and the value of a pair written `key to value`; null for a pair written any other way.
		 */
		private fun pairOf(pair: KtExpression): Pair<KtExpression, KtExpression>? = with(unit.session) {
			val binary = KtPsiUtil.safeDeparenthesize(pair) as? KtBinaryExpression ?: return null
			val id = binary.operationReference.resolveToCall()?.successfulFunctionCallOrNull()?.symbol?.callableId
			if (id?.packageName?.asString() != "kotlin" || id.callableName.asString() != "to") {
				return null
			}
			(binary.left ?: return null) to (binary.right ?: return null)
		}

		/**
		 * The map `associate` and its kin give, which keeps its keys in the order they came, as Kotlin's does: each
		 * element stores its value under its key.
		 */
		private fun stored(key: Expression, value: Expression, init: MutableList<Statement>,
			after: MutableList<Statement>): Block {
			body.library.checkKey(chain.terminal.call, keyType())
			val map = accumulator("map")
			init += local(map, helper(Helper.ORDERED_TABLE))
			deliver(after, Expression.name(map))
			return Block.of(Statement.assignment(Expression.Index(Expression.name(map), key), value))
		}

		/** The type of the keys of the map a terminal gives. */
		private fun keyType(): KaType? = resultType()?.let { types.typeArgumentOf(it, 0) }

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
			if (isEntry && withComparator) {
				throw Rejection.notYet(terminal.call, "maxWith and minWith over a map's entries")
			}
			if (isEntry && byKey) {
				return entryExtreme(terminal, function!!, max, init, after)
			}
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
		 * `maxBy { ... }` and its kin over a map's entries, which give a part of the best entry, `?.key` or `?.value`:
		 * that part, and the best entry's selected value, which the next entries compare with.
		 */
		private fun entryExtreme(terminal: Operation, selector: KtExpression, max: Boolean,
			init: MutableList<Statement>, after: MutableList<Statement>): Block {
			val part = given()
			var selected = mapped(selector)
			if (selected !is Expression.Name) {
				val local = fresh("selected", terminal.call)
				used += local
				steps += Step.Bind(local, selected)
				selected = Expression.name(local)
			}
			val best = accumulator("best")
			val record = fresh(if (max) "most" else "least", terminal.call)
			used += record
			init += Statement.Local(listOf(best), listOf())
			init += Statement.Local(listOf(record), listOf())
			val unset = Expression.binary(Expression.name(record), BinaryOperator.EQUAL, Expression.Nil())
			val better = if (max) Expression.binary(Expression.name(record), BinaryOperator.LESS, selected)
			else Expression.binary(selected, BinaryOperator.LESS, Expression.name(record))
			deliver(after, Expression.name(best))
			return Block.of(Statement.ifThen(Expression.binary(unset, BinaryOperator.OR, better),
				Block.of(assign(record, selected), assign(best, part))))
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
				return Block.of(Statement.Return(listOf(given())))
			}
			val found = accumulator("first")
			init += Statement.Local(listOf(found), listOf())
			deliver(after, Expression.name(found))
			return Block.of(assign(found, given()), stop())
		}

		/** `last { ... }`, `lastOrNull()` and `findLast { ... }`: each element that passes, kept until the end. */
		private fun last(predicate: KtExpression?, init: MutableList<Statement>, after: MutableList<Statement>):
			Block {
			predicate?.let { steps += Step.Guard(test(it)) }
			val found = accumulator("last")
			init += Statement.Local(listOf(found), listOf())
			deliver(after, Expression.name(found))
			return Block.of(assign(found, given()))
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
			return Block.of(assign(answer, Expression.BooleanLiteral(found)), stop())
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

		/**
		 * The steps around the terminal's code: each guard an if around what follows, consecutive guards one if, and
		 * each inner loop a loop around what follows.
		 */
		private fun nested(innermost: Block): Block {
			var block = innermost
			var index = steps.lastIndex
			while (index >= 0) {
				when (val step = steps[index]) {
					is Step.Bind -> {
						val bound = Block.Entry.of(local(step.name, step.value))
						block = Block(listOf(bound) + block.entries(), block.closingComments())
					}
					is Step.Do -> block = Block(listOf(Block.Entry.of(step.statement)) + block.entries(),
						block.closingComments())
					is Step.Loop -> block = Block.of(step.loop(block))
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
			"range or map, or a map's keys, values or entries, then filter, filterNot, filterKeys, filterValues, " +
			"filterNotNull, map, mapNotNull, flatMap, distinct, distinctBy, take, drop, takeWhile and dropWhile, " +
			"then forEach, toList, toSet, a sort, count, sum, sumOf, min, max and their By, Of and With forms, " +
			"first, last, find, any, all, none, joinToString, groupBy, associate, associateBy, associateWith, " +
			"mapValues or mapKeys"

		/** Why a map's entry can be read only as its key and its value. */
		const val ENTRY_PARTS = "In Lua a map's entry is its key and its value, which a loop walks, not a value of" +
			" its own; read entry.key and entry.value, or destructure it, (key, value) ->, and take .key or .value of" +
			" an entry a chain finds."

		/** The steps a loop runs on each element before the terminal call. */
		private val STAGES = setOf("filter", "filterNot", "filterKeys", "filterValues", "filterNotNull", "map",
			"mapNotNull", "flatMap", "distinct", "distinctBy", "take", "drop", "takeWhile", "dropWhile")

		/** The steps that pass elements on as they are, which leave the loop's variable its name. */
		private val KEEPS_ELEMENT = setOf("filterNotNull", "distinct", "take", "drop")

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
			"toMutableSet", "toHashSet", "count", "sum", "sumOf", "any", "all", "none", "joinToString", "groupBy",
			"associate", "associateBy", "associateWith", "mapValues", "mapKeys")

		/** Every function a chain is made of, which [LibraryCalls] refuses outside a chain's place, with the reason. */
		val FUNCTIONS = TERMINALS

		/** The calls whose lambda's parameter is the element, which can name the loop's variable. */
		private val ELEMENT_LAMBDAS = STAGES + setOf("forEach", "count", "sumOf", "maxOf", "minOf", "maxOfOrNull",
			"minOfOrNull", "maxBy", "minBy", "maxByOrNull", "minByOrNull", "first", "firstOrNull", "find", "last",
			"lastOrNull", "findLast", "any", "all", "none", "joinToString", "groupBy", "associate", "associateBy",
			"associateWith")

		/** The steps after which the elements are values that the lambdas made, rather than a map's entries. */
		private val MAKES_VALUES = setOf("map", "mapNotNull", "flatMap")

		/** The calls whose lambda's parameter is a map's entry, when the chain walks one. */
		private val ENTRY_LAMBDAS = ELEMENT_LAMBDAS - setOf("filterKeys", "filterValues") +
			setOf("mapValues", "mapKeys")

		/** The parameters of `joinToString` a chain's join takes. */
		private val JOIN_PARAMETERS = setOf("separator", "prefix", "postfix", "transform")
	}
}
