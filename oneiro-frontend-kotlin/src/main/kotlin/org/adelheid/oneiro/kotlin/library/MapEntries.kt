package org.adelheid.oneiro.kotlin.library

import org.adelheid.oneiro.kotlin.core.BodyLowering
import org.adelheid.oneiro.kotlin.core.Receiver
import org.adelheid.oneiro.kotlin.core.Rejection
import org.adelheid.oneiro.lua.BinaryOperator
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.Statement
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression

/**
 * A chain of collection functions over a map's entries, as [CollectionLoops] fuses it into one loop. Lua has no entry
 * to pass around: the loop walks the map's keys and values as two variables, and each part of an entry the chain
 * reads is one of them. A lambda's parameter that is the entry stands for the two, read as `entry.key` and
 * `entry.value` or destructured, and a find gives the part of the entry it reads.
 *
 * ```kotlin
 * return stock.maxByOrNull { it.value }?.key
 * ```
 *
 * ```lua
 * local best
 * local most
 * for key, value in pairs(stock) do
 *     if most == nil or most < value then
 *         most = value
 *         best = key
 *     end
 * end
 * return best
 * ```
 *
 * @param map the map whose entries the loop walks
 * @param lambdas the chain's lambdas whose parameter is the entry, which can name the loop's variables
 */
internal class MapEntries(
	private val body: BodyLowering,
	private val fusion: CollectionLoops.Fusion,
	map: Receiver,
	lambdas: List<KtLambdaExpression>,
) {

	private val unit = body.unit

	/**
	 * The loop's name for the key: the first a lambda destructures the entry into, other than `_`, so that the lambdas'
	 * code reads as written; otherwise key.
	 */
	val key: String

	/** The loop's name for the value, chosen as the key's is. */
	val value: String

	private val pairs: Expression

	init {
		val destructured = lambdas.mapNotNull { lambda ->
			lambda.functionLiteral.valueParameters.singleOrNull()?.destructuringDeclaration?.entries
				?.takeIf { it.size == 2 }
		}
		val where = fusion.chain.terminal.call
		key = destructured.firstNotNullOfOrNull { named(it[0]) } ?: fusion.inner("key", where)
		value = destructured.firstNotNullOfOrNull { named(it[1]) } ?: fusion.inner("value", where)
		pairs = Expression.call(Expression.name("pairs"), body.expression(map))
	}

	/** The Lua name of a destructured part of an entry, or null for `_`, which names nothing. */
	private fun named(part: KtDestructuringDeclarationEntry): String? =
		part.name?.takeIf { it != "_" }?.let { unit.localName(part, it) }

	/** The loop over the entries, `for key, value in pairs(map) do`, without the parts its code never reads. */
	fun loop(code: Block): Statement =
		Statement.GenericFor(LibraryCalls.entryVariables(key, value, code), listOf(pairs), code)

	/**
	 * Makes a lambda's parameter that is the entry stand for the loop's key and value: the names it destructures the
	 * entry into, or `entry.key` and `entry.value`.
	 */
	fun bind(lambda: KtLambdaExpression) {
		val literal = lambda.functionLiteral
		val parameter = literal.valueParameters.singleOrNull()
		val parts = parameter?.destructuringDeclaration?.entries
		if (parts == null) {
			unit.aliasEntry(parameter ?: literal, key, value)
			return
		}
		parts.getOrNull(0)?.let { unit.alias(it, key) }
		parts.getOrNull(1)?.let { unit.alias(it, value) }
	}

	/** `filterKeys { ... }` and `filterValues { ... }`: a test of the entry's key or value. */
	fun test(stage: CollectionLoops.Operation, predicate: KtExpression): Expression {
		val part = if (stage.name == "filterKeys") key else value
		if (predicate !is KtLambdaExpression) {
			return fusion.applied(predicate, Expression.name(part))
		}
		return fusion.lambdaValue(predicate, {
			unit.alias(body.lambdas.parameter(predicate).declaration, part)
		})
	}

	/** What a find over the entries gives: the part of the entry the chain reads, `?.key` or `?.value`. */
	fun found(): Expression {
		val chain = fusion.chain
		val part = chain.part ?: throw Rejection(chain.terminal.call, ENTRY_PARTS)
		return Expression.name(if (part == "key") key else value)
	}

	/** A map's `filter { ... }` and its kin, which give a map, as Kotlin's do: each entry that passes, stored. */
	fun filtered(init: MutableList<Statement>, after: MutableList<Statement>): Block =
		fusion.stored(Expression.name(key), Expression.name(value), init, after)

	/** `mapValues { ... }` and `mapKeys { ... }`: a map of each new value under its key, or value under its new key. */
	fun transformed(terminal: CollectionLoops.Operation, function: KtExpression, init: MutableList<Statement>,
		after: MutableList<Statement>): Block {
		val made = fusion.mapped(function)
		return if (terminal.name == "mapValues") {
			fusion.stored(Expression.name(key), made, init, after)
		} else {
			fusion.stored(made, Expression.name(value), init, after)
		}
	}

	/**
	 * `maxBy { ... }` and its kin, which give a part of the best entry, `?.key` or `?.value`: that part, and the best
	 * entry's selected value, which the next entries compare with.
	 */
	fun extreme(terminal: CollectionLoops.Operation, selector: KtExpression, max: Boolean,
		init: MutableList<Statement>, after: MutableList<Statement>): Block {
		val part = found()
		var selected = fusion.mapped(selector)
		if (selected !is Expression.Name) {
			selected = Expression.name(fusion.bound("selected", selected, terminal.call))
		}
		val best = fusion.accumulator("best")
		val record = fusion.declared(if (max) "most" else "least", terminal.call)
		init += Statement.Local(listOf(best), listOf())
		init += Statement.Local(listOf(record), listOf())
		val unset = Expression.binary(Expression.name(record), BinaryOperator.EQUAL, Expression.Nil())
		val better = if (max) Expression.binary(Expression.name(record), BinaryOperator.LESS, selected)
		else Expression.binary(selected, BinaryOperator.LESS, Expression.name(record))
		fusion.deliver(after, Expression.name(best))
		return Block.of(Statement.ifThen(Expression.binary(unset, BinaryOperator.OR, better),
			Block.of(Statement.assignment(Expression.name(record), selected),
				Statement.assignment(Expression.name(best), part))))
	}

	companion object {

		/** Why a map's entry can be read only as its key and its value. */
		const val ENTRY_PARTS = "In Lua a map's entry is its key and its value, which a loop walks, not a value of" +
			" its own; read entry.key and entry.value, or destructure it, (key, value) ->, and take .key or .value of" +
			" an entry a chain finds."
	}
}
