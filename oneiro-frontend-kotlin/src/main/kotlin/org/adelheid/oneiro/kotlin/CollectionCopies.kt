package org.adelheid.oneiro.kotlin

import org.adelheid.oneiro.lowering.Helper
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.Statement
import org.jetbrains.kotlin.psi.KtExpression

/**
 * Copying one collection into another, as the Java front end does: what the source holds, a list's elements, a set's
 * keys, a map's values or entries, decides how. A list into a new list is `table.move`; into a new set or map, a
 * helper adds them; `addAll` and `putAll` are loops over the source.
 */
internal class CollectionCopies(private val body: BodyLowering) {

	private val unit = body.unit
	private val types = body.types

	/** What a collection holds, for copying it. */
	private enum class Contents { ELEMENTS, KEYS, VALUES, ENTRIES }

	/** What a source holds, and the expression of the Lua table that holds it: a map for its views. */
	private class Holding(val contents: Contents, val table: KtExpression)

	private fun holding(source: KtExpression): Holding {
		val type = with(unit.session) { source.expressionType }
		if (type != null && types.isSequence(type)) {
			return Holding(Contents.ELEMENTS, source)
		}
		// A map's keys and values are views of the map itself
		val view = body.library.mapView(source)
		when (view?.selector) {
			"keys" -> return Holding(Contents.KEYS, view.map)
			"values" -> return Holding(Contents.VALUES, view.map)
		}
		return when {
			type != null && types.isSet(type) -> Holding(Contents.KEYS, source)
			type != null && types.isMap(type) -> Holding(Contents.ENTRIES, source)
			else -> throw Rejection.notYet(source, "Copying this kind of collection")
		}
	}

	/** `ArrayList(other)`: a list's elements moved into a new table, or a set's keys or a map's values. */
	fun listCopy(where: KtExpression, source: KtExpression): Expression {
		val holding = holding(source)
		return when (holding.contents) {
			Contents.ELEMENTS -> {
				val list = body.library.stable(source)
				call("move", list, Expression.integer(1), LibraryCalls.length(list), Expression.integer(1),
					Expression.Table(listOf()))
			}
			Contents.KEYS -> helper(Helper.KEYS_OF, body.expression(holding.table))
			Contents.VALUES -> helper(Helper.VALUES_OF, body.expression(holding.table))
			Contents.ENTRIES -> throw Rejection.notYet(where, "A list of a map's entries")
		}
	}

	/** A new set or map filled from another collection, through the helper that fits what it holds. */
	fun filled(where: KtExpression, empty: Expression, target: LibraryCalls.Kind, source: KtExpression): Expression {
		val holding = holding(source)
		val helper = when {
			target == LibraryCalls.Kind.SET && holding.contents == Contents.ELEMENTS -> Helper.ADD_ELEMENTS
			target == LibraryCalls.Kind.SET && holding.contents == Contents.KEYS -> Helper.ADD_KEYS
			target == LibraryCalls.Kind.MAP && holding.contents == Contents.ENTRIES -> Helper.COPY_ENTRIES
			else -> throw Rejection.notYet(where, "Filling this collection from that one")
		}
		return helper(helper, empty, body.expression(holding.table))
	}

	/**
	 * `list.addAll(set)`, `set.addAll(list)` or `map.putAll(other)` as a loop over what the other collection holds; a
	 * list into a list is one `table.move`.
	 */
	fun addedAll(receiver: KtExpression, target: LibraryCalls.Kind, source: KtExpression): Statement {
		val holding = holding(source)
		val into = body.library.stable(receiver)
		if (target == LibraryCalls.Kind.SEQUENCE && holding.contents == Contents.ELEMENTS) {
			val list = body.library.stable(source)
			return Statement.CallStatement(call("move", list, Expression.integer(1), LibraryCalls.length(list),
				LibraryCalls.nextIndex(into), into))
		}
		val key = body.invent("key", source)
		val value = body.invent("value", source)
		val stored = when {
			target == LibraryCalls.Kind.SEQUENCE && holding.contents == Contents.KEYS -> appended(into, key)
			target == LibraryCalls.Kind.SEQUENCE && holding.contents == Contents.VALUES -> appended(into, value)
			target == LibraryCalls.Kind.SET && holding.contents == Contents.ELEMENTS -> included(into, value)
			target == LibraryCalls.Kind.SET && holding.contents == Contents.KEYS -> included(into, key)
			target == LibraryCalls.Kind.MAP && holding.contents == Contents.ENTRIES ->
				Statement.assignment(Expression.Index(into, Expression.name(key)), Expression.name(value))
			else -> throw Rejection.notYet(source, "Adding this collection's contents to that one")
		}
		val names = when (holding.contents) {
			Contents.ELEMENTS, Contents.VALUES -> listOf("_", value)
			Contents.KEYS -> listOf(key)
			Contents.ENTRIES -> listOf(key, value)
		}
		val walk = if (holding.contents == Contents.ELEMENTS) "ipairs" else "pairs"
		return Statement.GenericFor(names, listOf(Expression.call(Expression.name(walk),
			body.expression(holding.table))), Block.of(stored))
	}

	/** `list[#list + 1] = element`. */
	private fun appended(list: Expression, element: String): Statement =
		Statement.assignment(Expression.Index(list, LibraryCalls.nextIndex(list)), Expression.name(element))

	/** `set[element] = true`. */
	private fun included(set: Expression, element: String): Statement =
		Statement.assignment(Expression.Index(set, Expression.name(element)), Expression.BooleanLiteral(true))

	private fun call(name: String, vararg arguments: Expression): Expression {
		unit.useGlobal("table")
		return Expression.Call(Expression.field(Expression.name("table"), name), arguments.toList())
	}

	private fun helper(helper: Helper, vararg arguments: Expression): Expression {
		unit.use(helper)
		return helper.invoke(*arguments)
	}
}
