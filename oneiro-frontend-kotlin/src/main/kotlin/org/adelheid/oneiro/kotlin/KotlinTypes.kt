package org.adelheid.oneiro.kotlin

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.StandardClassIds

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

	fun isString(type: KaType): Boolean = classId(type) == StandardClassIds.String

	fun isIntegral(type: KaType): Boolean = classId(type) in INTEGRAL

	fun isLong(type: KaType): Boolean = classId(type) == StandardClassIds.Long

	fun isFloating(type: KaType): Boolean = classId(type) == StandardClassIds.Double ||
		classId(type) == StandardClassIds.Float

	fun isBoolean(type: KaType): Boolean = classId(type) == StandardClassIds.Boolean

	/** The type's Lua Language Server name, with `?` when it can be null. */
	fun luaType(type: KaType): String {
		val base = when {
			isIntegral(type) -> "integer"
			isFloating(type) -> "number"
			isBoolean(type) -> "boolean"
			isString(type) || classId(type) == StandardClassIds.Char -> "string"
			isList(type) -> luaType(typeArgument(type, 0)).removeSuffix("?") + "[]"
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
		return if (symbol.classKind == KaClassKind.ENUM_CLASS) "string"
		else symbol.classId?.shortClassName?.asString() ?: "any"
	}

	fun isNullable(type: KaType): Boolean = with(session) {
		if (type is KaFlexibleType) type.upperBound.isMarkedNullable else type.isMarkedNullable
	}

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
	}
}
