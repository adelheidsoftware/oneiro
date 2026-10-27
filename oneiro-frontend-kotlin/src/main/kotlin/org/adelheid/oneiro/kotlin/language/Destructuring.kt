package org.adelheid.oneiro.kotlin.language

import com.intellij.psi.PsiElement
import org.adelheid.oneiro.kotlin.core.BodyLowering
import org.adelheid.oneiro.kotlin.core.Rejection
import org.adelheid.oneiro.kotlin.library.LibraryCalls
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.Statement
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry

/**
 * Kotlin's destructuring of a value into the parts it names: `val (name, level) = pair` is
 * `local name, level = pair.first, pair.second`. A `Pair` or a `Triple` is a table of its parts, `first`, `second` and
 * `third`; a data class's value has its properties, in the order its constructor takes them; and a list's parts are
 * its first elements. A part named `_` is not read.
 *
 * A value that is a name or a property read is read as it is; a pair made in place gives its parts directly,
 * `val (low, high) = lowest to highest` being `local low, high = lowest, highest`; and any other value goes into a
 * local of its own first, named after its type, `local pair = Ranges.span(values)`.
 */
internal class Destructuring(private val body: BodyLowering) {

	private val unit = body.unit

	/** `val (name, level) = value`: one local for each part the declaration names. */
	fun local(declaration: KtDestructuringDeclaration): List<Statement> {
		val initializer = declaration.initializer ?: throw Rejection(declaration, "This declaration has no value.")
		val named = declaration.entries.withIndex().filter { it.value.name != "_" }
		if (named.isEmpty()) {
			return body.expressionStatement(initializer)
		}
		val names = named.map { unit.localName(it.value, it.value.name!!) }
		body.library.pairParts(initializer)?.let { parts ->
			return listOf(Statement.Local(names, named.map { body.expression(parts[it.index]) }))
		}
		val type = with(unit.session) { initializer.expressionType }
		if (body.isStable(initializer)) {
			val value = body.expression(initializer)
			return listOf(Statement.Local(names, named.map { part(value, type, it.index, it.value) }))
		}
		val holder = body.invent(holderName(type), declaration)
		val holding = body.localValue(holder, initializer)
		return holding + Statement.Local(names, named.map { part(Expression.name(holder), type, it.index, it.value) })
	}

	/**
	 * The locals a loop's destructured element is read into, at the start of the loop's body, from the element's name:
	 * `for ((name, level) in pairs)` walks each pair, and reads `local name, level = pair.first, pair.second`.
	 *
	 * @param element the loop's variable, which holds the element
	 * @param type the type of the elements
	 */
	fun unpacked(entries: List<KtDestructuringDeclarationEntry>, element: String, type: KaType?, code: Block): Block {
		val named = entries.withIndex().filter { it.value.name != "_" }
		if (named.isEmpty()) {
			return code
		}
		val names = named.map { unit.localName(it.value, it.value.name!!) }
		val values = named.map { part(Expression.name(element), type, it.index, it.value) }
		return Block(listOf(Block.Entry.of(Statement.Local(names, values))) + code.entries(), code.closingComments())
	}

	/**
	 * The part of a value at a position, as `componentN()` gives it: a pair's `first`, a data class's property, or a
	 * list's element.
	 */
	private fun part(value: Expression, type: KaType?, index: Int, where: PsiElement): Expression =
		with(unit.session) {
			val symbol = type?.expandedSymbol
			when {
				type != null && body.types.isPair(type) -> Expression.field(value, PAIR_PARTS[index])
				body.library.kind(type) == LibraryCalls.Kind.SEQUENCE ->
					Expression.Index(value, Expression.integer(index + 1L))
				symbol != null && unit.isPluginType(symbol) && (symbol.psi as? KtClass)?.isData() == true -> {
					val constructor = symbol.declaredMemberScope.constructors.first { it.isPrimary }
					val property = constructor.valueParameters[index].generatedPrimaryConstructorProperty
						?: throw IllegalStateException("a data class's parameter without a property: ${symbol.classId}")
					Expression.field(value, unit.memberName(property))
				}
				else -> throw Rejection.notYet(where, "Destructuring this kind of value")
			}
		}

	/**
	 * The name of the local or loop variable that holds a value being destructured: `pair` or `triple`, a data class's
	 * own name, `drop` for a `Drop`, or `list` and `value`.
	 */
	fun holderName(type: KaType?): String = with(unit.session) {
		when {
			type == null -> "value"
			body.types.isPair(type) -> type.expandedSymbol!!.classId!!.shortClassName.asString().lowercase()
			body.library.kind(type) == LibraryCalls.Kind.SEQUENCE -> "list"
			else -> type.expandedSymbol?.takeIf(unit::isPluginType)?.classId?.shortClassName?.asString()
				?.replaceFirstChar { it.lowercaseChar() } ?: "value"
		}
	}

	private companion object {

		/** The parts of a `Pair` and a `Triple`, by their position. */
		val PAIR_PARTS = listOf("first", "second", "third")
	}
}
