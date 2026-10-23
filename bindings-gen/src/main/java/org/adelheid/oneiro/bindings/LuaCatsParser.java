package org.adelheid.oneiro.bindings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Line-based parser for the stubs' {@code ---@meta} files.
 *
 * <p>The stubs follow a regular layout: a block of {@code ---} lines (description, then tags) followed by one line of
 * code that the block annotates. A {@code ---@class} block with its {@code ---@field} lines is followed by the class's
 * table ({@code X = {}} or {@code local X = {}}) or by a blank line when the class has no table. An {@code ---@enum}
 * block is followed by a table literal. A function block is followed by {@code function Owner.Name(...) end} or
 * {@code function Owner:Name(...) end}. Anything else is recorded as unparsed, so the generator can report it.
 */
final class LuaCatsParser {

	private static final Pattern CLASS_TAG =
			Pattern.compile("^---@class\\s+([\\w.]+)(?:<([^>]*)>)?(?:\\s*:\\s*([\\w.]+))?\\s*(.*)$");
	private static final Pattern NAME_THEN_REST = Pattern.compile("^---@(field|param|alias|vararg)\\s+(\\S+)\\s*(.*)$");
	private static final Pattern TABLE_DECLARATION =
			Pattern.compile("^(local\\s+)?([A-Za-z_][\\w.]*)\\s*=\\s*\\{\\s*}\\s*$");
	private static final Pattern TABLE_LITERAL_START = Pattern.compile(
			"^(local\\s+)?([A-Za-z_][\\w.]*)\\s*=\\s*\\{\\s*$");
	private static final Pattern FUNCTION =
			Pattern.compile("^function\\s+(?:([A-Za-z_][\\w.]*)([.:]))?([A-Za-z_]\\w*)\\s*\\(([^)]*)\\)\\s*end\\s*$");
	private static final Pattern ENUM_ENTRY = Pattern.compile(
			"^\\s*([A-Za-z_]\\w*|\\[\"[^\"]*\"])\\s*=\\s*(\"[^\"]*\"|-?[0-9][0-9xA-Fa-f.]*)\\s*,?\\s*(?:--\\s*(.*))?$");
	private static final Pattern NAMED_RETURN = Pattern.compile("^([A-Za-z_]\\w*)\\s*(#.*)?$");

	/** A function whose owner table could not be resolved while its file was being read. */
	private record PendingFunction(String rawOwner, ApiModel.FunctionDef function) {
	}

	private final ApiModel model;
	private final Map<String, String> globalTables = new HashMap<>();
	private final List<PendingFunction> unresolvedFunctions = new ArrayList<>();

	// The file being read
	private String file = "";
	private Map<String, String> localTables = new HashMap<>();
	private final List<String> description = new ArrayList<>();
	private final List<ApiModel.ParamDef> params = new ArrayList<>();
	private final List<ApiModel.ReturnDef> returns = new ArrayList<>();
	private final List<TypeRef> overloads = new ArrayList<>();
	private boolean varargs;
	private ApiModel.@Nullable ClassDef openClass;
	private @Nullable String openEnum;
	private String openEnumDescription = "";

	LuaCatsParser(ApiModel model) {
		this.model = model;
	}

	/** Reads one stub file into the model. {@code displayName} is the path shown in messages. */
	void parseFile(Path path, String displayName) throws IOException {
		file = displayName;
		localTables = new HashMap<>();
		resetBlock();
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		for (int index = 0; index < lines.size(); index++) {
			String line = lines.get(index).stripTrailing();
			int lineNumber = index + 1;
			if (line.isBlank()) {
				resetBlock();
			} else if (line.startsWith("---@")) {
				tag(line, lineNumber);
			} else if (line.startsWith("---")) {
				descriptionLine(line.substring(3));
			} else if (!line.startsWith("--")) {
				// Plain "--" comments are section headings in types.lua; they annotate nothing
				index = code(lines, index, line, lineNumber);
			}
		}
		resetBlock();
	}

	/** Attaches functions whose owner is a global table declared in a later file, and records the rest as unparsed. */
	void finish() {
		for (PendingFunction pending : unresolvedFunctions) {
			String owner = globalTables.getOrDefault(pending.rawOwner(), pending.rawOwner());
			ApiModel.FunctionDef function = pending.function().withOwner(owner);
			ApiModel.ClassDef classDef = model.classes.get(owner);
			if (classDef != null) {
				classDef.functions.add(function);
			} else {
				model.unparsed.add(new ApiModel.Unparsed("function on unknown table " + pending.rawOwner(),
						function.file(), 0, "function " + pending.rawOwner() + "." + function.name()));
			}
		}
	}

	private void tag(String line, int lineNumber) {
		String tagName = line.substring(4).split("\\s", 2)[0];
		try {
			switch (tagName) {
				case "meta" -> {
				}
				case "class" -> classTag(line);
				case "field" -> fieldTag(line, lineNumber);
				case "param" -> paramTag(line);
				case "return" -> returnTag(line);
				case "overload" -> {
					closeClass();
					overloads.add(TypeParser.parsePrefix(line, "---@overload".length() + 1).type());
				}
				case "vararg" -> {
					closeClass();
					varargs = true;
				}
				case "alias" -> aliasTag(line);
				case "enum" -> {
					closeClass();
					openEnum = line.substring("---@enum".length()).trim();
					openEnumDescription = String.join("\n", description);
					description.clear();
				}
				default -> unparsed("unknown tag @" + tagName, lineNumber, line);
			}
		} catch (TypeParser.ParseException exception) {
			unparsed("@" + tagName + " type not understood: " + exception.getMessage(), lineNumber, line);
		}
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
		TypeParser.Result result = TypeParser.parsePrefix(rest, 0);
		String fieldDescription = stripHash(rest.substring(result.end()));
		boolean readOnly = fieldDescription.startsWith("(Read-only)");
		if (readOnly) {
			fieldDescription = fieldDescription.substring("(Read-only)".length()).strip();
		}
		// ---@field npcData? NPCData: the mark on the name makes the field nullable
		TypeRef type = name.endsWith("?") && !result.type().isNullable()
				? new TypeRef.Optional(result.type())
				: result.type();
		target.fields.add(new ApiModel.FieldDef(stripOptionalMark(name), type, fieldDescription, readOnly));
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

	private void returnTag(String line) {
		closeClass();
		String rest = line.substring("---@return".length()).trim();
		TypeParser.Result result = TypeParser.parsePrefix(rest, 0);
		String after = rest.substring(result.end()).trim();
		String name = "";
		if (!after.isEmpty() && !after.startsWith("#")) {
			// LuaCATS allows "---@return Type name # description"; the stubs mostly leave the name out
			Matcher named = NAMED_RETURN.matcher(after);
			if (named.matches()) {
				name = named.group(1);
				after = named.group(2) == null ? "" : named.group(2);
			}
		}
		returns.add(new ApiModel.ReturnDef(result.type(), name, stripHash(after)));
	}

	private void aliasTag(String line) {
		closeClass();
		Matcher matcher = NAME_THEN_REST.matcher(line);
		if (!matcher.matches()) {
			throw new TypeParser.ParseException("alias header");
		}
		TypeParser.Result result = TypeParser.parsePrefix(matcher.group(3), 0);
		model.aliases.put(matcher.group(2), new ApiModel.AliasDef(matcher.group(2), result.type(), file));
	}

	private void descriptionLine(String text) {
		// A description after a class's fields starts the next block (types.lua stacks bare classes back to back)
		closeClass();
		description.add(text);
	}

	/** Handles one code line; returns the index of the last line consumed, as table literals span several lines. */
	private int code(List<String> lines, int index, String line, int lineNumber) {
		Matcher table = TABLE_DECLARATION.matcher(line);
		if (table.matches()) {
			bindTable(table.group(2), table.group(1) != null, lineNumber, line);
			resetBlock();
			return index;
		}
		Matcher literal = TABLE_LITERAL_START.matcher(line);
		if (literal.matches()) {
			int end = enumBody(lines, index, literal.group(2), lineNumber, line);
			resetBlock();
			return end;
		}
		Matcher function = FUNCTION.matcher(line);
		if (function.matches()) {
			function(function);
			resetBlock();
			return index;
		}
		unparsed("code", lineNumber, line);
		resetBlock();
		return index;
	}

	private void bindTable(String tableName, boolean isLocal, int lineNumber, String line) {
		ApiModel.ClassDef target = openClass;
		if (target == null) {
			if (isLocal) {
				unparsed("local table without a class", lineNumber, line);
				return;
			}
			// World.RemoveRoof = {}: a nested namespace of functions, declared without a @class
			target = model.classNamed(tableName);
			target.files.add(file);
			model.implicitTables.add(tableName);
		}
		if (isLocal) {
			localTables.put(tableName, target.name);
			if (target.binding == ApiModel.Binding.NONE) {
				target.binding = ApiModel.Binding.LOCAL;
			}
		} else {
			globalTables.put(tableName, target.name);
			target.binding = ApiModel.Binding.GLOBAL;
		}
		openClass = null;
	}

	private int enumBody(List<String> lines, int start, String tableName, int lineNumber, String line) {
		String name = openEnum;
		if (name == null) {
			unparsed("table literal without @enum", lineNumber, line);
		}
		List<ApiModel.EnumValue> values = new ArrayList<>();
		int index = start + 1;
		for (; index < lines.size(); index++) {
			String entry = lines.get(index).stripTrailing();
			if (entry.trim().equals("}")) {
				break;
			}
			Matcher matcher = ENUM_ENTRY.matcher(entry);
			if (matcher.matches()) {
				values.add(new ApiModel.EnumValue(matcher.group(1), matcher.group(2),
						matcher.group(3) == null ? "" : matcher.group(3).trim()));
			} else {
				unparsed("enum entry", index + 1, entry);
			}
		}
		if (name != null) {
			if (!name.equals(tableName)) {
				unparsed("@enum name differs from its table", lineNumber, name + " vs " + tableName);
			}
			model.enums.put(name, new ApiModel.EnumDef(name, openEnumDescription, values, file));
		}
		return index;
	}

	private void function(Matcher matcher) {
		String rawOwner = matcher.group(1) == null ? "" : matcher.group(1);
		ApiModel.CallStyle style = ":".equals(matcher.group(2)) ? ApiModel.CallStyle.COLON : ApiModel.CallStyle.DOT;
		List<String> luaParams = Arrays.stream(matcher.group(4).split(","))
				.map(String::trim)
				.filter(param -> !param.isEmpty())
				.toList();
		ApiModel.FunctionDef function = new ApiModel.FunctionDef(rawOwner, matcher.group(3), style, luaParams,
				List.copyOf(params), List.copyOf(returns), List.copyOf(overloads), varargs || luaParams.contains("..."),
				String.join("\n", description), file);
		String localOwner = localTables.get(rawOwner);
		if (rawOwner.isEmpty()) {
			model.globalFunctions.add(function);
		} else if (localOwner != null) {
			model.classNamed(localOwner).functions.add(function.withOwner(localOwner));
		} else {
			unresolvedFunctions.add(new PendingFunction(rawOwner, function));
		}
	}

	private void closeClass() {
		openClass = null;
	}

	private void resetBlock() {
		closeClass();
		description.clear();
		params.clear();
		returns.clear();
		overloads.clear();
		varargs = false;
		openEnum = null;
		openEnumDescription = "";
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
