package org.adelheid.oneiro.kotlin

import org.adelheid.oneiro.lowering.Helper
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.FunctionBody
import org.adelheid.oneiro.lua.Statement
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry

/**
 * Kotlin's object expressions and local classes in Lua, as the Java front end lowers anonymous and local classes. An
 * object that implements a function type or a functional interface with its one function is a Lua function, where
 * Kotlin means one; any other is a table of its properties and functions, which take the object first, and one that
 * extends a class of the plugin's gives that class's new object its members, through a helper. A class declared inside
 * a function is a local table there, whose functions read the function's locals.
 */
internal class ObjectExpressions(private val body: BodyLowering) {

	private val unit = body.unit

	fun literal(literal: KtObjectLiteralExpression): Expression {
		val declaration = literal.objectDeclaration
		val functions = declaration.declarations.filterIsInstance<KtNamedFunction>()
		val properties = declaration.declarations.filterIsInstance<KtProperty>()
		declaration.declarations.firstOrNull { it !is KtNamedFunction && it !is KtProperty }?.let {
			throw Rejection.notYet(it, "This member of an object expression")
		}
		val superclass = superclass(declaration)
		if (isFunction(declaration)) {
			return function(functions.single())
		}
		val inner = BodyLowering(unit, declaration, true)
		val fields = properties.map { property ->
			val value = property.initializer ?: throw Rejection.notYet(property, "A property without a value")
			Expression.TableField.Named(unit.memberName(property), inner.expression(value))
		} + functions.map { function ->
			val parameters = listOf("self") + function.valueParameters.map { unit.localName(it, it.name!!) }
			val code = Functions(unit).functionBlock(function, inner)
			Expression.TableField.Named(unit.memberName(function),
				Expression.FunctionExpression(FunctionBody.of(parameters, code)))
		}
		val members = Expression.Table(fields)
		if (superclass == null) {
			return members
		}
		val call = declaration.superTypeListEntries.filterIsInstance<KtSuperTypeCallEntry>().first()
		val arguments = call.valueArguments.map {
			body.expression(it.getArgumentExpression() ?: throw Rejection(call, "This argument is empty."))
		}
		unit.use(Helper.WITH_MEMBERS)
		val created = Expression.Call(Expression.field(unit.moduleReference(superclass), "new"), arguments)
		return Helper.WITH_MEMBERS.invoke(created, members)
	}

	/**
	 * Whether an object expression is a Lua function: it implements a function type or a functional interface with
	 * its one function, and holds nothing.
	 */
	fun isFunction(declaration: KtClassOrObject): Boolean {
		val members = declaration.declarations
		return members.size == 1 && members.single() is KtNamedFunction && superclass(declaration) == null &&
			isFunctional(declaration)
	}

	/** The object's one function, as the Lua function a function type or functional interface stands for. */
	private fun function(function: KtNamedFunction): Expression {
		val parameters = function.valueParameters.map { unit.localName(it, it.name!!) }
		return Expression.FunctionExpression(FunctionBody.of(parameters,
			Functions(unit).functionBlock(function, body.nested())))
	}

	/**
	 * Whether an object implements a type meant as a function: a function type, a fun interface of the plugin's, or a
	 * functional interface of the library, such as `Runnable` or `Comparator`.
	 */
	private fun isFunctional(declaration: KtClassOrObject): Boolean = with(unit.session) {
		val supertypes = declaration.classSymbol?.superTypes?.mapNotNull { it.expandedSymbol } ?: return false
		supertypes.isNotEmpty() && supertypes.all { type ->
			val id = type.classId?.asFqNameString().orEmpty()
			FUNCTION_TYPE.matches(id) || (type.psi as? KtClass)?.hasModifier(KtTokens.FUN_KEYWORD) == true ||
				!unit.isPluginType(type) && type.classKind == KaClassKind.INTERFACE &&
				type.annotations.any { it.classId?.asFqNameString() == "java.lang.FunctionalInterface" }
		}
	}

	/** The class of the plugin's that an object or class extends, or null when it extends none. */
	private fun superclass(declaration: KtClassOrObject): KaClassSymbol? = with(unit.session) {
		declaration.classSymbol?.superTypes?.mapNotNull { it.expandedSymbol }
			?.firstOrNull { it.classKind == KaClassKind.CLASS && unit.isPluginType(it) }
	}

	/**
	 * A class declared inside a function, as statements of that function: its table, then its members, which read the
	 * function's locals as upvalues. Its functions are fields of the table, so a private one, which Lua would need
	 * declared before the functions that call it, is rejected.
	 */
	fun localClass(declaration: KtClass): List<Statement> {
		if (declaration.isInterface() || declaration.isEnum() || declaration.isData()) {
			throw Rejection(declaration, "Lua keeps one table per name in a function; declare this beside the class,"
				+ " not inside a function.")
		}
		for (member in declaration.declarations) {
			val private = member is KtNamedFunction && member.hasModifier(KtTokens.PRIVATE_KEYWORD)
			if (private || member is KtClassOrObject) {
				throw Rejection(member, "A class inside a function is a local table whose members are its fields; make"
					+ " this a member its functions call on the object, or move the class beside its outer one.")
			}
		}
		val name = declaration.name!!
		val table = Expression.name(name)
		val members = ClassLowering(unit).members(declaration)
		return listOf(Statement.Local(listOf(name), listOf(Expression.Table(listOf()))),
			Statement.assignment(Expression.field(table, "__index"), table)) +
			members.flatMap { member -> member.entries().map(Block.Entry::statement) }
	}

	private companion object {
		val FUNCTION_TYPE = Regex("kotlin\\.(jvm\\.functions\\.)?Function\\d+")
	}
}
