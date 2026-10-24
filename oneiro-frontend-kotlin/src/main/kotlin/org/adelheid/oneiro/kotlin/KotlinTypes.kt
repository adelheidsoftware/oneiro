package org.adelheid.oneiro.kotlin

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.StandardClassIds

/**
 * Questions about Kotlin types that lowering asks, and the Lua Language Server type each one is in the annotations:
 * `Int` is `integer`, `List<String>` is `string[]`, and a nullable type ends in `?`, as in the Java front end.
 */
internal class KotlinTypes(private val session: KaSession) {

	fun isList(type: KaType): Boolean = isOrExtends(type, StandardClassIds.List)

	fun isString(type: KaType): Boolean = classId(type) == StandardClassIds.String

	fun isIntegral(type: KaType): Boolean = classId(type) in INTEGRAL

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
			else -> "any"
		}
		return if (isNullable(type) && base != "any") "$base?" else base
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
