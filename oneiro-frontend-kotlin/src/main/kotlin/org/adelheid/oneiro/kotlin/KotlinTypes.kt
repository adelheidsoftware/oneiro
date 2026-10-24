package org.adelheid.oneiro.kotlin

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtClass

/**
 * Questions about Kotlin types that lowering asks, and the Lua Language Server type each one is in the annotations:
 * `Int` is `integer`, `List<String>` is `string[]`, an RS3 type is its Lua name, an enum of the plugin's is `string`,
 * another class of the plugin's its name, and a nullable type ends in `?`, as in the Java front end.
 *
 * @param isPluginType whether a class is the plugin's own
 */
internal class KotlinTypes(
	private val session: KaSession,
	private val annotations: Annotations,
	private val isPluginType: (KaClassSymbol) -> Boolean,
) {

	fun isList(type: KaType): Boolean = isOrExtends(type, StandardClassIds.List)

	/** `Array<T>`, or an array of a primitive type such as `IntArray`. */
	fun isArray(type: KaType): Boolean = classId(type).let { it == StandardClassIds.Array || it in PRIMITIVE_ARRAYS }

	/** Whether Lua indexes the value by position from 1: a list or an array, both Lua sequences. */
	fun isSequence(type: KaType): Boolean = isList(type) || isArray(type)

	/** The class of what a list or an array holds: `Int` for an `IntArray` or a `List<Int>`; null for another type. */
	fun elementClass(type: KaType): ClassId? {
		val classId = classId(type)
		StandardClassIds.elementTypeByPrimitiveArrayType[classId]?.let { return it }
		return if (isSequence(type)) classId(typeArgument(type, 0)) else null
	}

	fun isMap(type: KaType): Boolean = isOrExtends(type, StandardClassIds.Map)

	fun isSet(type: KaType): Boolean = isOrExtends(type, StandardClassIds.Set)

	fun isString(type: KaType): Boolean = classId(type) == StandardClassIds.String

	fun isIntegral(type: KaType): Boolean = classId(type) in INTEGRAL

	fun isLong(type: KaType): Boolean = classId(type) == StandardClassIds.Long

	fun isFloating(type: KaType): Boolean = classId(type) == StandardClassIds.Double ||
		classId(type) == StandardClassIds.Float

	fun isBoolean(type: KaType): Boolean = classId(type) == StandardClassIds.Boolean

	/** The type's Lua Language Server name, with `?` when it can be null. */
	fun luaType(type: KaType): String {
		val base = when {
			plain(type) is KaFunctionType -> "function"
			isIntegral(type) -> "integer"
			isFloating(type) -> "number"
			isBoolean(type) -> "boolean"
			isString(type) || classId(type) == StandardClassIds.Char -> "string"
			classId(type) in PRIMITIVE_ARRAYS -> primitiveElement(classId(type)!!) + "[]"
			isList(type) || isArray(type) -> element(type, 0) + "[]"
			isSet(type) -> "table<" + element(type, 0) + ", true>"
			isMap(type) -> "table<" + element(type, 0) + ", " + element(type, 1) + ">"
			else -> classType(type)
		}
		return if (isNullable(type) && base != "any") "$base?" else base
	}

	/** An RS3 type's Lua name, `string` for an enum of the plugin's, another class of the plugin's its name. */
	private fun classType(type: KaType): String = with(session) {
		val symbol = plain(type).expandedSymbol ?: return "any"
		annotations.value(symbol, Annotations.LUA_TYPE)?.let { return it }
		annotations.value(symbol, Annotations.LUA_GLOBAL)?.takeIf { it.isNotEmpty() }?.let { return it }
		if (!isPluginType(symbol)) {
			return "any"
		}
		return when {
			symbol.classKind == KaClassKind.ENUM_CLASS -> "string"
			(symbol.psi as? KtClass)?.isPlainData() == true -> "table"
			// A fun interface's values are Lua functions
			(symbol.psi as? KtClass)?.hasModifier(KtTokens.FUN_KEYWORD) == true -> "function"
			else -> symbol.classId?.shortClassName?.asString() ?: "any"
		}
	}

	fun isNullable(type: KaType): Boolean = with(session) {
		if (type is KaFlexibleType) type.upperBound.isMarkedNullable else type.isMarkedNullable
	}

	/** A collection's element, key or value type, as Lua writes it inside the collection's: never with `?`. */
	private fun element(type: KaType, index: Int): String = luaType(typeArgument(type, index)).removeSuffix("?")

	/** The Lua name of what an array of a primitive type holds: `IntArray` holds integers. */
	private fun primitiveElement(array: ClassId): String =
		when (StandardClassIds.elementTypeByPrimitiveArrayType[array]) {
			StandardClassIds.Double, StandardClassIds.Float -> "number"
			StandardClassIds.Boolean -> "boolean"
			StandardClassIds.Char -> "string"
			else -> "integer"
		}

	/** A type argument of a collection's type, `String` of `List<String>`; null for a star projection. */
	fun typeArgumentOf(type: KaType, index: Int): KaType? =
		(plain(type) as? KaClassType)?.typeArguments?.getOrNull(index)?.type

	private fun typeArgument(type: KaType, index: Int): KaType =
		(plain(type) as KaClassType).typeArguments[index].type
			?: throw IllegalStateException("a star projection: $type")

	private fun classId(type: KaType): ClassId? = (plain(type) as? KaClassType)?.classId

	/** A type Java hands over without saying whether it can be null, as the type it is either way. */
	private fun plain(type: KaType): KaType = if (type is KaFlexibleType) type.lowerBound else type

	private fun isOrExtends(type: KaType, classId: ClassId): Boolean = with(session) {
		classId(type) == classId || plain(type).allSupertypes.any { (it as? KaClassType)?.classId == classId }
	}

	private companion object {
		val INTEGRAL = setOf(StandardClassIds.Int, StandardClassIds.Long, StandardClassIds.Short, StandardClassIds.Byte)
		val PRIMITIVE_ARRAYS = StandardClassIds.elementTypeByPrimitiveArrayType.keys
	}
}
