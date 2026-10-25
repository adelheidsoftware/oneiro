package org.adelheid.oneiro.kotlin

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.adelheid.oneiro.lowering.BindingAnnotations
import org.adelheid.oneiro.lowering.LocalNames
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor

/**
 * Checks of a file's declarations against what Lua can hold, made before its code is lowered, as the Java front end
 * makes them: every name is a Lua name; nothing carries what only generated bindings do; functions do not share a
 * name, which Lua's untyped parameters cannot tell apart; a property and a function do not share a slot of one Lua
 * table; and the declarations that become locals of the module's chunk, the private members and nested classes of the
 * file's classes, do not share a name.
 */
internal class DeclarationChecks(private val unit: FileLowering) {

	/** Whether every name the file declares can be a Lua name; each that cannot is reported. */
	fun names(): Boolean {
		// A lambda is a declaration without a name of its own
		val invalid = PsiTreeUtil.collectElementsOfType(unit.file, KtNamedDeclaration::class.java).filter {
			it !is KtFunctionLiteral && it.name?.let(LocalNames::isAscii) == false
		}
		for (declaration in invalid) {
			unit.error(declaration, "Lua names are ASCII letters, digits and underscores only; rename"
				+ " ${declaration.name}.")
		}
		return invalid.isEmpty()
	}

	/**
	 * Whether the file is free of what only generated bindings carry: the annotations that tell the compiler how a
	 * class meets Lua it does not write, `@LuaGlobal`, `@LuaModule` and the rest, and external functions and accessors.
	 * Each is reported where it is, because the Lua the plugin calls is bound from its LuaCATS annotations instead.
	 */
	fun bindingsOnly(): Boolean = with(unit.session) {
		var clean = true
		for (entry in PsiTreeUtil.collectElementsOfType(unit.file, KtAnnotationEntry::class.java)) {
			val classId = entry.typeReference?.type?.expandedSymbol?.classId ?: continue
			if (BindingAnnotations.isBindingAnnotation(classId.asFqNameString())) {
				unit.error(entry, BindingAnnotations.rejection(classId.shortClassName.asString()))
				clean = false
			}
		}
		val external = PsiTreeUtil.collectElementsOfType(unit.file, KtNamedFunction::class.java) +
			PsiTreeUtil.collectElementsOfType(unit.file, KtPropertyAccessor::class.java)
		for (declaration in external.filter { it.hasModifier(KtTokens.EXTERNAL_KEYWORD) }) {
			unit.error(declaration, BindingAnnotations.noBody(
				if (declaration is KtNamedFunction) "An external function" else "An external accessor"))
			clean = false
		}
		clean
	}

	/** The checks of the file's classes and objects, each problem reported where it is. */
	fun check(main: KtClassOrObject) {
		val classes = PsiTreeUtil.collectElementsOfType(unit.file, KtClassOrObject::class.java)
			.filter { !it.isLocal && !(it is KtObjectDeclaration && it.isObjectLiteral()) }
		overloads(unit.file.declarations)
		for (declaration in classes) {
			overloads(declaration.declarations)
			slots(declaration)
		}
		locals(main, classes)
	}

	/** Functions of one class, object or file that share a name, which one Lua function would have to be. */
	private fun overloads(declarations: List<KtDeclaration>) {
		val functions = declarations.filterIsInstance<KtNamedFunction>().groupBy { it.name }
		for ((name, sharing) in functions) {
			for (function in sharing.drop(1)) {
				unit.error(function, "Lua functions have no parameter types, so two functions cannot share a name;"
					+ " rename one (for example ${name}All, ${name}One), or give one function default values for the"
					+ " parameters the others leave out.")
			}
		}
	}

	/**
	 * A property and a function of one name in a class, whose objects hold the property where a method call looks
	 * up the function, or in an object, whose one table holds both. A private function is a local of the module and
	 * clashes with nothing here, nor does an object's private property.
	 */
	private fun slots(declaration: KtClassOrObject) {
		if (declaration == unit.entry || declaration is KtClass && (declaration.isInterface() || declaration.isEnum() ||
				declaration.isPlainData())) {
			return
		}
		val instance = declaration is KtClass
		val properties = declaration.declarations.filterIsInstance<KtProperty>()
			.filter { instance || !it.isPrivateInLua() }.mapNotNull { it.name } +
			declaration.primaryConstructorParameters.filter { it.hasValOrVar() }.mapNotNull { it.name }
		for (function in declaration.declarations.filterIsInstance<KtNamedFunction>()) {
			val name = function.name ?: continue
			if (name in properties && !function.isPrivateInLua()) {
				unit.error(function, "The property $name and the function $name() would share one slot in the Lua"
					+ " table; rename one.")
			}
		}
	}

	/**
	 * The declarations that become locals of the module's one chunk: its table, the nested classes, the private
	 * functions of every class and object, the private properties of objects, the file's own functions and
	 * properties, and every member of the plugin class.
	 */
	private fun locals(main: KtClassOrObject, classes: List<KtClassOrObject>) {
		val taken = mutableMapOf<String, PsiElement>(main.name!! to main)
		for (declaration in unit.file.declarations) {
			if (declaration is KtNamedFunction || declaration is KtProperty) {
				claim(taken, declaration as KtNamedDeclaration)
			}
		}
		for (declaration in classes) {
			if (declaration != main && !(declaration is KtObjectDeclaration && declaration.isCompanion())) {
				claim(taken, declaration)
			}
			val allLocal = declaration == unit.entry || declaration.parent?.parent == unit.entry && unit.entry != null
			val isObject = declaration is KtObjectDeclaration
			val functions = mutableSetOf<String>()
			for (member in declaration.declarations) {
				val private = member.isPrivateInLua()
				val function = (member as? KtNamedFunction)?.takeIf { !it.hasModifier(KtTokens.OVERRIDE_KEYWORD) }
				val property = member as? KtProperty
				val settings = property?.let(unit::isSettingsProperty) == true
				if (function != null && (private || allLocal)) {
					// The overloads of one name are reported apart
					if (functions.add(function.name ?: continue)) {
						claim(taken, function)
					}
				} else if (property != null && (allLocal && !settings || private && isObject)) {
					claim(taken, property)
				}
			}
		}
	}

	private fun claim(taken: MutableMap<String, PsiElement>, declaration: KtNamedDeclaration) {
		val name = unit.names.luaName(declaration.name ?: return)
		if (taken.putIfAbsent(name, declaration) != null) {
			unit.error(declaration, "In Lua, the private members and nested classes of the classes in a file are"
				+ " locals of one scope, so two of them cannot both be named $name; rename one.")
		}
	}
}
