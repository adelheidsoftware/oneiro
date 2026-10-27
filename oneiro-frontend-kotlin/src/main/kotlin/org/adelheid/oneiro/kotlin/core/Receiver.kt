package org.adelheid.oneiro.kotlin.core

import org.adelheid.oneiro.lua.Expression
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtExpression

/**
 * The value a member of the Kotlin library is read on: the expression written before the dot, `names.size`, or the
 * receiver a member named without one is read on, `size` inside `names.apply { ... }` or an extension function of a
 * list.
 */
internal sealed interface Receiver {

	/** The receiver's type. */
	fun type(session: KaSession): KaType?

	/** Written before the dot, which is lowered wherever the Lua needs it, as any expression is. */
	class Written(val expression: KtExpression) : Receiver {

		override fun type(session: KaSession): KaType? = with(session) { expression.expressionType }
	}

	/**
	 * Left unnamed: the `this` of the scope function or extension function around the code, which already has a Lua
	 * name, so the Lua can read it as often as it needs.
	 *
	 * @property lua that name
	 */
	class Unnamed(val lua: Expression, private val type: KaType?) : Receiver {

		override fun type(session: KaSession): KaType? = type
	}
}
