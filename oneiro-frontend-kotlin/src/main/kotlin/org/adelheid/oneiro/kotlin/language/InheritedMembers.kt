package org.adelheid.oneiro.kotlin.language

import com.intellij.psi.PsiElement
import org.adelheid.oneiro.kotlin.core.FileLowering
import org.adelheid.oneiro.kotlin.core.Member
import org.adelheid.oneiro.lua.BinaryOperator
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Comment
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.FunctionBody
import org.adelheid.oneiro.lua.Statement
import org.adelheid.oneiro.lua.Trivia
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * What a class takes from the interfaces it implements, which Lua's metatables do not give it: the default functions
 * of the plugin's interfaces that it does not override, copied into its table, and for the library's `Comparable`, its
 * function types and functional interfaces, the metamethods that let `<`, `table.sort` and a call use its objects.
 */
internal class InheritedMembers(private val unit: FileLowering) {

	/**
	 * The default functions a class takes from the plugin's interfaces without overriding them, which Lua copies into
	 * its table, `Herbal.describe = Describable.describe`; one its superclass took already comes through that.
	 */
	fun defaults(declaration: KtClass, table: String): List<Member> = with(unit.session) {
		val symbol = declaration.classSymbol ?: return listOf()
		val copies = mutableListOf<Member>()
		for (implemented in pluginInterfaces(symbol)) {
			val source = implemented.psi as? KtClass ?: continue
			for (function in source.declarations.filterIsInstance<KtNamedFunction>()) {
				val name = function.name ?: continue
				if (!function.hasBody() || overrides(declaration, name) || superclassTakes(symbol, implemented)) {
					continue
				}
				val luaName = unit.memberName(function)
				val copy = Statement.assignment(Expression.field(Expression.name(table), luaName),
					Expression.field(unit.moduleReference(implemented), luaName))
				val uses: Set<PsiElement> = if (source.containingFile == unit.file) setOf(source, function) else setOf()
				copies += Member(listOf(), false, null, listOf(Block.Entry.of(copy)), uses = uses)
			}
		}
		copies
	}

	/** Whether the class declares a function of the name, which replaces an interface's. */
	private fun overrides(declaration: KtClass, name: String): Boolean =
		declaration.declarations.any { it is KtNamedFunction && it.name == name }

	/** Whether a superclass of the plugin's implements the interface, and so copied its defaults already. */
	private fun superclassTakes(type: KaClassSymbol, implemented: KaClassSymbol): Boolean = with(unit.session) {
		val superclass = type.superTypes.mapNotNull { it.expandedSymbol }
			.firstOrNull { it.classKind == KaClassKind.CLASS && unit.isPluginType(it) } ?: return false
		implemented in pluginInterfaces(superclass) || superclassTakes(superclass, implemented)
	}

	/** The plugin's interfaces a class implements, directly or through others, each once, nearest first. */
	private fun pluginInterfaces(type: KaClassSymbol): List<KaClassSymbol> = with(unit.session) {
		val found = mutableListOf<KaClassSymbol>()
		val pending = ArrayDeque(type.superTypes.mapNotNull { it.expandedSymbol })
		while (pending.isNotEmpty()) {
			val next = pending.removeFirst()
			if (next.classKind == KaClassKind.INTERFACE && unit.isPluginType(next) && next !in found) {
				found += next
				pending.addAll(next.superTypes.mapNotNull { it.expandedSymbol })
			}
		}
		found
	}

	/**
	 * What a class needs for the library interfaces it implements: `__lt` for `Comparable`, so that `table.sort` and
	 * `<` order its objects by `compareTo`, and `__call` for a function type or a functional interface, so that an
	 * object stands where Lua expects a function. Lua looks metamethods up in the class's own table, so each subclass
	 * has its own.
	 */
	fun metamethods(declaration: KtClass, table: String): List<Member> = with(unit.session) {
		val symbol = declaration.classSymbol ?: return listOf()
		val supertypes = symbol.defaultType.allSupertypes.mapNotNull { it.expandedSymbol }
		val metamethods = mutableListOf<Member>()
		if (supertypes.any { it.classId == StandardClassIds.Comparable }) {
			val lessThan = Expression.binary(Expression.methodCall(Expression.name("a"), "compareTo",
				Expression.name("b")), BinaryOperator.LESS, Expression.integer(0))
			val function = Statement.FunctionDeclaration(listOf(table, "__lt"), null,
				FunctionBody.of(listOf("a", "b"), Block.of(Statement.Return(listOf(lessThan)))))
			metamethods += metamethod(function, "Orders by compareTo, as Kotlin's natural order does")
		}
		val functional = supertypes.firstOrNull { functionalMethod(it) != null }
		if (functional != null) {
			val called = functionalMethod(functional)!!
			val call = Expression.methodCall(Expression.name("self"), called, Expression.Vararg())
			val function = Statement.FunctionDeclaration(listOf(table), "__call",
				FunctionBody(listOf(), true, Block.of(Statement.Return(listOf(call)))))
			val comment = if (unit.isPluginType(functional)) {
				"Called as a function, as every ${functional.classId?.shortClassName} is in Lua"
			} else {
				"Called as a function, as library code calls $called"
			}
			metamethods += metamethod(function, comment)
		}
		metamethods
	}

	private fun metamethod(function: Statement, comment: String): Member =
		Member(listOf(), true, null, listOf(Block.Entry(function, Trivia(true, listOf(Comment.line(comment)), null),
			null)))

	/**
	 * The function a call of an object runs for a type meant as a function: `invoke` for a function type, `accept`
	 * for a `Consumer`, and the one abstract function of a fun interface of the plugin's, whose values are Lua
	 * functions; null for any other type.
	 */
	private fun functionalMethod(type: KaClassSymbol): String? = with(unit.session) {
		val id = type.classId?.asFqNameString() ?: return null
		if (FUNCTION_TYPE.matches(id)) {
			return "invoke"
		}
		if (unit.isPluginType(type)) {
			if ((type.psi as? KtClass)?.hasModifier(KtTokens.FUN_KEYWORD) != true) {
				return null
			}
			return type.declaredMemberScope.callables.filterIsInstance<KaNamedFunctionSymbol>()
				.firstOrNull { it.modality == KaSymbolModality.ABSTRACT }?.let(unit::memberName)
		}
		if (type.classKind != KaClassKind.INTERFACE || !id.startsWith("java.")) {
			return null
		}
		// Interfaces meant as functions say so, as Runnable and Consumer do; Comparable and AutoCloseable do not
		val functional = type.annotations.any { it.classId?.asFqNameString() == "java.lang.FunctionalInterface" }
		if (!functional) {
			return null
		}
		type.declaredMemberScope.callables.filterIsInstance<KaNamedFunctionSymbol>()
			.firstOrNull { it.modality == KaSymbolModality.ABSTRACT }?.name?.asString()
	}

	private companion object {
		val FUNCTION_TYPE = Regex("kotlin\\.(jvm\\.functions\\.)?Function\\d+")
	}
}
