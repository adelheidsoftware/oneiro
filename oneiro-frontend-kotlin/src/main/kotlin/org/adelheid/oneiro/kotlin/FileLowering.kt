package org.adelheid.oneiro.kotlin

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import org.adelheid.oneiro.lowering.ClientApi
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
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaJavaFieldSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
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
	val types = KotlinTypes(session, annotations, ::isPluginType, ::isObjectEnum)
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
		val checks = DeclarationChecks(this)
		if (!checks.names()) {
			return null
		}
		val classes = file.declarations.filterIsInstance<KtClassOrObject>()
		if (classes.size > 1) {
			for (extra in classes.drop(1)) {
				error(extra, "Lua has one module per file; move ${extra.name} into a file of its own.")
			}
			return null
		}
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
		if (main is KtClass && (isPluginClass(main) || isDevEntry(main))) {
			entry = main
		}
		checks.check(main)
		val header = header(main)
		checkSubscribers()
		val module = try {
			when {
				main is KtClass && (isPluginClass(main) || isDevEntry(main)) -> PluginClass(this, main).lower(header)
				main is KtClass && main.isInterface() && hasAnnotation(main, Annotations.CONFIG_GROUP) ->
					SettingsLowering(this, main).lower(header, path(packageName))
				hasAnnotation(main, Annotations.CONFIG_GROUP) -> throw Rejection(main, "@ConfigGroup marks an interface"
					+ " of settings; make ${main.name} an interface whose functions carry @ConfigItem.")
				main is KtClass && main.isEnum() && with(session) { main.classSymbol }?.let(::isObjectEnum) == true -> {
					val classes = ClassLowering(this)
					val members = classes.objectEnumMembers(main)
					LuaModule.of(path(packageName), header, Block(assemble(Expression.Table(listOf()), true, members),
						listOf()))
				}
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
		val failed = diagnostics.any { it.severity() == Diagnostic.Severity.ERROR }
		// The code an error stopped leaves its comments unplaced, which says nothing new
		if (!failed) {
			for (comment in source.unclaimed()) {
				warning(comment, "This comment could not be placed in the Lua and was left out; move it onto its own"
					+ " line above a statement.")
			}
		}
		return if (failed) null else module
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
			// A member of a local class or an object expression has no callable ID, only its name
			?: (member as? KaNamedSymbol)?.name?.asString()
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

	/** The Lua names of the file's locals and parameters, by declaration and name. */
	private val localNames = mutableMapOf<Pair<PsiElement, String>, String>()

	/** The file's own locals each member's code uses, by their Lua names. */
	private val fileLocalsUsed = mutableMapOf<PsiElement, Set<String>>()

	/**
	 * The Lua name of a local or parameter. Kotlin keeps locals apart from members and classes, but in Lua the file's
	 * private members, the plugin class's members and the classes' tables are locals too, so a local that shares a name
	 * with one its own member uses would hide it: `val step = n - 1; return step(step)` in a private function `step`.
	 * Such a local gets a trailing underscore. Every use of a local asks here, so that its declaration and its uses
	 * agree.
	 *
	 * @param declaration the local's declaration, or for one Kotlin declares itself, such as a lambda's `it`, the
	 *     declaration it belongs to; null for a name no code of the file's shares
	 */
	fun localName(declaration: PsiElement?, name: String): String {
		if (declaration == null) {
			return names.luaName(name)
		}
		aliases[declaration]?.let { return it }
		return localNames.getOrPut(declaration to name) {
			val member = enclosingMember(declaration)
			val used = fileLocalsUsed.getOrPut(member) { fileLocalsUsedIn(member) }
			var lua = names.luaName(name)
			while (lua in used) {
				lua += "_"
			}
			lua
		}
	}

	/** The Lua names locals and parameters stand for instead of their own, by declaration; see [alias]. */
	private val aliases = mutableMapOf<PsiElement, String>()

	/**
	 * Makes a local or parameter stand for another Lua name, as a lambda's parameter stands for the variable of the
	 * loop its code is opened in.
	 */
	fun alias(declaration: PsiElement, lua: String) {
		aliases[declaration] = lua
	}

	/** The Lua receivers stand for, by the function or lambda they are the `this` of; see [aliasReceiver]. */
	private val receiverAliases = mutableMapOf<PsiElement, Expression>()

	/**
	 * Makes the receiver of a function or lambda, its `this`, stand for a Lua name or property: a scope function's
	 * receiver, which members named without a receiver are read on, `apply { SetPos(1, 2) }`.
	 */
	fun aliasReceiver(function: PsiElement, lua: Expression) {
		receiverAliases[function] = lua
	}

	/** The Lua the receiver of a function or lambda stands for, or null for one without an alias. */
	fun receiverAlias(function: PsiElement?): Expression? = function?.let(receiverAliases::get)

	/** The Lua names of a map entry's key and value, by the declaration of the parameter that is the entry. */
	private val entryAliases = mutableMapOf<PsiElement, Pair<String, String>>()

	/**
	 * Makes a lambda's parameter that is a map's entry stand for the key and the value of the loop its code is opened
	 * in, which `entry.key` and `entry.value` read; Lua has no entry of its own to pass around.
	 */
	fun aliasEntry(declaration: PsiElement, key: String, value: String) {
		entryAliases[declaration] = key to value
	}

	/** The Lua names of the key and value that an entry's declaration stands for, or null for any other declaration. */
	fun entryAlias(declaration: PsiElement?): Pair<String, String>? = declaration?.let(entryAliases::get)

	// How many labels the file's chains have taken for leaving the loops inside their own
	private var exitLabels = 0

	/** A label for a chain to leave its loops at, `done`, then `done2` and up, so that no two in the file clash. */
	fun exitLabel(): String = if (exitLabels++ == 0) "done" else "done$exitLabels"

	/** The member, a function, a property or an `init` block of a class or of the file, that code is part of. */
	private fun enclosingMember(element: PsiElement): PsiElement {
		var current = element
		while (current.parent != null && current.parent !is KtClassBody && current.parent !is KtFile) {
			current = current.parent
		}
		return current
	}

	/**
	 * The file's own locals a member's code uses, by their Lua names: a class's table, a private member of an object
	 * or a class, a member of the plugin class or a file's property, and for a member of an object of the file used
	 * without a qualifier, the object's table.
	 */
	private fun fileLocalsUsedIn(member: PsiElement): Set<String> = with(session) {
		val used = mutableSetOf<String>()
		for (reference in PsiTreeUtil.collectElementsOfType(member, KtNameReferenceExpression::class.java)) {
			if (PsiTreeUtil.getParentOfType(reference, KtTypeReference::class.java) != null) {
				continue
			}
			val symbol = reference.resolveSymbol() ?: continue
			if (symbol is KaClassSymbol) {
				symbol.classId?.shortClassName?.asString()?.let(used::add)
				continue
			}
			val declaration = symbol.psi as? KtCallableDeclaration ?: continue
			if (declaration.containingFile != file || declaration !is KtNamedFunction && declaration !is KtProperty) {
				continue
			}
			val owner = PsiTreeUtil.getParentOfType(declaration, KtClassOrObject::class.java)
			val ownerIsEntry = owner == entry || owner?.parent?.parent == entry && entry != null
			val local = declaration.isPrivateInLua() || declaration.parent is KtFile || ownerIsEntry
			if (local && declaration.parent.let { it is KtClassBody || it is KtFile }) {
				used += names.luaName(declaration.name ?: continue)
				continue
			}
			val qualified = (reference.parent as? KtQualifiedExpression)?.selectorExpression == reference
			if (!qualified && owner is KtObjectDeclaration && declaration.parent is KtClassBody) {
				val table = if (owner.isCompanion()) PsiTreeUtil.getParentOfType(owner, KtClassOrObject::class.java)
				else owner
				table?.name?.let(used::add)
			}
		}
		used
	}

	/** The event a payload class is for, when that event passes nothing; null for any other type. */
	fun eventWithoutPayload(type: KaType): String? = with(session) {
		val payload = type.expandedSymbol ?: return null
		val event = annotations.value(payload, Annotations.LUA_EVENT) ?: return null
		val passesNothing = payload.declaredMemberScope.callables
			.none { it is KaJavaFieldSymbol || it is KaPropertySymbol }
		if (passesNothing) event else null
	}

	/** Whether a class is the plugin class, or the dev source set's, which the client creates once. */
	fun isEntryClass(type: KaClassSymbol): Boolean {
		val name = type.classId?.asFqNameString() ?: return false
		return compilation.entries.any { it.qualifiedName == name } ||
			type.classId == Annotations.PLUGIN || type.classId == Annotations.DEV_PLUGIN
	}

	/** `@Subscribe` subscribes only the plugin class's functions, which `main.lua` subscribes as it loads. */
	private fun checkSubscribers() {
		for (function in PsiTreeUtil.collectElementsOfType(file, KtNamedFunction::class.java)) {
			val owner = function.containingClassOrObject?.let { with(session) { it.classSymbol } }
			if (hasAnnotation(function, Annotations.SUBSCRIBE) && (owner == null || !isEntryClass(owner))) {
				error(function, "Only the plugin class's functions are subscribed with @Subscribe; subscribe this one"
					+ " with ${ClientApi.EVENT}.<Name>.Subscribe(name, callback), and unsubscribe it when it is"
					+ " done.")
			}
		}
	}

	/** Whether an enum's constants are objects rather than the strings of their names; see [ObjectEnums]. */
	fun isObjectEnum(type: KaClassSymbol): Boolean =
		type.classKind == KaClassKind.ENUM_CLASS && type.classId?.asFqNameString() in compilation.objectEnums

	/** Whether a class is the plugin's own, written in its sources or in the main source set the dev set builds on. */
	fun isPluginType(type: KaClassSymbol): Boolean {
		// A class declared inside a function, or an object expression, has no name outside it, but is the file's
		val packageName = type.classId?.packageFqName?.asString() ?: return type.psi?.containingFile == file
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
