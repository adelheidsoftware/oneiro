package org.adelheid.oneiro.kotlin

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import org.adelheid.oneiro.lowering.Diagnostic
import org.adelheid.oneiro.lowering.Helper
import org.adelheid.oneiro.lowering.LocalNames
import org.adelheid.oneiro.lowering.LuaModule
import org.adelheid.oneiro.lowering.ModuleLayout
import org.adelheid.oneiro.lowering.ModulePaths
import org.adelheid.oneiro.lowering.SourceLanguage
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Comment
import org.adelheid.oneiro.lua.Expression
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import java.util.EnumSet

/**
 * Lowers one Kotlin source file into one Lua module. The module starts with the file's path and a header from the
 * comments above its declaration and the declaration's KDoc. What follows depends on what the file declares: the
 * plugin class becomes `main.lua`, a chunk of locals; an interface annotated `@ConfigGroup` becomes a module of
 * settings; an object, a class or an enum becomes a module table with its members.
 *
 * This class keeps what the whole file shares: its diagnostics, its source text and comments, the modules it requires,
 * the helpers and globals its Lua uses, and the loops that count from 1. The parts of a file have classes of their
 * own: [ClassLowering] for objects, classes and enums, [PluginClass] for the plugin class, [SettingsLowering] for
 * settings, and [References] for what each member uses. The code inside functions is lowered by [BodyLowering].
 *
 * @param names the Lua names for the file's locals, which avoid the helpers and globals a first lowering found
 */
internal class FileLowering(
	val session: KaSession,
	val file: KtFile,
	val compilation: KotlinFrontend.Compilation,
	val names: LocalNames,
) {

	val options = compilation.options

	val source = SourceText(file)
	val annotations = Annotations(session)
	val types = KotlinTypes(session, annotations, ::isPluginType)
	val reviews = ReviewWarnings(this)

	/** The parameters of the loops that count from 1, decided for the whole file at once. */
	val oneBased: Set<KtParameter> by lazy { OneBasedLoops.find(this) }

	/** The file's integer values that are never negative. */
	val nonNegative = NonNegative(this)

	/**
	 * The lambdas whose code runs in place, in the function they are written in, as a loop's body or a `do` block, so
	 * that a plain `return` inside one returns from that function, as Kotlin's does.
	 */
	val inlinedLambdas: MutableSet<PsiElement> = mutableSetOf()

	/** The classes, objects and interfaces this file declares, at any depth. */
	val fileClasses: Set<KtClassOrObject> =
		PsiTreeUtil.collectElementsOfType(file, KtClassOrObject::class.java).toSet()

	/** The plugin class, when this file declares it. */
	var entry: KtClass? = null
		private set

	/** The local that holds the module table: the name of the file's declaration. */
	var moduleName: String = ""
		private set

	/** The local name of each required module and its require path, in order of first use. */
	val requires: MutableMap<String, String> = linkedMapOf()

	/** The Lua globals the module reads, such as `Player` or `config`, which a local of the same name would hide. */
	val globals: MutableSet<String> = mutableSetOf()

	private val helpers: EnumSet<Helper> = EnumSet.noneOf(Helper::class.java)
	private val diagnostics = mutableListOf<Diagnostic>()

	/** The errors and warnings found while lowering, in source order. */
	fun diagnostics(): List<Diagnostic> = diagnostics.sortedWith(compareBy({ it.line() }, { it.column() }))

	/** The module, or null when the file could not be lowered; see [diagnostics]. */
	fun lower(): LuaModule? {
		val packageName = file.packageFqName.asString()
		if (!ModulePaths.isInside(options.rootPackage, packageName)) {
			error(file.packageDirective ?: file, "$packageName is not inside the plugin's root package "
				+ "${options.rootPackage}, so its Lua has no place under src/; move the file into that package.")
			return null
		}
		val classes = file.declarations.filterIsInstance<KtClassOrObject>()
		val main = classes.singleOrNull()
		if (main == null || main.name == null) {
			reject(Rejection.notYet(file.declarations.firstOrNull() ?: file,
				"A file that declares anything but one class, object, interface or enum"))
			return null
		}
		moduleName = main.name!!
		if (main is KtObjectDeclaration && hasAnnotation(main, Annotations.LUA_MODULE)) {
			// A declaration of a hand-written module, which produces no Lua
			checkDeclaration(main)
			return null
		}
		if (main is KtClass && main.isPlainData()) {
			// A plain table, built where it is constructed, which produces no Lua of its own
			return null
		}
		val header = header(main)
		val module = try {
			when {
				main is KtClass && (isPluginClass(main) || isDevEntry(main)) -> {
					entry = main
					PluginClass(this, main).lower(header)
				}
				main is KtClass && main.isInterface() && hasAnnotation(main, Annotations.CONFIG_GROUP) ->
					SettingsLowering(this, main).lower(header, path(packageName))
				main is KtClass && main.isEnum() -> {
					// A simple enum's table holds its constants, and nothing else
					val table = ClassLowering(this).enumTable(main)
					LuaModule.of(path(packageName), header, Block(assemble(table, false, listOf()), listOf()))
				}
				else -> {
					val classes = ClassLowering(this)
					val members = classes.members(main)
					val instanceClass = main is KtClass && !main.isInterface()
					val body = assemble(classes.classTable(main), instanceClass, members)
					LuaModule.of(path(packageName), header, Block(body, listOf()))
				}
			}
		} catch (rejection: Rejection) {
			reject(rejection)
			null
		}
		for (comment in source.unclaimed()) {
			warning(comment, "This comment could not be placed in the Lua and was left out; move it onto its own line"
				+ " above a statement.")
		}
		return if (diagnostics.any { it.severity() == Diagnostic.Severity.ERROR }) null else module
	}

	/**
	 * A `@LuaModule` declaration's members only stand for what the hand-written module defines: external functions,
	 * properties with external accessors, and data classes of properties alone, the tables it passes.
	 */
	private fun checkDeclaration(declaration: KtObjectDeclaration) {
		for (member in declaration.declarations) {
			when {
				member is KtNamedFunction && !member.hasModifier(KtTokens.EXTERNAL_KEYWORD) -> error(member, "Every"
					+ " function of a @LuaModule declaration is external: it stands for a function the Lua module"
					+ " defines.")
				member is KtNamedFunction -> {}
				member is KtProperty && !hasExternalAccessors(member) -> error(member, "A @LuaModule declaration's"
					+ " properties take their values from the Lua module; declare them without a value and with"
					+ " external accessors: val LEVEL: Int external get.")
				member is KtProperty -> {}
				member is KtClass && member.isPlainData() -> {}
				else -> reject(Rejection.notYet(member, "This member in a @LuaModule declaration"))
			}
		}
	}

	private fun hasExternalAccessors(property: KtProperty): Boolean =
		property.initializer == null && property.getter?.hasModifier(KtTokens.EXTERNAL_KEYWORD) == true &&
			(!property.isVar || property.setter?.hasModifier(KtTokens.EXTERNAL_KEYWORD) == true)

	/** A member's name in the Lua: its own, or the one `@LuaName` gives it. */
	fun memberName(member: KaCallableSymbol): String = with(session) {
		annotations.value(member, Annotations.LUA_NAME)
			?: (member as? KaPropertySymbol)?.backingFieldSymbol?.let { annotations.value(it, Annotations.LUA_NAME) }
			?: member.callableId?.callableName?.asString()
			?: throw IllegalStateException("a member without a name: $member")
	}

	/**
	 * A declared member's name in the Lua: its own, or the one `@LuaName` gives it; for a constructor's parameter, its
	 * property's.
	 */
	fun memberName(member: KtCallableDeclaration): String = with(session) {
		val symbol = member.symbol
		val property = (symbol as? KaValueParameterSymbol)?.generatedPrimaryConstructorProperty
		memberName(property ?: symbol as KaCallableSymbol)
	}

	/** The module's statements, laid out by [ModuleLayout] with the file's requires and helpers. */
	fun assemble(table: Expression?, instanceClass: Boolean, members: List<Member>): List<Block.Entry> =
		ModuleLayout.assemble(moduleName, table, instanceClass, requires, helpers, SourceLanguage.KOTLIN, members)

	private fun path(packageName: String): String =
		ModulePaths.requirePath(sourceFolder(), options.rootPackage, packageName, moduleName) + ".lua"

	/** The folder the file's Lua goes to: `src`, or `dev` for the dev source set, whose Lua stays out of the zip. */
	private fun sourceFolder(): String = if (options.plugin.dev()) "dev" else "src"

	/** The comments above the declaration and its KDoc description: the header every Lua file starts with. */
	fun header(declaration: KtDeclaration): List<Comment> {
		val header = source.before(codeStart(declaration).textRange.startOffset).toMutableList()
		val doc = declaration.docComment
		if (doc != null) {
			if (header.isNotEmpty()) {
				header += Comment.line("")
			}
			KDocText.description(doc).forEach { header += Comment.line(it) }
		}
		if (header.isEmpty()) {
			warning(declaration, "${declaration.name} has no KDoc, but every Lua file starts with a comment that says"
				+ " what it does; add a KDoc comment to it.")
		}
		return header
	}

	/** Whether a class extends Oneiro's `Plugin`: the plugin class, which becomes `main.lua`. */
	fun isPluginClass(declaration: KtClass): Boolean = extends(declaration, Annotations.PLUGIN)

	/** Whether a class extends Oneiro's `DevPlugin`: the dev source set's, which becomes `dev/main.lua`. */
	fun isDevEntry(declaration: KtClass): Boolean = extends(declaration, Annotations.DEV_PLUGIN)

	private fun extends(declaration: KtClass, base: ClassId): Boolean = with(session) {
		declaration.classSymbol?.superTypes?.any { it.expandedSymbol?.classId == base } == true
	}

	fun hasAnnotation(declaration: KtDeclaration, annotation: ClassId): Boolean =
		with(session) { annotations.has(declaration.symbol, annotation) }

	/**
	 * The table of a class of the plugin: a local of this file for its own classes, or another file's module, required
	 * at the top.
	 */
	fun moduleReference(type: KaClassSymbol): Expression = with(session) {
		val declaration = type.psi
		if (declaration is KtClassOrObject && declaration in fileClasses) {
			return Expression.name(declaration.name!!)
		}
		val classId = type.classId ?: throw IllegalStateException("a plugin class without a name: $type")
		val outer = classId.outerClassId
		if (outer != null) {
			val outerSymbol = findClass(outer) ?: throw IllegalStateException("the class around $classId is missing")
			return Expression.field(moduleReference(outerSymbol), classId.shortClassName.asString())
		}
		val local = classId.shortClassName.asString()
		val folder = if (type.origin == KaSymbolOrigin.SOURCE) sourceFolder() else "src"
		// A @LuaModule declaration names the hand-written module it stands for
		requires.putIfAbsent(local, annotations.value(type, Annotations.LUA_MODULE)
			?: ModulePaths.requirePath(folder, options.rootPackage, classId.packageFqName.asString(), local))
		return Expression.name(local)
	}

	/** Whether a class is the plugin's own, written in its sources or in the main source set the dev set builds on. */
	fun isPluginType(type: KaClassSymbol): Boolean {
		val packageName = type.classId?.packageFqName?.asString() ?: return false
		return ModulePaths.isInside(options.rootPackage, packageName)
	}

	fun useGlobal(name: String) {
		globals += name
	}

	fun use(helper: Helper) {
		helpers += helper
	}

	/** The names a local must not take: the helpers the module defines and the globals it reads. */
	fun takenNames(): Set<String> = globals + helpers.map { it.luaName() }

	/** The names of every function, property, parameter and local the file declares. */
	fun declaredNames(): Set<String> =
		PsiTreeUtil.collectElementsOfType(file, KtNamedDeclaration::class.java).mapNotNull { it.name }.toSet()

	/** A property of the plugin class whose type is an interface of settings: it holds nothing in the Lua. */
	fun isSettingsProperty(property: KtProperty): Boolean = with(session) {
		val type = property.symbol.returnType.expandedSymbol ?: return false
		annotations.has(type, Annotations.CONFIG_GROUP)
	}

	/** Where an element's code starts, after the comments and KDoc the tree may have made its first children. */
	fun codeStart(element: PsiElement): PsiElement {
		var child = element.firstChild
		while (child is PsiComment || child is PsiWhiteSpace) {
			child = child.nextSibling
		}
		return child ?: element
	}

	fun reject(rejection: Rejection) {
		error(rejection.element, rejection.message)
	}

	fun error(element: PsiElement, message: String) {
		diagnostics += source.diagnostic(Diagnostic.Severity.ERROR, codeStart(element).textRange.startOffset, message)
	}

	fun warning(element: PsiElement, message: String) {
		diagnostics += source.diagnostic(Diagnostic.Severity.WARNING, codeStart(element).textRange.startOffset, message)
	}
}
