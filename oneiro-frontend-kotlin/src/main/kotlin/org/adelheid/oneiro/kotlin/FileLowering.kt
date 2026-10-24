package org.adelheid.oneiro.kotlin

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import org.adelheid.oneiro.lowering.Diagnostic
import org.adelheid.oneiro.lowering.DocAnnotations
import org.adelheid.oneiro.lowering.LocalNames
import org.adelheid.oneiro.lowering.LuaModule
import org.adelheid.oneiro.lowering.ModulePaths
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Comment
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.FunctionBody
import org.adelheid.oneiro.lua.Statement
import org.adelheid.oneiro.lua.Trivia
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter

/**
 * Lowers one Kotlin source file into one Lua module. The module starts with the file's path and a header from the
 * comments above its declaration and the declaration's KDoc; then come the module table, its functions, and the
 * `return` of the table. A file declares one object, whose functions become the module's.
 *
 * This class keeps what the whole file shares: its diagnostics, its source text and comments, and the loops that count
 * from 1. The code inside functions is lowered by [BodyLowering].
 */
internal class FileLowering(val session: KaSession, val file: KtFile, val options: KotlinFrontend.Options) {

	val source = SourceText(file)
	val types = KotlinTypes(session)
	val names = LocalNames(setOf())

	/** The parameters of the loops that count from 1, decided for the whole file at once. */
	val oneBased: Set<KtParameter> by lazy { OneBasedLoops.find(this) }

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
		val declaration = file.declarations.singleOrNull()
		if (declaration !is KtObjectDeclaration || declaration.isCompanion() || declaration.name == null) {
			reject(Rejection.notYet(declaration ?: file, "A file that declares anything but one object"))
			return null
		}
		val moduleName = declaration.name!!
		val header = header(declaration)
		val table = Statement.Local(listOf(moduleName), listOf(Expression.Table(listOf())))
		val entries = mutableListOf(Block.Entry.of(table))
		for (member in declaration.declarations) {
			try {
				entries += member(moduleName, member)
			} catch (rejection: Rejection) {
				reject(rejection)
			}
		}
		val returned = Statement.Return(listOf(Expression.name(moduleName)))
		entries += Block.Entry(returned, Trivia(true, listOf(), null), null)
		for (comment in source.unclaimed()) {
			warning(comment, "This comment could not be placed in the Lua and was left out; move it onto its own line"
				+ " above a statement.")
		}
		if (diagnostics.any { it.severity() == Diagnostic.Severity.ERROR }) {
			return null
		}
		val path = ModulePaths.requirePath(options.rootPackage, packageName, moduleName) + ".lua"
		return LuaModule.of(path, header, Block(entries, listOf()))
	}

	/** The comments above the declaration and its KDoc description: the header every Lua file starts with. */
	private fun header(declaration: KtDeclaration): List<Comment> {
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

	private fun member(moduleName: String, member: KtDeclaration): Block.Entry = when (member) {
		is KtNamedFunction -> {
			val trivia = Trivia(true, memberComments(member), null)
			val function = Statement.FunctionDeclaration(listOf(moduleName, member.name!!), null, function(member))
			Block.Entry(function, trivia, source.position(codeStart(member)))
		}
		else -> throw Rejection.notYet(member, "This member of an object")
	}

	private fun function(function: KtNamedFunction): FunctionBody {
		val parameters = function.valueParameters.map { names.luaName(it.name!!) }
		val body = BodyLowering(this)
		val bodyBlock = function.bodyBlockExpression
		val expression = function.bodyExpression
		return when {
			bodyBlock != null -> FunctionBody.of(parameters, body.block(bodyBlock))
			expression != null ->
				FunctionBody.of(parameters, Block.of(Statement.Return(listOf(body.expression(expression)))))
			else -> throw Rejection(function, "A function without a body has no Lua; give it one.")
		}
	}

	/**
	 * A function's comments: the source comments above it, its KDoc description, and, with KDoc, the annotations its
	 * signature gives, `---@param` and `---@return`.
	 */
	private fun memberComments(function: KtNamedFunction): List<Comment> = with(session) {
		val lines = source.leading(function).toMutableList()
		val doc = function.docComment ?: return lines
		KDocText.description(doc).forEach { lines += Comment.line(it) }
		for (parameter in function.valueParameters) {
			val type = types.luaType(parameter.symbol.returnType)
			val text = KDocText.param(doc, parameter.name!!)
			lines += Comment.annotation("@param ${names.luaName(parameter.name!!)} $type $text".trimEnd())
		}
		val returned = function.symbol.returnType
		if (!returned.isUnitType) {
			lines += Comment.annotation(DocAnnotations.returned(types.luaType(returned), KDocText.returned(doc)))
		}
		return lines
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

	private fun error(element: PsiElement, message: String) {
		diagnostics += source.diagnostic(Diagnostic.Severity.ERROR, codeStart(element).textRange.startOffset, message)
	}

	private fun warning(element: PsiElement, message: String) {
		diagnostics += source.diagnostic(Diagnostic.Severity.WARNING, codeStart(element).textRange.startOffset, message)
	}
}
