package org.adelheid.oneiro.kotlin

import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.UnaryOperator
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.psi.KtExpression

/**
 * The Kotlin library's members as Lua: lists, maps and sets are Lua tables, so their members become Lua's own
 * operations, such as a list's `size` as `#list`. A member these do not list is not supported yet.
 */
internal object LibraryCalls {

	/** A property of the Kotlin library: a collection's `size`. */
	fun property(body: BodyLowering, where: KtExpression, property: KaPropertySymbol,
		receiver: KtExpression?): Expression {
		val name = property.name.asString()
		val owner = property.callableId?.classId?.asFqNameString()
		if (name == "size" && owner in COLLECTIONS && receiver != null) {
			val type = with(body.unit.session) { receiver.expressionType }
			if (type != null && body.types.isList(type)) {
				return Expression.Unary(UnaryOperator.LENGTH, body.expression(receiver))
			}
		}
		throw Rejection.notYet(where, "Reading $owner.$name")
	}

	/** A call of a function of the Kotlin library. */
	fun call(where: KtExpression, function: KaNamedFunctionSymbol): Expression {
		val owner = function.callableId?.classId?.asFqNameString()
		throw Rejection.notYet(where, "Calling $owner.${function.name}")
	}

	private val COLLECTIONS = setOf("kotlin.collections.Collection", "kotlin.collections.List",
		"kotlin.collections.MutableList", "kotlin.collections.MutableCollection")
}
