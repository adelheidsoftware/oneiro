package org.adelheid.oneiro.kotlin

import com.intellij.psi.PsiElement
import org.adelheid.oneiro.lowering.Helper
import org.adelheid.oneiro.lua.BinaryOperator
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.UnaryOperator
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtIsExpression

/**
 * Kotlin's type tests in Lua: `x is T`, and the test an `is` condition of `when` makes. A class of the plugin's is its
 * objects' metatable, `getmetatable(x) == Square`, through the helper `isInstance` for a class that others extend; a
 * string, number or boolean is Lua's own type; a simple enum's constant is one of its strings, `Mode[x] == x`. Kotlin
 * casts the value smartly after the test, which in Lua needs nothing.
 */
internal class TypeTests(private val body: BodyLowering) {

	private val unit = body.unit

	/** `x is T` and `x !is T`. */
	fun isExpression(test: KtIsExpression): Expression = with(unit.session) {
		val type = test.typeReference?.type ?: throw Rejection(test, "This type test names no type.")
		val tested = typeTest(body.expression(test.leftHandSide), type, test)
		if (test.isNegated) Expression.Unary(UnaryOperator.NOT, tested) else tested
	}

	/**
	 * `x as T`, the value itself: Lua checks no casts, so a value of another type goes on where Kotlin would throw. And
	 * `x as? T`, the value when it passes the type's test and nil otherwise, `test and x or nil`, which reads the value
	 * twice, so it must be a name or a property read, and must not be a `Boolean`, whose false `and` would turn to nil.
	 */
	fun cast(cast: KtBinaryExpressionWithTypeRHS): Expression = with(unit.session) {
		val value = cast.left
		if (cast.operationReference.getReferencedNameElementType() != KtTokens.AS_SAFE) {
			return body.expression(value)
		}
		val type = cast.right?.type ?: throw Rejection(cast, "This cast names no type.")
		if (!body.isStable(value)) {
			throw Rejection(cast, "Lua reads this value twice, to test its type and to give it; store it in a local"
				+ " first.")
		}
		if (unit.types.isBoolean(type)) {
			throw Rejection(cast, "Lua's and would turn a false into nil here; test the value with is instead.")
		}
		val lowered = body.expression(value)
		val passed = Expression.binary(typeTest(lowered, type, cast), BinaryOperator.AND, lowered)
		Expression.binary(passed, BinaryOperator.OR, Expression.Nil())
	}

	/** A test that a value is of a type, as Kotlin's `is`. */
	fun typeTest(value: Expression, type: KaType, where: PsiElement): Expression = with(unit.session) {
		val types = unit.types
		val classId = type.expandedSymbol?.classId
		when {
			types.isString(type) || classId == StandardClassIds.Char -> return luaType(value, "string")
			types.isBoolean(type) -> return luaType(value, "boolean")
			types.isIntegral(type) || types.isFloating(type) -> {
				unit.useGlobal("math")
				val kind = Expression.call(Expression.field(Expression.name("math"), "type"), value)
				return Expression.binary(kind, BinaryOperator.EQUAL,
					Expression.string(if (types.isIntegral(type)) "integer" else "float"))
			}
			classId == StandardClassIds.Number -> return luaType(value, "number")
		}
		val symbol = type.expandedSymbol
		if (symbol == null || !unit.isPluginType(symbol)) {
			throw Rejection(where, "Lua cannot tell the client's objects, or lists, maps and other tables, apart by"
				+ " type; test what the value holds instead.")
		}
		val declaration = symbol.psi as? KtClass
		when {
			symbol.classKind == KaClassKind.ENUM_CLASS ->
				return Expression.binary(Expression.Index(unit.moduleReference(symbol), value), BinaryOperator.EQUAL,
					value)
			declaration?.isPlainData() == true -> throw Rejection(where, "A data class of properties alone is a plain"
				+ " Lua table, which Lua cannot tell from other tables; give the class a function, which makes its"
				+ " values objects of a class, or test what the value holds instead.")
			declaration?.hasModifier(KtTokens.FUN_KEYWORD) == true -> return luaType(value, "function")
			symbol.classKind == KaClassKind.INTERFACE -> throw Rejection.notYet(where, "A type test of an interface")
		}
		val table = unit.moduleReference(symbol)
		if (symbol.classId?.asFqNameString() in unit.compilation.extended) {
			unit.use(Helper.IS_INSTANCE)
			return Helper.IS_INSTANCE.invoke(value, table)
		}
		Expression.binary(Expression.call(Expression.name("getmetatable"), value), BinaryOperator.EQUAL, table)
	}

	private fun luaType(value: Expression, name: String): Expression {
		val type = Expression.call(Expression.name("type"), value)
		return Expression.binary(type, BinaryOperator.EQUAL, Expression.string(name))
	}
}
