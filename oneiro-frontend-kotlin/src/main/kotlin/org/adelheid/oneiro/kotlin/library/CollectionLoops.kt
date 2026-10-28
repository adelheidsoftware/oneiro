package org.adelheid.oneiro.kotlin.library

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.adelheid.oneiro.kotlin.core.BodyLowering
import org.adelheid.oneiro.kotlin.core.Receiver
import org.adelheid.oneiro.kotlin.core.Rejection
import org.adelheid.oneiro.kotlin.language.Branches
import org.adelheid.oneiro.kotlin.language.CountingRange
import org.adelheid.oneiro.lowering.translation.Helper
import org.adelheid.oneiro.lowering.translation.IndexArithmetic
import org.adelheid.oneiro.lowering.translation.Inlining
import org.adelheid.oneiro.lowering.translation.PlainValues
import org.adelheid.oneiro.lua.BinaryOperator
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.Statement
import org.adelheid.oneiro.lua.Trivia
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParameter
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
 * own. Each lambda's parameter stands for the loop's current element, so the lambdas' code reads as written. A chain
 * over a map's entries walks their keys and values, as [MapEntries] describes, and a text's `count { ... }` and its kin
 * walk its chars, as a for loop over the text does.
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

		/** Into the two locals a destructuring declaration names, as `partition`'s lists. */
		class Parts(val names: List<String>) : Destination
	}

	/**
	 * One call of a chain: the function's name, the call and its arguments, and the whole `receiver.call(...)`, or the
	 * call alone on a receiver left unnamed.
	 */
	class Operation(
		val name: String,
		val call: KtCallExpression,
		val arguments: LibraryArguments,
		val expression: KtExpression,
	) {

		/** The receiver written before the dot, or null for one left unnamed. */
		val receiver: KtExpression?
			get() = (expression as? KtDotQualifiedExpression)?.receiverExpression
	}

	/**
	 * A chain from its collection, written or left unnamed, to the call that gives its result, the default `?:` gives
	 * when that call finds nothing, as in `firstOrNull { ... } ?: "none"`, and the part of a map's entry it gives,
	 * `key` or `value`, when it finds an entry, as in `maxByOrNull { it.value }?.key`.
	 */
	class Chain(
		val source: Receiver,
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
		if (terminal.name in WINDOWS) {
			// The call walks its list in windows, which it collects: the call is the chain's source and its end
			return Chain(Receiver.Written(current), listOf(), terminal, null, null)
		}
		if (terminal.name !in TERMINALS || (default != null || part != null) && terminal.name !in FINDS) {
			return null
		}
		val stages = mutableListOf<Operation>()
		var first = terminal
		while (true) {
			// A call that reads the elements' positions starts its chain, whose loop walks the list's positions
			if (first.name in INDEXED) {
				break
			}
			val receiver = first.receiver?.let(KtPsiUtil::safeDeparenthesize) ?: break
			val stage = operation(receiver)?.takeIf { it.name in STAGES } ?: break
			stages.add(0, stage)
			first = stage
		}
		// The collection the first call is made on: written before its dot, or the receiver it is made on unnamed
		val source = first.receiver?.let { Receiver.Written(KtPsiUtil.safeDeparenthesize(it)) }
			?: body.unnamedReceiver(first.call) ?: return null
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

	/**
	 * The name of a local that holds a chain's result, after the call that gives it, as the loop's own locals are
	 * named: `count`, `sum`, `any`, `first`, `sorted`, a `list` or a `map`.
	 */
	fun resultName(chain: Chain): String = when (val name = chain.terminal.name) {
		in SORTS -> "sorted"
		"count", "any", "all", "none", "first", "last", "single", "average" -> name
		"sum", "sumOf" -> "sum"
		"firstOrNull", "find" -> "first"
		"lastOrNull", "findLast" -> "last"
		"singleOrNull" -> "single"
		"fold", "reduce", "reduceOrNull" -> "total"
		"joinToString" -> "text"
		"groupBy" -> "groups"
		"toSet", "toMutableSet", "toHashSet" -> "set"
		in EXTREMES -> if (name.startsWith("max")) "max" else "min"
		else -> if (type(chain.terminal.expression)?.let(types::isMap) == true) "map" else "list"
	}

	/**
	 * `receiver.function(...)`, or `function(...)` on a receiver left unnamed, for one of Kotlin's functions on
	 * collections, or one of a text's that tests or finds its chars with a lambda; null for any other expression.
	 */
	private fun operation(expression: KtExpression?): Operation? = with(unit.session) {
		val qualified = expression as? KtDotQualifiedExpression
		val call = (qualified?.selectorExpression ?: expression) as? KtCallExpression ?: return null
		val resolved = call.resolveToCall()?.successfulFunctionCallOrNull() ?: return null
		val id = resolved.symbol.callableId ?: return null
		val name = id.callableName.asString()
		val onText = id.packageName.asString() == "kotlin.text" && name in TEXT_TERMINALS &&
			call.valueArguments.size == 1
		if (id.packageName.asString() != "kotlin.collections" && !onText || id.classId != null) {
			return null
		}
		Operation(name, call, body.libraryArguments(resolved, call), qualified ?: call)
	}

	/** Whether a call on a list, set or map, with no steps before it, has a form of its own without a loop. */
	private fun isDirect(chain: Chain): Boolean {
		val terminal = chain.terminal
		val kind = body.library.kind(type(chain.source))
		if (kind == null || written(chain.source)?.let(body.library::mapView) != null) {
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
			"fold" -> operation.arguments.byName["operation"]
			"chunked", "windowed", "zip" -> operation.arguments.byName["transform"]
			"take", "drop" -> null
			else -> operation.arguments.singleOrNull()
		}
		return argument?.let(KtPsiUtil::safeDeparenthesize)
	}

	private fun type(expression: KtExpression): KaType? = with(unit.session) { expression.expressionType }

	private fun type(receiver: Receiver): KaType? = receiver.type(unit.session)

	/** A receiver's expression, or null for one left unnamed. */
	private fun written(receiver: Receiver): KtExpression? = (receiver as? Receiver.Written)?.expression

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

		/**
		 * The statements of a lambda before its value, for each element that gets this far; the comments that close
		 * the block go before what comes after it.
		 */
		class Code(val code: Block) : Step

		/**
		 * A loop around everything after it, over the collection `flatMap` gives for each element, at its depth among
		 * the loops inside the source's.
		 */
		class Loop(val loop: (Block) -> Statement, val depth: Int) : Step
	}

	/** The table a chain collects its elements into. */
	private enum class Collected { LIST, SET, ORDERED_SET }

	/** One chain being lowered. */
	inner class Fusion(val chain: Chain, private val destination: Destination) {

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
		// What says the chain ended early inside those loops, with the depth it ended at: each loop around tests it
		private val stops = mutableListOf<Pair<Expression, Int>>()
		// The flag that says so where nothing else does, once a stop needs one
		private var stopped: String? = null
		// While the loop's element is a map's entry, the walk over the map's entries, whose key's name element is
		private var entries: MapEntries? = null
		// The name of the list's position in the loop over it, which a first mapIndexed or filterIndexed reads
		private var sourceIndex: String? = null
		// The names the parameters of zip's transform give the parts of each pair, null for one it leaves unnamed
		private var zipParts: List<String?>? = null

		fun lower(): List<Statement> {
			if (chain.stages.isEmpty() && chain.terminal.name in SORTS &&
				body.library.kind(type(chain.source)) == LibraryCalls.Kind.SEQUENCE) {
				return sortedCopy()
			}
			val walked = entryMap(chain.source)
			val source = if (walked != null) entrySource(walked) else source(chain.source)
			if (walked == null) {
				// Each element flatten walks is a collection of items, and each toMap takes a pair
				val defaultName = when ((chain.stages.firstOrNull() ?: chain.terminal).name) {
					"flatten" -> "items"
					"toMap", "toMutableMap" -> "pair"
					else -> source.defaultName
				}
				element = elementName(0, defaultName)
			}
			val loopVariable = element
			used += element
			chain.stages.forEach(::stage)
			val init = mutableListOf<Statement>()
			val after = mutableListOf<Statement>()
			val innermost = terminal(init, after)
			return before + init + source.loop(loopVariable, nested(innermost)) + after
		}

		/**
		 * Ends the chain: `break`. Inside a loop of its own, each loop around then tests whether the chain ended, after
		 * the loop inside it, and breaks too, as Lua's `break` leaves only the innermost loop.
		 *
		 * @param ended what says the chain ended, such as `taken == 5`, or null for one that leaves nothing that says
		 *     so, which then sets a flag, `stopped`
		 */
		private fun stop(ended: Expression?): Block {
			if (innerLoops == 0) {
				return Block.of(Statement.Break())
			}
			if (ended != null) {
				stops += ended to innerLoops
				return Block.of(Statement.Break())
			}
			val flag = stopped ?: localBefore("stopped", Expression.BooleanLiteral(false), chain.terminal.call)
				.also { stopped = it }
			stops += Expression.name(flag) to innerLoops
			return Block.of(assign(flag, Expression.BooleanLiteral(true)), Statement.Break())
		}

		// ---- The source

		/** A list's or a set's elements, a range's integers, or a map's keys or values, which walk the map itself. */
		private fun source(source: Receiver): Source {
			val written = written(source)
			written?.let { CountingRange.of(it) }?.let { range ->
				return Source("i") { item, loop -> body.loops.numericFor(item, range) { loop } }
			}
			written?.let(::windows)?.let { return it }
			val view = written?.let(body.library::mapView)
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
			if (type(source)?.let(types::isString) == true) {
				// A text, walked one char at a time, as a for loop over it is
				val characters = Expression.methodCall(body.expression(source), "gmatch", Expression.string("."))
				return Source("char") { item, loop -> Statement.GenericFor(listOf(item), listOf(characters), loop) }
			}
			return when (body.library.kind(type(source))) {
				LibraryCalls.Kind.SEQUENCE -> {
					val list = Expression.call(Expression.name("ipairs"), body.expression(source))
					Source("item") { item, loop ->
						val index = sourceIndex
						val names = if (index != null) listOf(index, item) else valueNames(item)
						Statement.GenericFor(names, listOf(list), loop)
					}
				}
				LibraryCalls.Kind.SET -> {
					val set = Expression.call(Expression.name("pairs"), body.expression(source))
					Source("item") { item, loop -> Statement.GenericFor(listOf(item), listOf(set), loop) }
				}
				LibraryCalls.Kind.MAP -> throw IllegalStateException("a map, whose entries entrySource walks")
				null -> throw Rejection(written ?: (chain.stages.firstOrNull() ?: chain.terminal).call,
					"$SUPPORTED; write a for loop for this one.")
			}
		}

		/**
		 * The windows a call walks a list in, each the loop's element, copied out of the list as the loop reaches it:
		 * the chunks of `chunked(n)`, the windows of `windowed(n, step)` and the pairs of `zip(other)`; null for any
		 * other source.
		 */
		private fun windows(written: KtExpression): Source? {
			val operation = operation(written)?.takeIf { it.name in WINDOWS } ?: return null
			val receiver = operation.receiver
				?: throw Rejection.notYet(operation.call, "${operation.name} on a receiver left unnamed")
			if (body.library.kind(type(receiver)) != LibraryCalls.Kind.SEQUENCE) {
				throw Rejection.notYet(operation.call, "${operation.name} of a set, a map or a range")
			}
			if (operation.call != chain.terminal.call && argument(operation) != null) {
				throw Rejection.notYet(operation.call, "${operation.name} with a transform before other calls")
			}
			val list = readOnce(receiver, "list", operation.call)
			val position = taken("i", operation.call)
			return if (operation.name == "zip") zipped(operation, list, position) else sliced(operation, list, position)
		}

		/**
		 * `chunked(size)`, `for i = 1, #list, size do` and `local chunk = table.move(list, i, math.min(i + size - 1,
		 * #list), 1, {})`, whose last chunk may be smaller; and `windowed(size, step)`,
		 * `for i = 1, #list - size + 1, step do` and `local window = table.move(list, i, i + size - 1, 1, {})`.
		 */
		private fun sliced(operation: Operation, list: Expression, position: String): Source {
			val chunked = operation.name == "chunked"
			val arguments = operation.arguments.byName
			if ("partialWindows" in arguments) {
				throw Rejection.notYet(operation.call, "windowed with partial windows")
			}
			val size = readOnce(arguments.getValue("size"), "size", operation.call)
			val step = arguments["step"]?.let { readOnce(it, "step", operation.call) }
			val length = LibraryCalls.length(list)
			val last = IndexArithmetic.minusOne(Expression.binary(Expression.name(position), BinaryOperator.ADD, size))
			val end = if (chunked) body.library.mathCall("min", last, length) else last
			val limit = if (chunked) length
			else IndexArithmetic.plusOne(Expression.binary(length, BinaryOperator.SUBTRACT, size))
			return Source(if (chunked) "chunk" else "window") { item, loop ->
				val slice = tableCall("move", list, Expression.name(position), end, Expression.integer(1),
					Expression.Table(listOf()))
				val code = if (item == "_") loop
				else Block(listOf(Block.Entry.of(local(item, slice))) + loop.entries(), loop.closingComments())
				Statement.NumericFor(position, Expression.integer(1), limit, if (chunked) size else step, code)
			}
		}

		/**
		 * `zip(other)`: `for i = 1, math.min(#list, #other) do`, and each pair,
		 * `local pair = { first = list[i], second = other[i] }`; for the lambda of `zip(other) { a, b -> ... }`, the
		 * parts alone, under its parameters' names, `local a, b = list[i], other[i]`.
		 */
		private fun zipped(operation: Operation, list: Expression, position: String): Source {
			val otherTree = operation.arguments.byName.getValue("other")
			if (body.library.kind(type(otherTree)) != LibraryCalls.Kind.SEQUENCE) {
				throw Rejection.notYet(operation.call, "zip with a set, a map or a range")
			}
			val other = readOnce(otherTree, "other", operation.call)
			val parts = listOf(list, other).map { Expression.Index(it, Expression.name(position)) }
			val limit = body.library.mathCall("min", LibraryCalls.length(list), LibraryCalls.length(other))
			return Source("pair") { item, loop ->
				val names = zipParts
				val unpacked = when {
					names != null -> names.indices.filter { names[it] != null }.takeIf { it.isNotEmpty() }?.let {
						Statement.Local(it.map { index -> names[index]!! }, it.map(parts::get))
					}
					item == "_" -> null
					else -> local(item, Expression.Table(LibraryCalls.PAIR_PARTS.zip(parts).map { (name, part) ->
						Expression.TableField.Named(name, part)
					}))
				}
				val code = if (unpacked == null) loop
				else Block(listOf(Block.Entry.of(unpacked)) + loop.entries(), loop.closingComments())
				Statement.NumericFor(position, Expression.integer(1), limit, null, code)
			}
		}

		/** The map whose entries a chain walks: a map itself, or a map's `entries`; null for any other source. */
		private fun entryMap(source: Receiver): Receiver? {
			val view = written(source)?.let(body.library::mapView)
			if (view != null) {
				return if (view.selector == "entries") Receiver.Written(view.map) else null
			}
			return if (body.library.kind(type(source)) == LibraryCalls.Kind.MAP) source else null
		}

		/** A map's entries, which the loop walks as their keys and values: `for key, value in pairs(map) do`. */
		private fun entrySource(map: Receiver): Source {
			val walk = MapEntries(body, this, map, entryLambdas())
			entries = walk
			element = walk.key
			used += walk.value
			return Source(walk.key) { _, loop -> walk.loop(loop) }
		}

		/**
		 * The lambdas whose parameter is the map's entry: those of the calls that take one, up to the first step
		 * after which the lambdas take the values it made instead.
		 */
		private fun entryLambdas(): List<KtLambdaExpression> {
			val consumers = chain.stages + chain.terminal
			val makesValues = consumers.indexOfFirst { it.name in MAKES_VALUES }
			val takingEntries = if (makesValues < 0) consumers else consumers.take(makesValues + 1)
			return takingEntries.filter { it.name in ENTRY_LAMBDAS }.mapNotNull { argument(it) as? KtLambdaExpression }
		}

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
				if (consumer.name in FOLDS + INDEXED && function is KtLambdaExpression) {
					val item = pairedParameters(consumer, function).second
					if (item.name == "_") {
						break
					}
					return unit.localName(item, item.name!!)
				}
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
				"filterKeys", "filterValues" -> steps += Step.Guard(entries!!.test(stage, function!!))
				"filterNotNull" -> notNil(stage)
				"map" -> {
					pending = mapped(function!!)
					entries = null
				}
				"mapNotNull" -> {
					pending = mapped(function!!)
					entries = null
					notNil(stage)
				}
				"distinct" -> {
					if (entries != null) {
						throw Rejection(stage.call, MapEntries.ENTRY_PARTS)
					}
					distinct(stage, null)
				}
				"distinctBy" -> distinct(stage, function!!)
				"take" -> take(stage)
				"drop" -> drop(stage)
				"takeWhile" -> steps += Step.Do(Statement.ifThen(body.operators.negation(test(function!!)),
					stop(null)))
				"dropWhile" -> dropWhile(stage, function!!)
				"flatMap" -> flatten(stage, function!!)
				"flatten" -> flattened(stage)
				"mapIndexed" -> {
					pending = indexed(stage, function!!)
					entries = null
				}
				"filterIndexed" -> steps += Step.Guard(indexed(stage, function!!))
				"filterIsInstance" -> {
					// The elements of the type the list it gives holds
					val type = type(stage.expression)?.let { types.typeArgumentOf(it, 0) } ?: throw unsupported(stage)
					bindPending("value", stage.call)
					steps += Step.Guard(body.typeTests.typeTest(Expression.name(element), type, stage.call))
				}
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
			steps += Step.Do(Statement.ifThen(full, stop(full)))
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

		/** `take`'s and `drop`'s count, read once. */
		private fun limit(stage: Operation): Expression = readOnce(stage.arguments[0], "limit", stage.call)

		/**
		 * A value the loop reads each time round: one that reading again gives the same, such as a literal or a name,
		 * as it is (see [PlainValues]), and anything else read once into a local before the loop, as Kotlin reads it
		 * once.
		 */
		private fun readOnce(value: KtExpression, preferred: String, where: PsiElement): Expression {
			val lowered = body.expression(KtPsiUtil.safeDeparenthesize(value))
			if (PlainValues.isPlain(lowered)) {
				return lowered
			}
			return Expression.name(localBefore(preferred, lowered, where))
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
			innerLoops++
			steps += Step.Loop(innerLoop(collection, name), innerLoops)
			element = name
			entries = null
			used += name
		}

		/**
		 * The value of the lambda of `mapIndexed` or `filterIndexed`, which starts its chain over a list: its first
		 * parameter stands for the list's position, which Kotlin counts from 0, and its second for the element. The
		 * position is `ipairs`'s own when it counts from 1 in Lua (see [OneBasedLoops]); otherwise a local reads it,
		 * `local index = i - 1`.
		 */
		private fun indexed(stage: Operation, function: KtExpression): Expression {
			if (body.library.kind(type(chain.source)) != LibraryCalls.Kind.SEQUENCE || entries != null) {
				throw Rejection.notYet(stage.call, "${stage.name} over a set, a map or a range")
			}
			val lambda = function as? KtLambdaExpression
				?: throw Rejection.notYet(function, "${stage.name} with a function reference")
			val (index, item) = pairedParameters(stage, lambda)
			return lambdaValue(lambda, {
				if (item.name != "_") {
					unit.alias(item, element)
				}
				if (index.name != "_") {
					val name = unit.localName(index, index.name!!)
					used += name
					if (index in unit.oneBased) {
						sourceIndex = name
					} else {
						val position = taken("i", stage.call)
						sourceIndex = position
						steps += Step.Bind(name, IndexArithmetic.minusOne(Expression.name(position)))
					}
				}
			})
		}

		/**
		 * `flatten()`: a loop over each element, itself a list, set or array, inside which its elements go through the
		 * rest of the chain.
		 */
		private fun flattened(stage: Operation) {
			bindPending("items", stage.call)
			// The type of the collections flatten walks, the elements of the collection it is called on
			val input = stage.receiver?.let(::type) ?: type(chain.source)
			val name = elementName((chain.stages + chain.terminal).indexOf(stage) + 1, "item")
			val loop = loopOver(Expression.name(element), input?.let { types.typeArgumentOf(it, 0) }, name)
				?: throw Rejection(stage.call, "flatten walks the elements of each element, which must be a list, set"
					+ " or array.")
			innerLoops++
			steps += Step.Loop(loop, innerLoops)
			element = name
			used += name
		}

		/** The loop over one element's collection, a list's, an array's, a set's or a range's elements. */
		private fun innerLoop(collection: KtExpression, item: String): (Block) -> Statement {
			CountingRange.of(collection)?.let { range ->
				return { loop -> body.loops.numericFor(item, range) { loop } }
			}
			return loopOver(body.expression(collection), type(collection), item)
				?: throw Rejection(collection, "flatMap's lambda gives the collection the loop inside the loop"
					+ " walks; give it a list, set, array or range.")
		}

		/** The loop over a list's, an array's or a set's elements; null for a collection of another type. */
		private fun loopOver(collection: Expression, type: KaType?, item: String): ((Block) -> Statement)? =
			when (body.library.kind(type)) {
				LibraryCalls.Kind.SEQUENCE -> { loop ->
					val walked = Expression.call(Expression.name("ipairs"), collection)
					Statement.GenericFor(valueNames(item), listOf(walked), loop)
				}
				LibraryCalls.Kind.SET -> { loop ->
					Statement.GenericFor(listOf(item), listOf(Expression.call(Expression.name("pairs"), collection)),
						loop)
				}
				else -> null
			}

		/**
		 * A selector's value for the current element, in a local of its own that later code reads, `local key =
		 * it.item`; a key's must be one Lua tables find.
		 */
		private fun selected(selector: KtExpression, preferred: String, where: PsiElement): String {
			val value = if (selector is KtLambdaExpression) {
				lambdaValue(selector, { bindElement(selector) }) { key ->
					body.library.checkKey(key, type(key))
					body.expression(key)
				}
			} else {
				bindPending("value", selector)
				applied(selector)
			}
			return bound(preferred, value, where)
		}

		/** A local inside the loop, under a name of its own, which the steps after it read: `local name = value`. */
		fun bound(preferred: String, value: Expression, where: PsiElement): String {
			val name = taken(preferred, where)
			steps += Step.Bind(name, value)
			return name
		}

		/** A local before the loop, under a name of its own. */
		private fun localBefore(preferred: String, value: Expression, where: PsiElement): String {
			val name = taken(preferred, where)
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
				return lambdaValue(predicate, { bindElement(predicate) })
			}
			bindPending("value", predicate)
			return applied(predicate)
		}

		/** A function's value for the current element: the lambda's expression, or a call of the function. */
		fun mapped(function: KtExpression): Expression {
			if (function is KtLambdaExpression) {
				return lambdaValue(function, { bindElement(function) })
			}
			return applied(function)
		}

		/**
		 * The current element: a pending mapped value, which this reads once, or the element's name. A map's entry is
		 * no one value Lua could take.
		 */
		private fun current(): Expression {
			val value = pending
			if (value == null && entries != null) {
				throw Rejection(chain.terminal.call, MapEntries.ENTRY_PARTS)
			}
			pending = null
			return value ?: Expression.name(element)
		}

		/**
		 * What a find keeps of the current element: the element, or of a map's entry the part the chain reads, `?.key`
		 * or `?.value`.
		 */
		private fun given(): Expression {
			val entries = entries
			if (entries == null || pending != null) {
				return current()
			}
			return entries.found()
		}

		/** Gives a pending mapped value a local, so that it can be read more than once. */
		private fun bindPending(preferred: String, where: PsiElement) {
			val value = pending ?: return
			val local = if (preferred in used) fresh(preferred, where) else preferred
			steps += Step.Bind(local, value)
			used += local
			element = local
			entries = null
			pending = null
		}

		/**
		 * The value of a lambda the loop runs for each element, once [bind] has made the lambda's parameters stand for
		 * the element: what [value] makes of its last expression, read in place, after the statements before it, which
		 * run in the loop's body first, as a step of their own.
		 */
		fun lambdaValue(lambda: KtLambdaExpression, bind: () -> Unit,
			value: (KtExpression) -> Expression = body::expression): Expression {
			val (code, lowered) = lambdaCode(lambda, bind, value)
			if (code.entries().isNotEmpty() || code.closingComments().isNotEmpty()) {
				steps += Step.Code(code)
			}
			return lowered
		}

		/**
		 * A lambda's statements before its last expression, each with what its values need before it, and what [value]
		 * makes of that expression, once [bind] has made the lambda's parameters stand for the element. The comments
		 * above the last expression, and after it, close the block. A last expression the lowering rejects has been
		 * reported, and gives nil.
		 */
		private fun lambdaCode(lambda: KtLambdaExpression, bind: () -> Unit,
			value: (KtExpression) -> Expression): Pair<Block, Expression> {
			// The lambda's label, as Kotlin gives it, is the name of the function it is passed to
			val function = PsiTreeUtil.getParentOfType(lambda, KtCallExpression::class.java)?.calleeExpression?.text
			body.lambdas.inPlace(lambda, function ?: "label")
			val code = lambda.bodyExpression ?: throw Rejection(lambda, "This lambda has no body.")
			val last = code.statements.lastOrNull() ?: throw Rejection(lambda, NO_VALUE)
			bind()
			val comments = unit.source.leading(last)
			var lowered: Expression = Expression.Nil()
			val statements = body.block(code) { expression ->
				if (expression is KtDeclaration) {
					throw Rejection(expression, NO_VALUE)
				}
				lowered = value(expression)
				listOf()
			}
			return Block(statements.entries(), comments + statements.closingComments()) to lowered
		}

		/** A lambda's statements before the code that reads its value, which takes the comments that close them. */
		private fun ahead(code: Block, block: Block): Block {
			val first = block.entries().firstOrNull()
			val comments = code.closingComments()
			if (first == null || comments.isEmpty()) {
				return Block(code.entries() + block.entries(), comments + block.closingComments())
			}
			val trivia = first.trivia()
			val commented = Block.Entry(first.statement(), Trivia(trivia.blankLineBefore(), comments + trivia.leading(),
				trivia.trailing()), first.origin())
			return Block(code.entries() + commented + block.entries().drop(1), block.closingComments())
		}

		/** Makes a lambda's parameter stand for the current element, which a pending value is bound for first. */
		private fun bindElement(lambda: KtLambdaExpression) {
			val entries = entries
			if (entries != null) {
				entries.bind(lambda)
				return
			}
			val parameter = body.lambdas.parameter(lambda)
			bindPending(unit.localName(parameter.declaration, parameter.name), lambda)
			unit.alias(parameter.declaration, element)
		}

		/**
		 * A function called on the current element, or on the argument given, opened where it is a reference's small
		 * function; a function value the call cannot open goes into a local before the loop.
		 */
		fun applied(function: KtExpression, argument: Expression? = null): Expression {
			val lowered = body.expression(function)
			if (lowered is Expression.FunctionExpression && argument == null) {
				bindPending("value", function)
			}
			val value = argument ?: current()
			return Inlining.value(lowered, listOf(value)) ?: called(lowered, value, function)
		}

		/** A function value called on the value; a function expression goes into a local before the loop first. */
		private fun called(function: Expression, value: Expression, where: PsiElement): Expression {
			if (function is Expression.FunctionExpression) {
				val local = taken("action", where)
				before += Statement.Local(listOf(local), listOf(function))
				return Expression.Call(Expression.name(local), listOf(value))
			}
			return Expression.Call(function, listOf(value))
		}

		/** A name of the function's own the loop's variables have not taken. */
		fun fresh(preferred: String, where: PsiElement): String {
			var name = body.invent(preferred, where)
			var number = 2
			while (name in used) {
				name = preferred + number++
			}
			return name
		}

		/** A name of the function's own the loop's variables have not taken, which one of them now takes. */
		fun taken(preferred: String, where: PsiElement): String {
			val name = fresh(preferred, where)
			used += name
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
					val entries = entries
					if (entries != null && resultType()?.let(types::isMap) == true) {
						entries.filtered(init, after)
					} else {
						collected(Collected.LIST, init, after)
					}
				}
				"mapValues", "mapKeys" ->
					entries?.transformed(terminal, function!!, init, after) ?: throw unsupported(terminal)
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
				"single", "singleOrNull" -> single(function, init, after)
				in FOLDS -> folded(terminal, function!!, init, after)
				"average" -> averaged(init, after)
				"partition" -> partitioned(function!!, init)
				"toMap", "toMutableMap" -> mapOfPairs(init, after)
				// The windows, or what the transform makes of each
				in WINDOWS -> {
					function?.let { pending = if (terminal.name == "zip") zipValue(terminal, it) else mapped(it) }
					collected(Collected.LIST, init, after)
				}
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
			val group = taken("group", terminal.call)
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
			var key: Expression = Expression.Nil()
			val value = lambdaValue(lambda, { bindElement(lambda) }) { pair ->
				val (keyTree, valueTree) = body.library.pairParts(pair)?.takeIf { it.size == 2 }
					?: throw Rejection.notYet(pair, "associate with a pair that is not written key to value")
				key = body.expression(keyTree)
				body.expression(valueTree)
			}
			return stored(key, value, init, after)
		}

		/**
		 * The map `associate` and its kin give, which keeps its keys in the order they came, as Kotlin's does: each
		 * element stores its value under its key.
		 */
		fun stored(key: Expression, value: Expression, init: MutableList<Statement>,
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
			val lowered = body.expression(action)
			if (lowered is Expression.FunctionExpression) {
				bindPending("value", action)
			}
			val value = current()
			// A reference's small function is opened into what it does: a call, or an assignment such as list::add's
			Inlining.statement(lowered, listOf(value))?.let { return Block.of(it) }
			if (Inlining.value(lowered, listOf(value)) != null) {
				throw Rejection(action, "In Lua a statement is a call; this function's value would go nowhere.")
			}
			return Block.of(Statement.CallStatement(called(lowered, value, action)))
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
			val source = body.reused(chain.source, LibraryCalls.READ_TWICE)
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
			val entries = entries
			if (entries != null && withComparator) {
				throw Rejection.notYet(terminal.call, "maxWith and minWith over a map's entries")
			}
			if (entries != null && byKey) {
				return entries.extreme(terminal, function!!, max, init, after)
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
			return Block(listOf(Block.Entry.of(assign(found, given()))) + stop(null).entries(), listOf())
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
		 * `single { ... }` and `singleOrNull()`: the one element that passes, which a second one that passes takes
		 * back, ending the loop. Nothing found, or more than one, is nil, where Kotlin's `single` throws.
		 */
		private fun single(predicate: KtExpression?, init: MutableList<Statement>, after: MutableList<Statement>):
			Block {
			predicate?.let { steps += Step.Guard(test(it)) }
			val found = accumulator("single")
			init += Statement.Local(listOf(found), listOf())
			deliver(after, Expression.name(found))
			// The elements are never nil in Lua, so a value kept means one passed before
			val again = Expression.binary(Expression.name(found), BinaryOperator.NOT_EQUAL, Expression.Nil())
			val takenBack = Block(listOf(Block.Entry.of(assign(found, Expression.Nil()))) + stop(null).entries(),
				listOf())
			return Block.of(Statement.ifThen(again, takenBack), assign(found, given()))
		}

		/**
		 * `fold(initial) { total, x -> ... }` and `reduce { total, x -> ... }`: the running value in a local, which the
		 * lambda's first parameter stands for, as its second stands for the element, and which each element sets to the
		 * lambda's value. `reduce` starts from the first element, and an empty collection gives nil, where Kotlin's
		 * `reduce` throws.
		 */
		private fun folded(terminal: Operation, function: KtExpression, init: MutableList<Statement>,
			after: MutableList<Statement>): Block {
			if (entries != null) {
				throw Rejection.notYet(terminal.call, "${terminal.name} over a map's entries")
			}
			val lambda = function as? KtLambdaExpression
				?: throw Rejection.notYet(function, "${terminal.name} with a function reference")
			val (total, item) = pairedParameters(terminal, lambda)
			lateinit var running: String
			// The lambda's statements run where its value is worked out, which reduce skips for the first element
			val (code, value) = lambdaCode(lambda, {
				if (item.name != "_") {
					bindPending(unit.localName(item, item.name!!), lambda)
					unit.alias(item, element)
				}
				running = runningValue(total)
			}, body::expression)
			deliver(after, Expression.name(running))
			val next = ahead(code, Block.of(assign(running, value)))
			if (terminal.name == "fold") {
				init += local(running, body.expression(terminal.arguments.byName["initial"]!!))
				return next
			}
			bindPending("value", terminal.call)
			init += Statement.Local(listOf(running), listOf())
			val first = Expression.binary(Expression.name(running), BinaryOperator.EQUAL, Expression.Nil())
			return Block.of(Statement.If(listOf(Statement.If.Branch(first, Block.of(assign(running,
				Expression.name(element))))), next))
		}

		/**
		 * The local of `fold`'s and `reduce`'s running value: the destination's own local when the result goes there as
		 * it is, otherwise one named after the lambda's first parameter, which then stands for it.
		 */
		private fun runningValue(total: KtParameter): String {
			val default = chain.default
			val name = if (destination is Destination.Local && (default == null || Branches.jumps(default))) {
				destination.name
			} else {
				val preferred = unit.localName(total, total.name?.takeIf { it != "_" } ?: "total")
				if (preferred in used) fresh(preferred, total) else preferred
			}
			used += name
			unit.alias(total, name)
			return name
		}

		/**
		 * `val (passed, failed) = drops.partition { ... }`: two lists filled in one loop, each element into the first
		 * when it passes the test, and into the second otherwise.
		 */
		private fun partitioned(predicate: KtExpression, init: MutableList<Statement>): Block {
			val parts = destination as? Destination.Parts ?: throw Rejection.notYet(chain.terminal.call,
				"partition whose lists are not destructured, val (passed, failed) = ...")
			val test = test(predicate)
			val item = current()
			init += Statement.Local(parts.names, parts.names.map { Expression.Table(listOf()) })
			val (passed, failed) = parts.names.map { name ->
				Block.of(Statement.assignment(Expression.Index(Expression.name(name),
					LibraryCalls.nextIndex(Expression.name(name))), item))
			}
			return Block.of(Statement.If(listOf(Statement.If.Branch(test, passed)), failed))
		}

		/**
		 * `toMap()` of pairs: each pair's second part under its first, in a map that keeps the order they came, as
		 * Kotlin's does; a pair a step makes in place goes in as its parts. Of a map, a copy of it.
		 */
		private fun mapOfPairs(init: MutableList<Statement>, after: MutableList<Statement>): Block {
			entries?.let { return it.filtered(init, after) }
			val made = pending as? Expression.Table
			val parts = made?.fields()?.map { it as? Expression.TableField.Named }
			if (parts != null && parts.map { it?.name() } == LibraryCalls.PAIR_PARTS.take(2)) {
				pending = null
				return stored(parts[0]!!.value(), parts[1]!!.value(), init, after)
			}
			bindPending("pair", chain.terminal.call)
			val pair = Expression.name(element)
			return stored(Expression.field(pair, "first"), Expression.field(pair, "second"), init, after)
		}

		/**
		 * The value of the lambda of `zip(other) { a, b -> ... }`, whose parameters name the parts of each pair, which
		 * the loop reads into locals of their names.
		 */
		private fun zipValue(terminal: Operation, transform: KtExpression): Expression {
			val lambda = transform as? KtLambdaExpression
				?: throw Rejection.notYet(transform, "zip with a function reference")
			val parameters = pairedParameters(terminal, lambda)
			return lambdaValue(lambda, {
				zipParts = parameters.toList().map { parameter ->
					if (parameter.name == "_") null else unit.localName(parameter, parameter.name!!).also { used += it }
				}
			})
		}

		/** `average()`: the total and the count, and then the one divided by the other, which is NaN for none. */
		private fun averaged(init: MutableList<Statement>, after: MutableList<Statement>): Block {
			val sum = taken("sum", chain.terminal.call)
			val count = taken("count", chain.terminal.call)
			init += Statement.Local(listOf(sum, count), listOf(Expression.integer(0), Expression.integer(0)))
			deliver(after, Expression.binary(Expression.name(sum), BinaryOperator.DIVIDE, Expression.name(count)))
			return Block.of(assign(sum, Expression.binary(Expression.name(sum), BinaryOperator.ADD, current())),
				increment(count))
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
			// any's answer is true once it is decided, all's and none's false
			val decided = if (found) Expression.name(answer) else body.operators.negation(Expression.name(answer))
			val decision = Block.Entry.of(assign(answer, Expression.BooleanLiteral(found)))
			return Block(listOf(decision) + stop(decided).entries(), listOf())
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
				return lambdaValue(transform, { bindElement(transform) }, body.strings::asText)
			}
			return applied(transform)
		}

		/** Whether the elements joined are strings or numbers, which a range's integers are. */
		private fun joinable(terminal: Operation): Boolean = with(unit.session) {
			if (chain.stages.isEmpty() && written(chain.source)?.let { CountingRange.of(it) } != null) {
				return true
			}
			val written = terminal.receiver
			val receiver = (if (written != null) type(written) else type(chain.source)) ?: return false
			val element = types.elementClass(receiver) ?: types.typeArgumentOf(receiver, 0)?.expandedSymbol?.classId
			element in LibraryCalls.JOINABLE
		}

		/**
		 * The local a terminal builds its result in: the destination's own local when the result goes there as it is,
		 * otherwise a new one.
		 */
		fun accumulator(preferred: String): String {
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
		fun deliver(after: MutableList<Statement>, result: Expression) {
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
				is Destination.Parts -> throw IllegalStateException("partition fills the parts it is given itself")
			}
		}

		// ---- The loop's body

		/**
		 * The steps around the terminal's code: each guard an if around what follows, consecutive guards one if, and
		 * each inner loop a loop around what follows, after which the loop around it tests whether the chain ended
		 * inside it.
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
					is Step.Code -> block = ahead(step.code, block)
					is Step.Loop -> {
						val loop = Block.Entry.of(step.loop(block))
						val ended = stops.filter { it.second >= step.depth }.map { it.first }.distinct()
							.reduceOrNull { first, second -> Expression.binary(first, BinaryOperator.OR, second) }
						block = if (ended == null) Block(listOf(loop), listOf())
						else Block(listOf(loop, Block.Entry.of(Statement.ifThen(ended, Block.of(Statement.Break())))),
							listOf())
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

	/**
	 * The two parameters of a lambda that takes another value before the element: `fold`'s and `reduce`'s running
	 * value, or `mapIndexed`'s and `filterIndexed`'s position, then the element; or the two parts of `zip`'s pairs.
	 */
	private fun pairedParameters(operation: Operation, lambda: KtLambdaExpression): Pair<KtParameter, KtParameter> {
		val parameters = lambda.functionLiteral.valueParameters
		if (parameters.size != 2 || parameters.any { it.destructuringDeclaration != null }) {
			throw Rejection.notYet(lambda, "${operation.name} with a lambda that destructures its parameters")
		}
		return parameters[0] to parameters[1]
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

		private const val NO_VALUE = "This lambda gives the loop no value; end it with the value, as the last" +
			" expression."

		private const val SUPPORTED = "Oneiro makes one loop of a chain of collection functions: a list, set, array, " +
			"range or map, or a map's keys, values or entries, then filter, filterNot, filterKeys, filterValues, " +
			"filterNotNull, filterIsInstance, map, mapNotNull, flatMap, flatten, distinct, distinctBy, take, drop, " +
			"takeWhile and dropWhile, then forEach, toList, toSet, a sort, count, sum, sumOf, average, min, max and " +
			"their By, Of and With forms, first, last, single, find, any, all, none, fold, reduce, joinToString, " +
			"groupBy, associate, associateBy, associateWith, partition, toMap, mapValues or mapKeys; mapIndexed " +
			"and filterIndexed start a chain over a list, and chunked, windowed and zip walk one in windows"

		/** The steps a loop runs on each element before the terminal call. */
		private val STAGES = setOf("filter", "filterNot", "filterKeys", "filterValues", "filterNotNull",
			"filterIsInstance", "filterIndexed", "map", "mapNotNull", "mapIndexed", "flatMap", "flatten", "distinct",
			"distinctBy", "take", "drop", "takeWhile", "dropWhile")

		/** The steps whose lambda takes each element's position before the element, which start a chain. */
		private val INDEXED = setOf("mapIndexed", "filterIndexed")

		/** The steps that pass elements on as they are, which leave the loop's variable its name. */
		private val KEEPS_ELEMENT = setOf("filterNotNull", "filterIsInstance", "distinct", "take", "drop")

		/** The sorts that collect a sorted list. */
		private val SORTS = setOf("sorted", "sortedDescending", "sortedBy", "sortedByDescending", "sortedWith")

		/** The calls that keep the smallest or biggest value. */
		private val EXTREMES = setOf("max", "min", "maxOrNull", "minOrNull", "maxOf", "minOf", "maxOfOrNull",
			"minOfOrNull", "maxBy", "minBy", "maxByOrNull", "minByOrNull", "maxWith", "minWith", "maxWithOrNull",
			"minWithOrNull")

		/** The calls that walk a list in windows: chunks, windows of a size, or the pairs of a zip. */
		private val WINDOWS = setOf("chunked", "windowed", "zip")

		/** The calls whose lambda takes the running value and the element. */
		private val FOLDS = setOf("fold", "reduce", "reduceOrNull")

		/** The calls that find one element or value, or nothing, which `?:` gives a default for. */
		private val FINDS = setOf("first", "firstOrNull", "find", "last", "lastOrNull", "findLast", "single",
			"singleOrNull", "reduceOrNull") + EXTREMES

		/** The calls a chain ends in. */
		private val TERMINALS = STAGES + SORTS + FINDS + FOLDS + setOf("forEach", "toList", "toMutableList", "toSet",
			"toMutableSet", "toHashSet", "count", "sum", "sumOf", "average", "any", "all", "none", "joinToString",
			"groupBy", "associate", "associateBy", "associateWith", "partition", "toMap", "toMutableMap", "mapValues",
			"mapKeys") + WINDOWS

		/** A text's functions that walk its chars with a lambda, which a chain over the text ends in. */
		private val TEXT_TERMINALS = setOf("forEach", "count", "any", "all", "none", "first", "firstOrNull", "find",
			"last", "lastOrNull", "findLast")

		/** Every function a chain is made of, which [LibraryCalls] refuses outside a chain's place, with the reason. */
		val FUNCTIONS = TERMINALS

		/** The calls whose lambda's parameter is the element, which can name the loop's variable. */
		private val ELEMENT_LAMBDAS = STAGES - INDEXED + setOf("forEach", "count", "sumOf", "maxOf", "minOf",
			"maxOfOrNull", "minOfOrNull", "maxBy", "minBy", "maxByOrNull", "minByOrNull", "first", "firstOrNull",
			"find", "last", "lastOrNull", "findLast", "single", "singleOrNull", "any", "all", "none", "joinToString",
			"groupBy", "associate", "associateBy", "associateWith", "partition", "chunked", "windowed")

		/** The steps after which the elements are values that the lambdas made, rather than a map's entries. */
		private val MAKES_VALUES = setOf("map", "mapNotNull", "flatMap")

		/** The calls whose lambda's parameter is a map's entry, when the chain walks one. */
		private val ENTRY_LAMBDAS = ELEMENT_LAMBDAS - setOf("filterKeys", "filterValues") +
			setOf("mapValues", "mapKeys")

		/** The parameters of `joinToString` a chain's join takes. */
		private val JOIN_PARAMETERS = setOf("separator", "prefix", "postfix", "transform")
	}
}
