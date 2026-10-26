package org.adelheid.oneiro.bindings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reads LuaCATS annotations, the Lua Language Server's, into the {@link ApiModel}: from definition files, which begin
 * with {@code ---@meta} and declare without running anything, as the stubs do, and from code annotated in place, as a
 * library's is. {@link LuaSyntax} parses each file; this class reads the annotations before each top-level statement,
 * and what the statement declares.
 *
 * <p>Annotations come in blocks of {@code ---} lines, which a blank line ends: description lines, then tags. A
 * {@code ---@class} line opens a class, whose {@code ---@field} lines follow it; a description line or a tag of a
 * function closes it, so that classes without a table can stand back to back. The statement after a block is what the
 * block annotates:
 *
 * <ul>
 *   <li>{@code X = {}}, {@code local X = {}}, or any value a name takes after {@code ---@class}: the class's table,
 *       global or local; after {@code ---@enum}, a table of the enum's values. A local table without a class collects
 *       what the file defines on it, in case the file returns it.
 *   <li>{@code function T.f(...)}, {@code function T:f(...)}, {@code T.f = function(...)}, and {@code T.f = f} for a
 *       local function: a function of the class of the table, called with a colon when declared with one, or when its
 *       first parameter is {@code self}.
 *   <li>{@code T.x = value}, and {@code x = value} in the table's constructor: a field, of the type
 *       {@code ---@type} gives, or else of a literal's type, or {@code any}; for {@code require "path"}, the table that
 *       module exports.
 *   <li>{@code return T}: the file's export, the table {@code require} gives.
 * </ul>
 *
 * <p>It infers nothing beyond a literal's type and the module a {@code require} names. A member that
 * {@code ---@private}, {@code ---@protected} or {@code ---@package} marks, and a field declared with one of those
 * visibilities, is no part of the API and is left out. In a definition file, a statement that declares nothing the
 * reader understands is reported, as is a function of a table that no file declares; in code, such statements are the
 * library's own workings and pass without a word.
 */
final class LuaCatsReader {

	/** {@code ---@class (exact) Name<T>: Parent}: the name, the type parameters and the first parent. */
	private static final Pattern CLASS_TAG = Pattern.compile("^---@class\\s+(?:\\(\\w+(?:\\s*,\\s*\\w+)*\\)\\s*)?"
			+ "([\\w.]+)(?:<([^>]*)>)?(?:\\s*:\\s*([\\w.]+))?\\s*(.*)$");
	private static final Pattern NAME_THEN_REST = Pattern.compile("^---@(field|param|alias|vararg)\\s+(\\S+)\\s*(.*)$");
	private static final Pattern ENUM_TAG = Pattern.compile("^---@enum\\s+(?:\\((\\w+)\\)\\s*)?([\\w.]+).*$");
	/** {@code ---@operator add(Vector3): Vector3}: the operator, its other operand's type, if any, and the result's. */
	private static final Pattern OPERATOR_TAG =
			Pattern.compile("^---@operator\\s+(\\w+)(?:\\s*\\(([^)]*)\\))?\\s*:\\s*(.+)$");
	/** The marks the docs put at the start of a field's description, which the field's binding follows. */
	private static final String READ_ONLY = "(Read-only)";
	private static final String WRITABLE = "(Writeable)";
	private static final Pattern VISIBLE_FIELD = Pattern.compile("^(\\S+)\\s+(\\S.*)$");
	private static final Pattern GENERIC_NAME = Pattern.compile("\\G\\s*([A-Za-z_]\\w*)(?:\\s*:\\s*[\\w.]+)?\\s*(,?)");
	private static final Pattern RETURN_NAME = Pattern.compile("^([A-Za-z_]\\w*)(?:\\s+(.*))?$");
	private static final Set<String> VISIBILITIES = Set.of("public", "private", "protected", "package");
	/** Tags that shape no API, which the reader passes over. */
	private static final Set<String> IGNORED_TAGS = Set.of("async", "nodiscard", "see", "source", "version",
			"diagnostic", "cast", "as", "module", "public");

	/** The function of an enum table that turns a value into its name. */
	private static final String FROM_ID = "FromID";

	/** A function whose owner is a global table, attached once every file is read. */
	private record PendingFunction(String rawOwner, ApiModel.FunctionDef function, boolean reported) {
	}

	/** A field that holds the table a module exports, known once every file is read. */
	private record PendingModuleTable(ApiModel.ClassDef owner, String field, String module, String file, int line,
			String text, boolean reported) {
	}

	/** A module that returns another's table, {@code return require "other"}. */
	private record PendingExport(String path, String module, String file, boolean reported) {
	}

	/** What a name local to the file stands for. */
	private sealed interface Local {
	}

	/**
	 * A table and its class. A provisional class, named by the module's path, collects the members of a table that no
	 * class annotation names, and joins the model only when the file returns the table.
	 */
	private record TableLocal(ApiModel.ClassDef type, boolean provisional, int line) implements Local {
	}

	private record EnumLocal(String name) implements Local {
	}

	/** The table of a module, {@code local reactive = require "zyklib/reactive"}. */
	private record ModuleLocal(String module) implements Local {
	}

	private record FunctionLocal(ApiModel.FunctionDef function) implements Local {
	}

	private record ValueLocal() implements Local {
	}

	private final ApiModel model;
	private final Map<String, String> globalTables = new HashMap<>();
	private final List<PendingFunction> unresolvedFunctions = new ArrayList<>();
	private final List<PendingModuleTable> moduleTables = new ArrayList<>();
	private final List<PendingExport> reexports = new ArrayList<>();

	// The file being read
	private String file = "";
	private String modulePath = "";
	private boolean definitions;
	private List<String> lines = List.of();
	private Map<String, Local> locals = new LinkedHashMap<>();
	private ApiModel.@Nullable ClassDef exported;
	private int lastLine;

	// The block being read
	private final List<String> description = new ArrayList<>();
	private final List<ApiModel.ParamDef> params = new ArrayList<>();
	private final List<ApiModel.ReturnDef> returns = new ArrayList<>();
	private final List<TypeRef> overloads = new ArrayList<>();
	private final List<String> typeParams = new ArrayList<>();
	private boolean varargs;
	private boolean deprecated;
	private boolean hidden;
	private @Nullable TypeRef type;
	private ApiModel.@Nullable ClassDef openClass;
	private @Nullable String openEnum;
	private boolean openEnumKeys;
	private String openEnumDescription = "";
	private @Nullable String openAlias;
	private int openAliasLine;
	private final List<TypeRef> aliasOptions = new ArrayList<>();

	LuaCatsReader(ApiModel model) {
		this.model = model;
	}

	/**
	 * Reads one file into the model.
	 *
	 * @param displayName the file's path as messages show it
	 * @param modulePath the path {@code require} loads the file by, such as {@code zyklib/reactive/Event}
	 */
	void read(Path path, String displayName, String modulePath) throws IOException {
		file = displayName;
		this.modulePath = modulePath;
		definitions = false;
		locals = new LinkedHashMap<>();
		exported = null;
		lastLine = 0;
		resetBlock();
		String source = Files.readString(path, StandardCharsets.UTF_8);
		lines = source.lines().toList();
		LuaSyntax.Chunk chunk;
		try {
			chunk = LuaSyntax.parse(source);
		} catch (LuaSyntax.SyntaxError error) {
			unparsed("not valid Lua: " + error.getMessage(), error.line(), lineText(error.line()));
			return;
		}
		for (LuaSyntax.Statement statement : chunk.statements()) {
			comments(statement.comments());
			statement(statement);
		}
		comments(chunk.trailing());
		resetBlock();
		if (definitions) {
			for (Local local : locals.values()) {
				if (local instanceof TableLocal table && table.provisional() && table.type() != exported) {
					unparsed("local table without a class", table.line(), lineText(table.line()));
				}
			}
		}
	}

	/**
	 * Attaches what waited for every file to be read: functions of global tables that later files declare, modules that
	 * return another's table, and fields that hold a module's; and reports what still has nothing to attach to.
	 */
	void finish() {
		for (PendingFunction pending : unresolvedFunctions) {
			String owner = globalTables.getOrDefault(pending.rawOwner(), pending.rawOwner());
			ApiModel.FunctionDef function = pending.function().withOwner(owner);
			ApiModel.ClassDef classDef = model.classes.get(owner);
			ApiModel.EnumDef enumDef = model.enums.get(owner);
			if (classDef != null) {
				classDef.functions.add(function);
			} else if (enumDef != null && function.name().equals(FROM_ID)) {
				// An enum table's FromID, which names a value
				model.enums.put(owner, enumDef.withFromId());
			} else if (pending.reported()) {
				model.unparsed.add(new ApiModel.Unparsed("function on unknown table " + pending.rawOwner(),
						function.file(), 0, "function " + pending.rawOwner() + "." + function.name()));
			}
		}
		valueClassesAsEnums();
		// In rounds, as a module can return one that returns a third's table
		List<PendingExport> waiting = new ArrayList<>(reexports);
		boolean progress = true;
		while (progress) {
			progress = waiting.removeIf(pending -> {
				ApiModel.ModuleDef target = model.modules.get(pending.module());
				if (target == null) {
					return false;
				}
				model.modules.put(pending.path(), new ApiModel.ModuleDef(pending.path(), target.export(),
						pending.file(), target.implicit()));
				return true;
			});
		}
		for (PendingExport pending : waiting) {
			if (pending.reported()) {
				model.unparsed.add(new ApiModel.Unparsed("returns a module no file declares", pending.file(), 0,
						"return require \"" + pending.module() + "\""));
			}
		}
		for (PendingModuleTable pending : moduleTables) {
			ApiModel.ModuleDef module = model.modules.get(pending.module());
			if (module != null) {
				pending.owner().tables.put(pending.field(), module.export());
			} else {
				pending.owner().tables.remove(pending.field());
				if (pending.reported()) {
					model.unparsed.add(new ApiModel.Unparsed("requires a module no file declares", pending.file(),
							pending.line(), pending.text()));
				}
			}
		}
	}

	// ---- Annotations

	private void comments(List<LuaSyntax.Comment> comments) {
		for (LuaSyntax.Comment comment : comments) {
			if (comment.endLine() <= lastLine) {
				// A comment inside the statement before, such as one before a function's end
				continue;
			}
			if (comment.line() > lastLine + 1) {
				resetBlock();
			}
			lastLine = comment.endLine();
			String text = comment.text();
			boolean dashes = text.length() > 1 && text.chars().allMatch(c -> c == '-');
			if (!comment.isLong() && text.startsWith("-") && !dashes) {
				docLine(text.substring(1), comment.line());
			}
		}
	}

	/** One line of annotations, without its {@code ---}. */
	private void docLine(String content, int line) {
		String stripped = content.stripLeading();
		if (openAlias != null && stripped.startsWith("|")) {
			aliasOption(stripped.substring(1), line);
			return;
		}
		finishAlias();
		if (stripped.startsWith("@")) {
			tag("---" + stripped, line);
		} else {
			descriptionLine(content);
		}
	}

	private void tag(String line, int lineNumber) {
		String tagName = line.substring(4).split("\\s", 2)[0];
		try {
			switch (tagName) {
				case "meta" -> definitions = true;
				case "class" -> classTag(line);
				case "field" -> fieldTag(line, lineNumber);
				case "param" -> paramTag(line);
				case "return" -> returnTag(line);
				case "overload" -> {
					closeClass();
					overloads.add(TypeParser.parsePrefix(rest(line, tagName), 0).type());
				}
				case "vararg" -> {
					closeClass();
					varargs = true;
				}
				case "alias" -> aliasTag(line, lineNumber);
				case "enum" -> enumTag(line);
				case "generic" -> genericTag(line);
				case "type" -> {
					closeClass();
					type = TypeParser.parsePrefix(rest(line, tagName), 0).type();
				}
				case "deprecated" -> deprecated = true;
				case "private", "protected", "package" -> hidden = true;
				case "operator" -> operatorTag(line, lineNumber);
				default -> {
					if (!IGNORED_TAGS.contains(tagName)) {
						unparsed("unknown tag @" + tagName, lineNumber, line);
					}
				}
			}
		} catch (TypeParser.ParseException exception) {
			unparsed("@" + tagName + " type not understood: " + exception.getMessage(), lineNumber, line);
		}
	}

	/** What follows a tag's name. */
	private static String rest(String line, String tagName) {
		return line.substring(("---@" + tagName).length()).strip();
	}

	private void classTag(String line) {
		closeClass();
		Matcher matcher = CLASS_TAG.matcher(line);
		if (!matcher.matches()) {
			throw new TypeParser.ParseException("class header");
		}
		ApiModel.ClassDef classDef = model.classNamed(matcher.group(1));
		if (matcher.group(2) != null && classDef.typeParams.isEmpty()) {
			Arrays.stream(matcher.group(2).split(",")).map(String::trim).forEach(classDef.typeParams::add);
		}
		if (matcher.group(3) != null) {
			classDef.superclass = matcher.group(3);
		}
		if (classDef.description.isEmpty()) {
			classDef.description = String.join("\n", description);
		}
		classDef.files.add(file);
		description.clear();
		openClass = classDef;
	}

	private void fieldTag(String line, int lineNumber) {
		Matcher matcher = NAME_THEN_REST.matcher(line);
		ApiModel.ClassDef target = openClass;
		if (target == null || !matcher.matches()) {
			unparsed("@field outside a class block", lineNumber, line);
			return;
		}
		String name = matcher.group(2);
		String rest = matcher.group(3);
		Matcher visible = VISIBLE_FIELD.matcher(rest);
		if (VISIBILITIES.contains(name) && visible.matches()) {
			// ---@field private listeners Listener[]: only a public field is part of the API
			if (!name.equals("public")) {
				return;
			}
			name = visible.group(1);
			rest = visible.group(2);
		}
		TypeParser.Result result = TypeParser.parsePrefix(rest, 0);
		String fieldDescription = stripHash(rest.substring(result.end()));
		boolean readOnly = fieldDescription.startsWith(READ_ONLY);
		// Writeable alone: "(Writeable: Using Swap())" is a value changed through a method, not by assigning it
		boolean writable = fieldDescription.startsWith(WRITABLE);
		if (readOnly || writable) {
			fieldDescription = fieldDescription.substring((readOnly ? READ_ONLY : WRITABLE).length()).strip();
		}
		// ---@field npcData? NPCData: the mark on the name makes the field nullable
		TypeRef fieldType = name.endsWith("?") && !result.type().isNullable()
				? new TypeRef.Optional(result.type())
				: result.type();
		target.fields.add(new ApiModel.FieldDef(stripOptionalMark(name), fieldType, fieldDescription, readOnly,
				writable, false));
	}

	/** {@code ---@operator add(Vector3): Vector3}, or for a unary operator {@code ---@operator unm: Vector3}. */
	private void operatorTag(String line, int lineNumber) {
		Matcher matcher = OPERATOR_TAG.matcher(line);
		ApiModel.ClassDef target = openClass;
		if (target == null || !matcher.matches()) {
			unparsed("@operator outside a class block, or of another form", lineNumber, line);
			return;
		}
		String operand = matcher.group(2);
		TypeRef other = operand == null ? null : TypeParser.parse(operand);
		target.operators.add(
				new ApiModel.OperatorDef(matcher.group(1), other, TypeParser.parsePrefix(matcher.group(3), 0).type()));
	}

	private void paramTag(String line) {
		closeClass();
		Matcher matcher = NAME_THEN_REST.matcher(line);
		if (!matcher.matches()) {
			throw new TypeParser.ParseException("param header");
		}
		String name = matcher.group(2);
		String rest = matcher.group(3);
		TypeParser.Result result = TypeParser.parsePrefix(rest, 0);
		params.add(new ApiModel.ParamDef(stripOptionalMark(name), result.type(),
				stripHash(rest.substring(result.end())), name.endsWith("?")));
	}

	/**
	 * {@code ---@return Type name description}. A word after the type is the value's name, as LuaLS reads it, when it
	 * stands alone, comes before {@code #}, or starts in lower case; a word in upper case starts the description, so
	 * that {@code ---@return Handler The created handler} names nothing.
	 */
	private void returnTag(String line) {
		closeClass();
		String rest = rest(line, "return");
		TypeParser.Result result = TypeParser.parsePrefix(rest, 0);
		String after = rest.substring(result.end()).trim();
		String name = "";
		Matcher named = RETURN_NAME.matcher(after);
		if (named.matches()) {
			String following = named.group(2) == null ? "" : named.group(2);
			char first = named.group(1).charAt(0);
			if (following.isEmpty() || following.startsWith("#") || Character.isLowerCase(first) || first == '_') {
				name = named.group(1);
				after = following;
			}
		}
		returns.add(new ApiModel.ReturnDef(result.type(), name, stripHash(after)));
	}

	private void aliasTag(String line, int lineNumber) {
		closeClass();
		Matcher matcher = NAME_THEN_REST.matcher(line);
		if (!matcher.matches()) {
			throw new TypeParser.ParseException("alias header");
		}
		if (matcher.group(3).isBlank()) {
			// ---@alias Mode, then one ---| "value" line for each value
			openAlias = matcher.group(2);
			openAliasLine = lineNumber;
			return;
		}
		TypeParser.Result result = TypeParser.parsePrefix(matcher.group(3), 0);
		model.aliases.put(matcher.group(2), new ApiModel.AliasDef(matcher.group(2), result.type(), file));
	}

	private void aliasOption(String text, int line) {
		try {
			aliasOptions.add(TypeParser.parsePrefix(text.strip(), 0).type());
		} catch (TypeParser.ParseException exception) {
			unparsed("@alias value not understood: " + exception.getMessage(), line, "---|" + text);
		}
	}

	private void finishAlias() {
		String name = openAlias;
		if (name == null) {
			return;
		}
		if (aliasOptions.isEmpty()) {
			unparsed("@alias without a type", openAliasLine, lineText(openAliasLine));
		} else {
			TypeRef target = aliasOptions.size() == 1 ? aliasOptions.getFirst() : new TypeRef.Union(aliasOptions);
			model.aliases.put(name, new ApiModel.AliasDef(name, target, file));
		}
		openAlias = null;
		aliasOptions.clear();
	}

	private void enumTag(String line) {
		closeClass();
		Matcher matcher = ENUM_TAG.matcher(line);
		if (!matcher.matches()) {
			throw new TypeParser.ParseException("enum header");
		}
		openEnum = matcher.group(2);
		openEnumKeys = "key".equals(matcher.group(1));
		openEnumDescription = String.join("\n", description);
		description.clear();
	}

	/** {@code ---@generic T, K : Base description}: the names, each with an optional constraint, then free text. */
	private void genericTag(String line) {
		closeClass();
		Matcher matcher = GENERIC_NAME.matcher(rest(line, "generic"));
		while (matcher.find()) {
			typeParams.add(matcher.group(1));
			if (matcher.group(2).isEmpty()) {
				break;
			}
		}
	}

	private void descriptionLine(String text) {
		// A description after a class's fields starts the next block, where a file stacks bare classes back to back
		closeClass();
		description.add(text);
	}

	private void closeClass() {
		openClass = null;
	}

	private void resetBlock() {
		finishAlias();
		closeClass();
		description.clear();
		params.clear();
		returns.clear();
		overloads.clear();
		typeParams.clear();
		varargs = false;
		deprecated = false;
		hidden = false;
		type = null;
		openEnum = null;
		openEnumKeys = false;
		openEnumDescription = "";
	}

	// ---- Statements

	private void statement(LuaSyntax.Statement statement) {
		if (statement.line() > lastLine + 1) {
			// A blank line between the annotations and the code: the code has none
			resetBlock();
		}
		finishAlias();
		// The fields of a table constructor find their annotations after this line
		lastLine = statement.line();
		switch (statement) {
			case LuaSyntax.Local local -> local(local);
			case LuaSyntax.Assignment assignment -> assignment(assignment);
			case LuaSyntax.FunctionStatement function -> functionStatement(function);
			case LuaSyntax.LocalFunction function -> locals.put(function.name(),
					new FunctionLocal(function("", function.name(), ApiModel.CallStyle.DOT, function.parameters())));
			case LuaSyntax.Return returned -> returnStatement(returned);
			case LuaSyntax.OtherStatement other -> notUnderstood(other);
		}
		resetBlock();
		lastLine = statement.endLine();
	}

	private void local(LuaSyntax.Local local) {
		if (local.names().size() != 1 || local.values().size() > 1) {
			local.names().forEach(name -> locals.put(name, new ValueLocal()));
			notUnderstood(local);
			return;
		}
		String name = local.names().getFirst();
		LuaSyntax.Expression value = local.values().isEmpty() ? null : local.values().getFirst();
		String enumName = openEnum;
		if (enumName != null && value instanceof LuaSyntax.Table table) {
			locals.put(name, new EnumLocal(enumTable(enumName, table, name, local.line())));
			return;
		}
		ApiModel.ClassDef declared = openClass;
		if (declared != null) {
			locals.put(name, new TableLocal(declared, false, local.line()));
			if (declared.binding == ApiModel.Binding.NONE) {
				declared.binding = ApiModel.Binding.LOCAL;
			}
			if (value instanceof LuaSyntax.Table table) {
				members(declared, table);
			}
			return;
		}
		switch (value) {
			case LuaSyntax.Table table -> {
				ApiModel.ClassDef provisional = provisionalClass();
				locals.put(name, new TableLocal(provisional, true, local.line()));
				members(provisional, table);
			}
			case LuaSyntax.Require require -> locals.put(name, new ModuleLocal(require.module()));
			case LuaSyntax.FunctionLiteral function -> locals.put(name,
					new FunctionLocal(literalFunction("", name, function)));
			case null, default -> locals.put(name, new ValueLocal());
		}
	}

	private void assignment(LuaSyntax.Assignment assignment) {
		if (assignment.targets().size() != 1 || assignment.values().size() != 1
				|| assignment.targets().getFirst().isEmpty()) {
			notUnderstood(assignment);
			return;
		}
		List<String> target = assignment.targets().getFirst();
		LuaSyntax.Expression value = assignment.values().getFirst();
		String root = target.getFirst();
		if (locals.containsKey(root)) {
			if (target.size() == 1) {
				// x = function(...) after local x
				if (value instanceof LuaSyntax.FunctionLiteral function) {
					locals.put(root, new FunctionLocal(literalFunction("", root, function)));
				}
				return;
			}
			ApiModel.ClassDef owner = localTable(target.subList(0, target.size() - 1));
			if (owner == null) {
				notUnderstood(assignment);
				return;
			}
			member(owner, target.getLast(), value, assignment.line());
			return;
		}
		String tableName = String.join(".", target);
		String enumName = openEnum;
		if (enumName != null && value instanceof LuaSyntax.Table table) {
			enumTable(enumName, table, tableName, assignment.line());
			return;
		}
		if (value instanceof LuaSyntax.Table || openClass != null) {
			ApiModel.ClassDef bound = globalTable(tableName);
			if (value instanceof LuaSyntax.Table table) {
				members(bound, table);
			}
			return;
		}
		notUnderstood(assignment);
	}

	private void functionStatement(LuaSyntax.FunctionStatement statement) {
		List<String> path = statement.path();
		String name = path.getLast();
		ApiModel.CallStyle style = statement.method() ? ApiModel.CallStyle.COLON : ApiModel.CallStyle.DOT;
		if (path.size() == 1) {
			if (locals.containsKey(name)) {
				// function f() after local f: the local's value
				locals.put(name, new FunctionLocal(function("", name, style, statement.parameters())));
			} else if (!hidden) {
				model.globalFunctions.add(function("", name, style, statement.parameters()));
			}
			return;
		}
		if (hidden) {
			return;
		}
		List<String> ownerPath = path.subList(0, path.size() - 1);
		if (locals.containsKey(ownerPath.getFirst())) {
			ApiModel.ClassDef owner = localTable(ownerPath);
			if (owner == null) {
				notUnderstood(statement);
			} else {
				owner.functions.add(function(owner.name, name, style, statement.parameters()));
			}
			return;
		}
		String rawOwner = String.join(".", ownerPath);
		unresolvedFunctions.add(new PendingFunction(rawOwner,
				function(rawOwner, name, style, statement.parameters()), definitions));
	}

	private void returnStatement(LuaSyntax.Return statement) {
		if (statement.values().size() != 1) {
			notUnderstood(statement);
			return;
		}
		LuaSyntax.Expression value = statement.values().getFirst();
		if (value instanceof LuaSyntax.Path path && path.names().size() == 1) {
			switch (locals.get(path.names().getFirst())) {
				case TableLocal table -> {
					export(table.type(), table.provisional(), statement.line());
					return;
				}
				case ModuleLocal module -> {
					reexports.add(new PendingExport(modulePath, module.module(), file, definitions));
					return;
				}
				case null, default -> {
				}
			}
		}
		switch (value) {
			case LuaSyntax.Require require ->
					reexports.add(new PendingExport(modulePath, require.module(), file, definitions));
			case LuaSyntax.Table table -> {
				ApiModel.ClassDef declared = openClass;
				ApiModel.ClassDef export = declared != null ? declared : provisionalClass();
				members(export, table);
				export(export, declared == null, statement.line());
			}
			default -> notUnderstood(statement);
		}
	}

	/** {@code owner.name = value}, in an assignment or a table constructor. */
	private void member(ApiModel.ClassDef owner, String name, LuaSyntax.Expression value, int line) {
		if (name.startsWith("__") || hidden || hasMember(owner, name)) {
			// Metatable plumbing such as __index, a member the annotations hide, or one declared already
			return;
		}
		String enumName = openEnum;
		if (enumName != null && value instanceof LuaSyntax.Table table) {
			owner.tables.put(name, enumTable(enumName, table, null, line));
			return;
		}
		ApiModel.ClassDef declared = openClass;
		if (declared != null) {
			// M.Sub = {} after ---@class M.Sub: the class's table, in a field of M's
			owner.tables.put(name, declared.name);
			if (value instanceof LuaSyntax.Table table) {
				members(declared, table);
			}
			return;
		}
		if (value instanceof LuaSyntax.Path path && path.names().size() == 1) {
			switch (locals.get(path.names().getFirst())) {
				case FunctionLocal function -> {
					owner.functions.add(function.function().withOwner(owner.name).withName(name));
					return;
				}
				case TableLocal table when !table.provisional() -> {
					owner.tables.put(name, table.type().name);
					return;
				}
				case EnumLocal enumLocal -> {
					owner.tables.put(name, enumLocal.name());
					return;
				}
				case ModuleLocal module -> {
					moduleTable(owner, name, module.module(), line);
					return;
				}
				case null, default -> {
				}
			}
		}
		switch (value) {
			case LuaSyntax.FunctionLiteral function -> owner.functions.add(literalFunction(owner.name, name, function));
			case LuaSyntax.Require require -> moduleTable(owner, name, require.module(), line);
			default -> {
				TypeRef declaredType = type;
				TypeRef fieldType = declaredType != null ? declaredType : literalType(value);
				owner.fields.add(new ApiModel.FieldDef(name, fieldType, String.join("\n", description), false, false,
						deprecated));
			}
		}
	}

	/** The named fields of a table constructor, each as if assigned to the table once it is made. */
	private void members(ApiModel.ClassDef owner, LuaSyntax.Table table) {
		resetBlock();
		for (LuaSyntax.Field field : table.fields()) {
			comments(field.comments());
			if (field.line() > lastLine + 1) {
				resetBlock();
			}
			String name = field.name();
			if (name != null) {
				member(owner, name, field.value(), field.line());
			}
			resetBlock();
			lastLine = field.endLine();
		}
	}

	/** The enum the open {@code ---@enum} names, with its table's values; returns its name. */
	private String enumTable(String name, LuaSyntax.Table table, @Nullable String tableName, int line) {
		List<ApiModel.EnumValue> values = new ArrayList<>();
		for (LuaSyntax.Field field : table.fields()) {
			String fieldName = field.name();
			String fieldKey = field.key();
			String key = fieldName != null ? fieldName : fieldKey != null ? "[\"" + fieldKey + "\"]" : null;
			String literal = field.value() instanceof LuaSyntax.Literal value
					? switch (value.kind()) {
						case NUMBER -> value.value();
						case STRING -> "\"" + value.value() + "\"";
						case BOOLEAN, NIL -> null;
					}
					: null;
			if (key == null || literal == null) {
				unparsed("enum entry", field.line(), lineText(field.line()));
				continue;
			}
			LuaSyntax.Comment trailing = field.trailing();
			values.add(new ApiModel.EnumValue(key, literal, trailing == null ? "" : trailing.text().strip()));
		}
		if (tableName != null && !name.equals(tableName)) {
			unparsed("@enum name differs from its table", line, name + " vs " + tableName);
		}
		model.enums.put(name, new ApiModel.EnumDef(name, openEnumDescription, values, file, openEnumKeys, false));
		return name;
	}

	/**
	 * Each class that is an enum whose values the definitions do not give, as an enum of placeholder values, each its
	 * own name as a string, which {@link ApiModel.EnumDef#placeholderValued} tells apart. Such a class has a global
	 * table, fields each of the class's own type, and no function but {@code FromID}: {@code PluginPermissions}, whose
	 * values are the client's own objects.
	 */
	private void valueClassesAsEnums() {
		for (ApiModel.ClassDef classDef : List.copyOf(model.classes.values())) {
			boolean ownTypeFields = !classDef.fields.isEmpty() && classDef.fields.stream()
					.allMatch(field -> field.type() instanceof TypeRef.Named named && named.args().isEmpty()
							&& named.name().equals(classDef.name));
			boolean fromIdAlone = classDef.functions.stream().allMatch(function -> function.name().equals(FROM_ID));
			if (classDef.binding != ApiModel.Binding.GLOBAL || !ownTypeFields || !fromIdAlone) {
				continue;
			}
			List<ApiModel.EnumValue> values = classDef.fields.stream()
					.map(field -> new ApiModel.EnumValue(field.name(), "\"" + field.name() + "\"", field.description()))
					.toList();
			String definedIn = classDef.files.isEmpty() ? "" : classDef.files.getFirst();
			model.enums.put(classDef.name, new ApiModel.EnumDef(classDef.name, classDef.description, values, definedIn,
					false, !classDef.functions.isEmpty()));
			model.classes.remove(classDef.name);
		}
	}

	/** {@code Player = {}}: a global table, of the open class, or of a class named by the table. */
	private ApiModel.ClassDef globalTable(String tableName) {
		ApiModel.ClassDef target = openClass;
		if (target == null) {
			// World.RemoveRoof = {}: a nested namespace of functions, declared without a @class
			target = model.classNamed(tableName);
			target.files.add(file);
		}
		globalTables.put(tableName, target.name);
		target.binding = ApiModel.Binding.GLOBAL;
		return target;
	}

	/** The class of the local table a path names, {@code disposable.DisposableGroup}, or null when it names none. */
	private ApiModel.@Nullable ClassDef localTable(List<String> path) {
		if (!(locals.get(path.getFirst()) instanceof TableLocal root)) {
			return null;
		}
		ApiModel.ClassDef current = root.type();
		for (String segment : path.subList(1, path.size())) {
			String table = current.tables.get(segment);
			ApiModel.ClassDef next = table == null ? null : model.classes.get(table);
			if (next == null) {
				return null;
			}
			current = next;
		}
		return current;
	}

	private ApiModel.ClassDef provisionalClass() {
		ApiModel.ClassDef provisional = new ApiModel.ClassDef(modulePath);
		provisional.files.add(file);
		return provisional;
	}

	/** The file's export: its module, and a provisional class, which joins the model. */
	private void export(ApiModel.ClassDef exportType, boolean provisional, int line) {
		if (provisional) {
			if (model.classes.containsKey(exportType.name)) {
				unparsed("a module's table without a class, where a class has the module's name", line,
						lineText(line));
				return;
			}
			model.classes.put(exportType.name, exportType);
			exported = exportType;
		}
		model.modules.put(modulePath, new ApiModel.ModuleDef(modulePath, exportType.name, file, provisional));
	}

	/** A field that holds a module's table, known once every file is read; it keeps its place among the tables. */
	private void moduleTable(ApiModel.ClassDef owner, String name, String module, int line) {
		owner.tables.put(name, "");
		moduleTables.add(new PendingModuleTable(owner, name, module, file, line, lineText(line), definitions));
	}

	private ApiModel.FunctionDef literalFunction(String owner, String name, LuaSyntax.FunctionLiteral function) {
		List<String> parameters = function.parameters();
		boolean method = !parameters.isEmpty() && parameters.getFirst().equals("self");
		return method
				? function(owner, name, ApiModel.CallStyle.COLON, parameters.subList(1, parameters.size()))
				: function(owner, name, ApiModel.CallStyle.DOT, parameters);
	}

	/** A function with the annotations of the block; a colon function's {@code self} is implicit, so its tag goes. */
	private ApiModel.FunctionDef function(String owner, String name, ApiModel.CallStyle style, List<String> luaParams) {
		List<ApiModel.ParamDef> declared = style == ApiModel.CallStyle.COLON
				? params.stream().filter(param -> !param.name().equals("self")).toList()
				: List.copyOf(params);
		return new ApiModel.FunctionDef(owner, name, style, luaParams, declared, List.copyOf(returns),
				List.copyOf(overloads), varargs || luaParams.contains("..."), String.join("\n", description), file,
				List.copyOf(typeParams), deprecated);
	}

	private static boolean hasMember(ApiModel.ClassDef owner, String name) {
		return owner.tables.containsKey(name)
				|| owner.fields.stream().anyMatch(field -> field.name().equals(name))
				|| owner.functions.stream().anyMatch(function -> function.name().equals(name));
	}

	private static TypeRef literalType(LuaSyntax.Expression value) {
		if (value instanceof LuaSyntax.Table) {
			return TypeRef.Named.of("table");
		}
		if (!(value instanceof LuaSyntax.Literal literal)) {
			return TypeRef.Named.of("any");
		}
		return switch (literal.kind()) {
			case STRING -> TypeRef.Named.of("string");
			case NUMBER -> TypeRef.Named.of(
					new TypeRef.NumberLiteral(literal.value()).isInteger() ? "integer" : "number");
			case BOOLEAN -> TypeRef.Named.of("boolean");
			case NIL -> TypeRef.Named.of("any");
		};
	}

	/** A statement that declares nothing the reader understands, which a definition file should not hold. */
	private void notUnderstood(LuaSyntax.Statement statement) {
		if (definitions) {
			unparsed("code", statement.line(), lineText(statement.line()));
		}
	}

	private String lineText(int line) {
		return line >= 1 && line <= lines.size() ? lines.get(line - 1) : "";
	}

	private void unparsed(String reason, int lineNumber, String line) {
		model.unparsed.add(new ApiModel.Unparsed(reason, file, lineNumber, line.strip()));
	}

	private static String stripOptionalMark(String name) {
		return name.endsWith("?") ? name.substring(0, name.length() - 1) : name;
	}

	private static String stripHash(String text) {
		String trimmed = text.trim();
		return trimmed.startsWith("#") ? trimmed.substring(1).trim() : trimmed;
	}
}
