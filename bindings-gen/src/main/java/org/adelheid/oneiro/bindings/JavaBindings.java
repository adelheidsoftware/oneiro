package org.adelheid.oneiro.bindings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.lang.model.SourceVersion;
import org.adelheid.oneiro.api.LuaConstants;
import org.jspecify.annotations.Nullable;

/**
 * Writes the Java sources of the RS3 bindings from the stubs' model and the overrides: one class per global table,
 * with nested classes for nested tables, so that {@code ui.Text} in Lua is {@code ui.Text} in Java. Each class and
 * member carries the annotations that tell Oneiro its Lua shape, and the Javadoc of the RS3
 * documentation the stubs were generated from. The names of the {@code id}, {@code config} and {@code var} namespaces
 * are not written as sources: they are returned for {@link ConstantChunks}, and the namespace classes extend them.
 */
final class JavaBindings {

	static final String PACKAGE = "org.adelheid.oneiro.rs3";
	static final int CHUNK_SIZE = 20_000;

	private static final int MAX_UNION_OVERLOADS = 8;
	private static final String NULLABLE = "@Nullable";
	private static final List<String> ANNOTATION_IMPORTS = List.of("org.adelheid.oneiro.api.LuaColon",
			"org.adelheid.oneiro.api.LuaCopy", "org.adelheid.oneiro.api.LuaDevOnly", "org.adelheid.oneiro.api.LuaDot",
			"org.adelheid.oneiro.api.LuaEvent", "org.adelheid.oneiro.api.LuaFromId",
			"org.adelheid.oneiro.api.LuaGlobal", "org.adelheid.oneiro.api.LuaIndex",
			"org.adelheid.oneiro.api.LuaManifestFlag", "org.adelheid.oneiro.api.LuaName",
			"org.adelheid.oneiro.api.LuaOperator", "org.adelheid.oneiro.api.LuaSame",
			"org.adelheid.oneiro.api.LuaPermission", "org.adelheid.oneiro.api.LuaReturnValues",
			"org.adelheid.oneiro.api.LuaType", "org.jspecify.annotations.Nullable");
	/** The JDK types the bindings use, which the generator names fully until {@link #file} imports them. */
	private static final Pattern JDK_TYPE = Pattern.compile("\\bjava\\.util\\.(?:function\\.)?[A-Z]\\w*");
	/** Methods of {@code Object} that a binding cannot declare with the same parameters. */
	private static final Set<String> OBJECT_METHODS = Set.of("getClass()", "hashCode()", "equals(Object)", "toString()",
			"notify()", "notifyAll()", "wait()", "clone()", "finalize()");

	/** The names of one constant namespace, written as chunk classes by {@link ConstantChunks}. */
	record ConstantNamespace(String luaPath, String chunkPrefix, @Nullable String elementBinaryName,
			List<CsvConstants.Row> rows) {

		ConstantNamespace {
			rows = List.copyOf(rows);
		}

		int chunkCount() {
			return (rows.size() + CHUNK_SIZE - 1) / CHUNK_SIZE;
		}
	}

	/** How a Lua class becomes a Java class. */
	private enum Shape {
		/** A global table that is a namespace or a singleton: static members, colon functions with {@code @LuaColon}. */
		NAMESPACE,
		/** A global table of constructors and the type of the objects they make. */
		INSTANCE,
		/** Values without a table to reach: entities, payloads, settings and data shapes. */
		TYPE,
	}

	/** A Java type as a binding writes it, with its boxed form for type arguments. */
	private record JType(String text, String boxed, boolean primitive) {

		static final JType VOID = reference("void");
		static final JType OBJECT = reference("Object");

		static JType reference(String text) {
			return new JType(text, text, false);
		}

		static JType primitive(String text, String boxed) {
			return new JType(text, boxed, true);
		}

		JType nullable() {
			String base = primitive ? boxed : text;
			if (base.contains(NULLABLE)) {
				return reference(base);
			}
			// JSpecify's type-use annotation goes on the simple name: ui.@Nullable Component
			int generic = base.indexOf('<');
			int dot = base.lastIndexOf('.', generic < 0 ? base.length() : generic);
			String annotated = dot < 0
					? NULLABLE + " " + base
					: base.substring(0, dot + 1) + NULLABLE + " " + base.substring(dot + 1);
			return reference(annotated);
		}

		/** The type without annotations and type arguments, which decides whether two overloads clash. */
		String erased() {
			String plain = text.replace(NULLABLE + " ", "");
			int generic = plain.indexOf('<');
			return generic < 0 ? plain : plain.substring(0, generic);
		}
	}

	/** One Java parameter; the description is Javadoc text, already escaped. */
	private record JParam(String name, JType type, String description) {
	}

	/** One Java form of a Lua function: its parameters and what it returns. */
	private record Form(List<JParam> params, JType returned) {
	}

	/** A Lua parameter list and the Java return type that goes with it, before optional parameters are left out. */
	private record Variant(List<ApiModel.ParamDef> params, JType returned) {
	}

	/** A method a superclass declares: its erased return type, and whether it is static. */
	private record Inherited(String returned, boolean isStatic) {
	}

	/** An event of the client's {@code Event} table. */
	private record EventInfo(String name, String payload, boolean predicate) {
	}

	/** A class to write, at its place in the tree of nested classes. */
	private static final class Node {
		final String javaName;
		final String luaSegment;
		final @Nullable Node parent;
		final Map<String, Node> children = new TreeMap<>();
		ApiModel.@Nullable ClassDef classDef;
		ApiModel.@Nullable EnumDef enumDef;
		@Nullable EventInfo payloadOf;
		/** Holds only nested classes: the {@code id}, {@code config} and {@code var} tables. */
		boolean holder;
		/** For a namespace of the {@code id}, {@code config} or {@code var} tables: its name list. */
		@Nullable ConstantNamespace constants;
		@Nullable TypeRef fromId;
		/** For a namespace of the {@code id}, {@code config} or {@code var} tables: what a name looks up. */
		@Nullable TypeRef byName;
		/** For a table of named objects, such as {@code game.options}: the names, and the node of their type. */
		@Nullable List<String> elementNames;
		@Nullable Node elementType;

		Node(String javaName, String luaSegment, @Nullable Node parent) {
			this.javaName = javaName;
			this.luaSegment = luaSegment;
			this.parent = parent;
		}

		/** The class's reference from anywhere in the package: {@code ui.Text}. */
		String reference() {
			return parent == null ? javaName : parent.reference() + "." + javaName;
		}

		/** The class's binary name, as the JVM spells nested classes: {@code ui$Text}. */
		String binaryName() {
			return parent == null ? javaName : parent.binaryName() + "$" + javaName;
		}

		/** The Lua path of the class's table or type. */
		String luaName() {
			return parent == null ? luaSegment : parent.luaName() + "." + luaSegment;
		}
	}

	private final ApiModel model;
	private final Map<String, CsvConstants.Namespace> csvs;
	private final Overrides overrides;
	private final List<String> warnings;
	private final Map<String, Integer> typeUses;
	private final Set<String> parameterTypes = new HashSet<>();
	private final Map<String, Node> tops = new TreeMap<>();
	// Each Lua class and enum name, and each synthesized payload, by its node
	private final Map<String, Node> byLuaName = new HashMap<>();
	private final Map<String, EventInfo> events = new LinkedHashMap<>();
	private final List<ConstantNamespace> constants = new ArrayList<>();
	private final Set<String> writtenRecords = new HashSet<>();
	/** The classes the stubs lack that {@code field} lines declare, with the first line of each. */
	private final Map<String, Overrides.Entry> declaredClasses = new LinkedHashMap<>();
	/** The hook lines, by the Lua name of the table of hooks they describe, {@code ui.Hook}, in order. */
	private final Map<String, List<HookLine>> hooks = new LinkedHashMap<>();

	/**
	 * One hook constant: its Java and Lua names (the same, but for a second form of one hook), the callback interface
	 * it takes, and the callback's parameters after the component.
	 */
	private record HookLine(String javaName, String luaName, String callback, List<TypeRef.Param> params) {
	}

	JavaBindings(Stubs stubs, Overrides overrides, List<String> warnings) {
		this.model = stubs.model();
		this.csvs = stubs.namespaces();
		this.overrides = overrides;
		this.warnings = warnings;
		applyRenameOverrides();
		applyValuesOverrides();
		applyTypeOverrides();
		applyFieldOverrides();
		applyFunctionOverrides();
		applyParamOverrides();
		applySuperclassOverrides();
		collectHooks();
		for (Overrides.Entry entry : overrides.all("operator")) {
			int dot = entry.target().lastIndexOf('.');
			if (dot < 0 || !model.classes.containsKey(entry.target().substring(0, dot))) {
				warnings.add("overrides.txt:" + entry.line() + ": operator " + entry.target() + " needs a class of the"
						+ " stubs.");
			}
		}
		this.typeUses = model.typeUses();
		declaredClasses.forEach((name, entry) -> {
			if (typeUses.getOrDefault(name, 0) == 0) {
				warnings.add("overrides.txt:" + entry.line() + ": field " + entry.target() + " declares the class " + name
						+ ", which the stubs lack and no type line uses; check its name.");
			}
		});
		collectParameterTypes();
		collectEvents();
		buildTree();
	}

	/** The constant namespaces whose chunk classes the namespace classes extend. */
	List<ConstantNamespace> constants() {
		return constants;
	}

	/** Every source file, by its path under the source root. */
	Map<String, String> sources() {
		Map<String, String> files = new TreeMap<>();
		String folder = PACKAGE.replace('.', '/') + "/";
		files.put(folder + "package-info.java", packageInfo());
		files.put(folder + "LuaOnly.java", luaOnly());
		files.put(folder + "EventSubscription.java", subscription(false));
		files.put(folder + "EventPredicateSubscription.java", subscription(true));
		files.put(folder + "Globals.java", globals());
		Set<String> lowerCaseNames = new HashSet<>();
		for (String fixed : List.of("LuaOnly", "EventSubscription", "EventPredicateSubscription", "Globals")) {
			lowerCaseNames.add(fixed.toLowerCase(Locale.ROOT));
		}
		for (Node top : tops.values()) {
			if (!lowerCaseNames.add(top.javaName.toLowerCase(Locale.ROOT))) {
				throw new IllegalStateException("Two binding classes differ only in case, which some file systems cannot"
						+ " hold apart, or one takes the name of a fixed class: " + top.javaName);
			}
			StringBuilder source = new StringBuilder();
			writeNode(source, top, "");
			files.put(folder + top.javaName + ".java", file(source.toString(), nestedNames(top)));
		}
		return files;
	}

	// ---- Preparation

	/**
	 * {@code rename <Owner.member> <name>}: the client's name for a field or function the stubs misspell, keeping the
	 * stubs' type and description. The other lines name the member by its new name. Stubs that already use the new
	 * name, and not the old, have caught up with the line.
	 */
	private void applyRenameOverrides() {
		for (Overrides.Entry entry : overrides.all("rename")) {
			int dot = entry.target().lastIndexOf('.');
			ApiModel.ClassDef owner = dot < 0 ? null : model.classes.get(entry.target().substring(0, dot));
			if (owner == null || entry.arguments().size() != 1) {
				warnings.add("overrides.txt:" + entry.line() + ": rename " + entry.target() + " needs a class of the stubs"
						+ " and one new name.");
				continue;
			}
			String oldName = entry.target().substring(dot + 1);
			String newName = entry.arguments().getFirst();
			boolean renamed = false;
			for (int index = 0; index < owner.fields.size(); index++) {
				ApiModel.FieldDef field = owner.fields.get(index);
				if (field.name().equals(oldName)) {
					owner.fields.set(index, new ApiModel.FieldDef(newName, field.type(), field.description(),
							field.readOnly()));
					renamed = true;
				}
			}
			for (int index = 0; index < owner.functions.size(); index++) {
				ApiModel.FunctionDef function = owner.functions.get(index);
				if (function.name().equals(oldName)) {
					owner.functions.set(index, function.withName(newName));
					renamed = true;
				}
			}
			boolean hasNew = owner.fields.stream().anyMatch(field -> field.name().equals(newName))
					|| owner.functions.stream().anyMatch(function -> function.name().equals(newName));
			if (renamed) {
				overrides.applied(entry, false);
			} else if (hasNew) {
				overrides.applied(entry, true);
			} else {
				warnings.add("overrides.txt:" + entry.line() + ": rename " + entry.target() + " matches no field and no"
						+ " function, under either name.");
			}
		}
	}

	/**
	 * {@code values <enum> <NAME=literal...>}: the values the client gives an enum's names, where the stubs give others,
	 * such as each name as a string. Stubs that give the same values have caught up with the line.
	 */
	private void applyValuesOverrides() {
		for (Overrides.Entry entry : overrides.all("values")) {
			ApiModel.EnumDef enumDef = model.enums.get(entry.target());
			Map<String, String> given = new LinkedHashMap<>();
			for (String argument : entry.arguments()) {
				int equals = argument.indexOf('=');
				if (equals > 0) {
					given.put(argument.substring(0, equals), argument.substring(equals + 1));
				}
			}
			if (enumDef == null || given.size() != entry.arguments().size()) {
				warnings.add("overrides.txt:" + entry.line() + ": values " + entry.target() + " needs an enum of the"
						+ " stubs and NAME=value arguments.");
				continue;
			}
			List<ApiModel.EnumValue> values = new ArrayList<>();
			boolean agrees = true;
			for (ApiModel.EnumValue value : enumDef.values()) {
				String literal = given.remove(enumKey(value.name()));
				if (literal == null) {
					values.add(value);
				} else {
					agrees &= literal.equals(value.literal());
					values.add(new ApiModel.EnumValue(value.name(), literal, value.comment()));
				}
			}
			if (!given.isEmpty()) {
				warnings.add("overrides.txt:" + entry.line() + ": values " + entry.target() + " names values the stubs"
						+ " lack: " + String.join(", ", given.keySet()) + ".");
			}
			overrides.applied(entry, agrees);
			model.enums.put(enumDef.name(), new ApiModel.EnumDef(enumDef.name(), enumDef.description(), values,
					enumDef.file()));
		}
	}

	/**
	 * {@code type <Owner.member> <LuaCATS type>}: a field type the stubs got wrong, or the return type of a function,
	 * in every declaration of that name; a function the stubs give no return value gets this one.
	 */
	private void applyTypeOverrides() {
		for (Overrides.Entry entry : overrides.all("type")) {
			int dot = entry.target().lastIndexOf('.');
			ApiModel.ClassDef owner = dot < 0 ? null : model.classes.get(entry.target().substring(0, dot));
			String member = entry.target().substring(dot + 1);
			TypeRef type = TypeParser.parse(String.join(" ", entry.arguments()));
			boolean applied = false;
			if (owner != null) {
				for (int index = 0; index < owner.fields.size(); index++) {
					ApiModel.FieldDef field = owner.fields.get(index);
					if (field.name().equals(member)) {
						overrides.applied(entry, field.type().equals(type));
						owner.fields.set(index, new ApiModel.FieldDef(field.name(), type, field.description(),
								field.readOnly()));
						applied = true;
					}
				}
				for (int index = 0; index < owner.functions.size(); index++) {
					ApiModel.FunctionDef function = owner.functions.get(index);
					if (function.name().equals(member)) {
						List<ApiModel.ReturnDef> returns = new ArrayList<>(function.returns());
						if (returns.isEmpty()) {
							// A function the stubs give no return value, as Normalise, which returns the vector
							overrides.applied(entry, false);
							returns.add(new ApiModel.ReturnDef(type, "", ""));
						} else {
							ApiModel.ReturnDef first = returns.getFirst();
							overrides.applied(entry, first.type().equals(type));
							returns.set(0, new ApiModel.ReturnDef(type, first.name(), first.description()));
						}
						// An @overload that returns something returns the same, as AlignTo's two-argument form does
						List<TypeRef> overloads = function.overloads().stream()
								.map(overload -> overload instanceof TypeRef.Function form && !form.returns().isEmpty()
										? withFirstReturn(form, type)
										: overload)
								.toList();
						owner.functions.set(index, function.withReturns(returns).withOverloads(overloads));
						applied = true;
					}
				}
			}
			if (!applied) {
				warnings.add("overrides.txt:" + entry.line() + ": type " + entry.target()
						+ " matches no field and no function.");
			}
		}
	}

	private static TypeRef.Function withFirstReturn(TypeRef.Function form, TypeRef type) {
		List<TypeRef> returns = new ArrayList<>(form.returns());
		returns.set(0, type);
		return new TypeRef.Function(form.params(), returns);
	}

	/**
	 * {@code field <Owner.name> <LuaCATS type>}: a field the docs give and the stubs lack. Stubs that have the field with
	 * that type have caught up with the line; with another type, the line's type stands. A class the stubs lack is
	 * declared by its fields, as a shape the docs describe and a {@code type} line uses: the keyed table of an
	 * achievement's tests.
	 */
	private void applyFieldOverrides() {
		for (Overrides.Entry entry : overrides.all("field")) {
			int dot = entry.target().lastIndexOf('.');
			if (dot < 0 || entry.arguments().isEmpty()) {
				warnings.add("overrides.txt:" + entry.line() + ": field " + entry.target() + " needs a class and a type.");
				continue;
			}
			String ownerName = entry.target().substring(0, dot);
			ApiModel.ClassDef owner = model.classes.get(ownerName);
			if (owner == null) {
				owner = model.classNamed(ownerName);
				declaredClasses.put(ownerName, entry);
			}
			String name = entry.target().substring(dot + 1);
			TypeRef type = TypeParser.parse(String.join(" ", entry.arguments()));
			boolean present = false;
			for (int index = 0; index < owner.fields.size(); index++) {
				ApiModel.FieldDef field = owner.fields.get(index);
				if (field.name().equals(name)) {
					overrides.applied(entry, field.type().equals(type));
					owner.fields.set(index, new ApiModel.FieldDef(name, type, field.description(), field.readOnly()));
					present = true;
				}
			}
			if (!present) {
				overrides.applied(entry, false);
				owner.fields.add(new ApiModel.FieldDef(name, type, "", false));
			}
		}
	}

	/**
	 * {@code function <Owner.Name> <fun(...) type>}, or {@code <Owner:Name>} for a colon call: a function the docs give
	 * and the stubs lack, or whose form the stubs get wrong. Stubs that have the function with the same parameters and
	 * returns have caught up with the line; with others, the line's form stands. A further line for the same function
	 * adds another form of it, as an overload.
	 */
	private void applyFunctionOverrides() {
		Map<String, ApiModel.ClassDef> declared = new HashMap<>();
		for (Overrides.Entry entry : overrides.all("function")) {
			String target = entry.target();
			int separator = Math.max(target.lastIndexOf('.'), target.lastIndexOf(':'));
			ApiModel.ClassDef owner = separator < 0 ? null : model.classes.get(target.substring(0, separator));
			TypeRef type = entry.arguments().isEmpty() ? null : TypeParser.parse(String.join(" ", entry.arguments()));
			if (owner == null || !(type instanceof TypeRef.Function function)) {
				warnings.add("overrides.txt:" + entry.line() + ": function " + target + " needs a class of the stubs and a"
						+ " fun(...) type.");
				continue;
			}
			String name = target.substring(separator + 1);
			if (declared.get(target) == owner) {
				for (int index = 0; index < owner.functions.size(); index++) {
					ApiModel.FunctionDef existing = owner.functions.get(index);
					if (existing.name().equals(name)) {
						List<TypeRef> overloads = new ArrayList<>(existing.overloads());
						overloads.add(function);
						owner.functions.set(index, existing.withOverloads(overloads));
					}
				}
				overrides.applied(entry, false);
				continue;
			}
			declared.put(target, owner);
			ApiModel.CallStyle style = target.charAt(separator) == ':' ? ApiModel.CallStyle.COLON : ApiModel.CallStyle.DOT;
			List<TypeRef.Param> params = function.params().stream().filter(param -> !param.name().equals("...")).toList();
			ApiModel.FunctionDef added = new ApiModel.FunctionDef(owner.name, name, style,
					function.params().stream().map(TypeRef.Param::name).toList(),
					params.stream().map(param -> new ApiModel.ParamDef(param.name(), param.type(), "", false)).toList(),
					function.returns().stream().map(returned -> new ApiModel.ReturnDef(returned, "", "")).toList(),
					List.of(), params.size() < function.params().size(), "", "overrides.txt");
			boolean present = false;
			for (int index = 0; index < owner.functions.size(); index++) {
				ApiModel.FunctionDef existing = owner.functions.get(index);
				if (existing.name().equals(name)) {
					overrides.applied(entry, sameForm(existing, added));
					owner.functions.set(index, added);
					present = true;
				}
			}
			if (!present) {
				overrides.applied(entry, false);
				owner.functions.add(added);
			}
		}
	}

	/** Whether two declarations of a function are called and typed alike, whatever their descriptions say. */
	private static boolean sameForm(ApiModel.FunctionDef first, ApiModel.FunctionDef second) {
		return first.style() == second.style() && first.varargs() == second.varargs()
				&& first.params().stream().map(ApiModel.ParamDef::type).toList()
						.equals(second.params().stream().map(ApiModel.ParamDef::type).toList())
				&& first.returns().stream().map(ApiModel.ReturnDef::type).toList()
						.equals(second.returns().stream().map(ApiModel.ReturnDef::type).toList());
	}

	/** {@code param <Owner.function> <name> <LuaCATS type>}: the type of a parameter, where the stubs' is wrong. */
	private void applyParamOverrides() {
		for (Overrides.Entry entry : overrides.all("param")) {
			int dot = entry.target().lastIndexOf('.');
			ApiModel.ClassDef owner = dot < 0 ? null : model.classes.get(entry.target().substring(0, dot));
			String functionName = entry.target().substring(dot + 1);
			List<ApiModel.FunctionDef> functions = owner != null ? owner.functions
					: dot < 0 ? model.globalFunctions
					: List.of();
			boolean applied = false;
			if (entry.arguments().size() >= 2) {
				String name = entry.arguments().getFirst();
				TypeRef type = TypeParser.parse(String.join(" ", entry.arguments().subList(1, entry.arguments().size())));
				for (int index = 0; index < functions.size(); index++) {
					ApiModel.FunctionDef function = functions.get(index);
					List<ApiModel.ParamDef> params = new ArrayList<>(function.params());
					for (int position = 0; position < params.size(); position++) {
						ApiModel.ParamDef param = params.get(position);
						if (function.name().equals(functionName) && param.name().equals(name)) {
							overrides.applied(entry, param.type().equals(type));
							params.set(position, new ApiModel.ParamDef(name, type, param.description(),
									param.optionalName()));
							functions.set(index, function.withParams(params));
							applied = true;
						}
					}
				}
			}
			if (!applied) {
				warnings.add("overrides.txt:" + entry.line() + ": param " + entry.target() + " " + String.join(" ",
						entry.arguments()) + " matches no parameter of a function of the stubs.");
			}
		}
	}

	/**
	 * {@code hook <table.NAME> <Callback> <name:Type...>}: the callback a hook takes, a functional interface whose
	 * parameters follow the component that fired it. The stubs type every callback {@code fun(...)}, so these lines
	 * give the docs' hook table and the client's arguments. A target {@code table.JAVA=LUA} adds a second Java constant
	 * for one hook that passes other values from another kind of component.
	 */
	private void collectHooks() {
		Map<String, List<TypeRef.Param>> callbacks = new HashMap<>();
		for (Overrides.Entry entry : overrides.all("hook")) {
			String target = entry.target();
			int equals = target.indexOf('=');
			String javaPath = equals < 0 ? target : target.substring(0, equals);
			int dot = javaPath.lastIndexOf('.');
			String table = dot < 0 ? "" : javaPath.substring(0, dot);
			String javaName = javaPath.substring(dot + 1);
			String luaName = equals < 0 ? javaName : target.substring(equals + 1);
			ApiModel.EnumDef enumDef = model.enums.get(table);
			boolean known = enumDef != null
					&& enumDef.values().stream().anyMatch(value -> enumKey(value.name()).equals(luaName));
			if (!known || entry.arguments().isEmpty()) {
				warnings.add("overrides.txt:" + entry.line() + ": hook " + target + " needs a value of a table of hooks"
						+ " in the stubs and a callback name.");
				continue;
			}
			List<TypeRef.Param> params = new ArrayList<>();
			for (String argument : entry.arguments().subList(1, entry.arguments().size())) {
				int colon = argument.indexOf(':');
				params.add(new TypeRef.Param(argument.substring(0, colon), TypeParser.parse(argument.substring(colon + 1))));
			}
			String callback = entry.arguments().getFirst();
			List<TypeRef.Param> earlier = callbacks.putIfAbsent(callback, params);
			if (earlier != null && !earlier.stream().map(TypeRef.Param::type).toList()
					.equals(params.stream().map(TypeRef.Param::type).toList())) {
				warnings.add("overrides.txt:" + entry.line() + ": hook " + target + " gives the callback " + callback
						+ " other parameter types than an earlier line; the earlier ones are kept.");
				params = earlier;
			}
			overrides.applied(entry, false);
			hooks.computeIfAbsent(table, _ -> new ArrayList<>())
					.add(new HookLine(javaName, luaName, callback, params));
		}
	}

	/** {@code superclass <class> <superclass>}: the class the docs say a class extends, where the stubs give none. */
	private void applySuperclassOverrides() {
		for (Overrides.Entry entry : overrides.all("superclass")) {
			ApiModel.ClassDef classDef = model.classes.get(entry.target());
			String superName = entry.arguments().isEmpty() ? null : entry.arguments().getFirst();
			if (classDef == null || superName == null || !model.classes.containsKey(superName)) {
				warnings.add("overrides.txt:" + entry.line() + ": superclass " + entry.target() + " needs two classes of"
						+ " the stubs.");
				continue;
			}
			overrides.applied(entry, superName.equals(classDef.superclass));
			classDef.superclass = superName;
		}
	}

	/** The classes some function takes as a parameter: the data tables a plugin may build. */
	private void collectParameterTypes() {
		for (ApiModel.FunctionDef function : model.allFunctions()) {
			for (ApiModel.ParamDef param : function.params()) {
				if (param.type().withoutNil() instanceof TypeRef.Named named) {
					parameterTypes.add(model.resolveAlias(named.name()));
				}
			}
		}
	}

	/** The fields of {@code Event}: each event's payload class, and whether its callback returns a boolean. */
	private void collectEvents() {
		ApiModel.ClassDef event = model.classes.get("Event");
		if (event == null) {
			warnings.add("The stubs have no Event class, so no events are generated.");
			return;
		}
		for (ApiModel.FieldDef field : event.fields) {
			if (!(field.type() instanceof TypeRef.Named named) || !named.name().equals("EventSubscription")
					|| named.args().size() != 2) {
				warnings.add("Event." + field.name() + " is not an EventSubscription<Payload, Return>; it is left out.");
				continue;
			}
			String payload = named.args().get(0) instanceof TypeRef.Named type && !type.name().equals("nil")
					? model.resolveAlias(type.name())
					: field.name() + "EventPayload";
			boolean predicate = named.args().get(1) instanceof TypeRef.Named returned
					&& model.resolveAlias(returned.name()).equals("boolean");
			events.put(field.name(), new EventInfo(field.name(), payload, predicate));
		}
	}

	private void buildTree() {
		for (ApiModel.ClassDef classDef : model.classes.values()) {
			if (isGenerated(classDef)) {
				Node node = node(luaPath(classDef.name));
				// _global.id and the table id are one namespace, and the class holds the fields
				ApiModel.ClassDef existing = node.classDef;
				if (existing == null || existing.fields.isEmpty()) {
					node.classDef = classDef;
				}
				byLuaName.put(classDef.name, node);
				byLuaName.put(luaPath(classDef.name), node);
			}
		}
		for (ApiModel.EnumDef enumDef : model.enums.values()) {
			Node node = node(enumDef.name());
			node.enumDef = enumDef;
			byLuaName.put(enumDef.name(), node);
		}
		for (EventInfo event : events.values()) {
			Node node = byLuaName.get(event.payload());
			if (node == null) {
				node = node(event.payload());
				byLuaName.put(event.payload(), node);
			}
			node.payloadOf = event;
		}
		for (Overrides.Entry entry : overrides.all("element-table")) {
			elementTable(entry);
		}
		for (String global : List.of("id", "config", "var")) {
			Node node = tops.get(javaTopName(global));
			if (node != null) {
				constantNamespaces(node, global);
			}
		}
	}

	/**
	 * {@code element-table game.options ClientOption <names...>}: the stubs describe one element of the table as the
	 * table itself. The table becomes a class of named objects, and the stubs' class becomes their type.
	 */
	private void elementTable(Overrides.Entry entry) {
		Node node = byLuaName.get(entry.target());
		ApiModel.ClassDef classDef = node == null ? null : node.classDef;
		if (node == null || classDef == null || entry.arguments().size() < 2) {
			warnings.add("overrides.txt:" + entry.line() + ": element-table " + entry.target() + " needs a class of the"
					+ " stubs, a type name and the element names.");
			return;
		}
		// Stubs that already have the elements as the table's fields describe the table themselves, and the class is
		// left as they give it; stubs that have some of them changed in a way this line cannot follow
		List<String> elementNames = entry.arguments().subList(1, entry.arguments().size());
		Set<String> fieldNames = new HashSet<>();
		classDef.fields.forEach(field -> fieldNames.add(field.name()));
		List<String> named = elementNames.stream().filter(fieldNames::contains).toList();
		if (!named.isEmpty()) {
			if (named.size() == elementNames.size()) {
				overrides.applied(entry, true);
			} else {
				warnings.add("overrides.txt:" + entry.line() + ": element-table " + entry.target() + " is left as the"
						+ " stubs give it, which name some of its elements as fields (" + String.join(", ", named)
						+ ") but not all; check the line against the stubs.");
			}
			return;
		}
		overrides.applied(entry, false);
		ApiModel.ClassDef element = new ApiModel.ClassDef(classDef.name);
		element.description = classDef.description;
		element.binding = ApiModel.Binding.LOCAL;
		element.fields.addAll(classDef.fields);
		element.functions.addAll(classDef.functions);
		String typeName = entry.arguments().getFirst();
		Node elementNode = new Node(typeName, typeName, node);
		elementNode.classDef = element;
		node.children.put(typeName, elementNode);
		node.classDef = null;
		node.elementNames = elementNames;
		node.elementType = elementNode;
		byLuaName.put(classDef.name, elementNode);
	}

	/** Every class but the generic EventSubscription, which has templates of its own, and the integer ID types. */
	private boolean isGenerated(ApiModel.ClassDef classDef) {
		return !classDef.name.equals("EventSubscription") && !"integer".equals(classDef.superclass)
				&& !overrides.has("skip", classDef.name);
	}

	/** The Lua path of a class's table: {@code _global.id} is the table {@code id}. */
	private static String luaPath(String luaName) {
		return luaName.startsWith("_global.") ? luaName.substring("_global.".length()) : luaName;
	}

	/** The Java name of a top-level table: its own, except {@code var}, a name Java reserves for types. */
	private String javaTopName(String luaName) {
		String renamed = overrides.value("java-name", luaName);
		if (renamed != null) {
			return renamed;
		}
		return luaName.equals("var") ? "vars" : luaName;
	}

	private Node node(String luaName) {
		List<String> segments = List.of(luaName.split("\\.", -1));
		Node current = tops.computeIfAbsent(javaTopName(segments.getFirst()),
				name -> new Node(name, segments.getFirst(), null));
		for (int index = 1; index < segments.size(); index++) {
			Node parent = current;
			String segment = segments.get(index);
			current = parent.children.computeIfAbsent(javaIdentifier(segment), name -> new Node(name, segment, parent));
		}
		return current;
	}

	/**
	 * The namespaces of {@code id}, {@code config} and {@code var}: each field of their class, typed
	 * {@code { [string]: X, FromID: fun(...) }}, becomes a nested class with {@code FromID} that extends the chunk
	 * classes of its csv's names.
	 */
	private void constantNamespaces(Node holder, String global) {
		ApiModel.ClassDef classDef = holder.classDef;
		if (classDef == null) {
			return;
		}
		holder.holder = true;
		for (ApiModel.FieldDef field : classDef.fields) {
			if (!(field.type() instanceof TypeRef.Table table)) {
				continue;
			}
			TypeRef element = null;
			TypeRef fromId = null;
			for (TypeRef.Entry entry : table.entries()) {
				if (entry.keyType() != null) {
					element = entry.value();
				} else if (entry.name().equals("FromID")) {
					fromId = entry.value();
				}
			}
			if (element == null) {
				continue;
			}
			Node node = new Node(javaIdentifier(field.name()), field.name(), holder);
			holder.children.put(node.javaName, node);
			node.fromId = fromId;
			node.byName = element;
			String elementBinaryName = global.equals("id") ? null : binaryNameOf(element);
			CsvConstants.Namespace csv = csvs.get(field.name());
			List<CsvConstants.Row> rows = csv != null && (global.equals("id") || elementBinaryName != null)
					? validRows(global + "." + field.name(), csv)
					: List.of();
			String kind = Character.toUpperCase(global.charAt(0)) + global.substring(1);
			ConstantNamespace namespace = new ConstantNamespace(global + "." + field.name(),
					node.javaName + kind + "Chunk", elementBinaryName, rows);
			node.constants = namespace;
			if (!rows.isEmpty()) {
				constants.add(namespace);
			}
		}
	}

	/**
	 * The csv's rows whose names Java can use, after {@link LuaConstants#PREFIX}, and whose IDs fit an {@code int}. The
	 * rest are left out with one warning
	 * for the whole table, because a large table can leave out thousands of names, which a build log should not list.
	 */
	private List<CsvConstants.Row> validRows(String namespace, CsvConstants.Namespace csv) {
		List<CsvConstants.Row> valid = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		List<String> notNames = new ArrayList<>();
		List<String> repeated = new ArrayList<>();
		List<String> tooLarge = new ArrayList<>();
		for (CsvConstants.Row row : csv.rows()) {
			if (!SourceVersion.isName(LuaConstants.PREFIX + row.name())) {
				notNames.add(row.name());
			} else if (row.id() != (int) row.id()) {
				tooLarge.add(row.name());
			} else if (!seen.add(row.name())) {
				repeated.add(row.name());
			} else {
				valid.add(row);
			}
		}
		List<String> reasons = new ArrayList<>();
		addReason(reasons, notNames, "not a Java name", "not Java names");
		addReason(reasons, repeated, "repeated", "repeated");
		addReason(reasons, tooLarge, "with an ID that does not fit an int", "with IDs that do not fit an int");
		if (!reasons.isEmpty()) {
			int count = notNames.size() + repeated.size() + tooLarge.size();
			String hint = namespace.startsWith("id.") ? "; use their numeric IDs instead"
					: namespace.startsWith("config.") ? "; look them up with FromID and their numeric IDs instead" : "";
			warnings.add(namespace + " leaves out " + count + (count == 1 ? " name" : " names") + " Java cannot use: "
					+ String.join(", ", reasons) + hint + ".");
		}
		return valid;
	}

	/** One reason a table leaves names out, with how many and the first as an example: "3 repeated (such as MAN)". */
	private static void addReason(List<String> reasons, List<String> names, String one, String many) {
		if (!names.isEmpty()) {
			reasons.add(names.size() + " " + (names.size() == 1 ? one : many) + " (such as " + names.getFirst() + ")");
		}
	}

	private @Nullable String binaryNameOf(TypeRef type) {
		if (type instanceof TypeRef.Named named) {
			Node node = byLuaName.get(model.resolveAlias(named.name()));
			if (node != null) {
				return PACKAGE + "." + node.binaryName();
			}
		}
		warnings.add("The names of type " + type.show() + " have no binding class; they are left out.");
		return null;
	}

	// ---- Writing classes

	private Shape shape(ApiModel.ClassDef classDef) {
		if (classDef.binding != ApiModel.Binding.GLOBAL) {
			return Shape.TYPE;
		}
		if (overrides.has("instance", classDef.name)) {
			return Shape.INSTANCE;
		}
		if (overrides.has("singleton", classDef.name)) {
			return Shape.NAMESPACE;
		}
		// A class that extends another describes objects, as PluginImageResource does, even when no type names it
		if (typeUses.getOrDefault(classDef.name, 0) == 0 && classDef.superclass == null) {
			return Shape.NAMESPACE;
		}
		// A global table with no function of its own, only methods, is never reached: its objects come from the API,
		// as ui.ComponentOpConfig's come from a component's opConfig
		boolean hasTableFunctions = classDef.functions.stream()
				.anyMatch(function -> function.style() == ApiModel.CallStyle.DOT);
		return hasTableFunctions ? Shape.INSTANCE : Shape.TYPE;
	}

	private void writeNode(StringBuilder out, Node node, String indent) {
		ApiModel.EnumDef enumDef = node.enumDef;
		ApiModel.ClassDef classDef = node.classDef;
		EventInfo payload = node.payloadOf;
		if (enumDef != null && hooks.containsKey(enumDef.name())) {
			writeHooks(out, node, enumDef, hooks.get(enumDef.name()), indent);
		} else if (enumDef != null) {
			writeEnum(out, node, enumDef, indent);
		} else if (node.constants != null) {
			writeConstantNamespace(out, node, indent);
		} else if (node.elementNames != null) {
			writeElementTable(out, node, indent);
		} else if (classDef != null && !node.holder
				&& !(payload != null && classDef.fields.isEmpty() && classDef.functions.isEmpty())) {
			writeClass(out, node, classDef, indent);
		} else if (payload != null) {
			// The stubs' empty payload class, as for WorldUpdated, is an event without a payload
			writeSynthesizedPayload(out, node, payload, indent);
		} else {
			writeHolder(out, node, indent);
		}
	}

	private void writeHolder(StringBuilder out, Node node, String indent) {
		javadoc(out, indent, List.of("The Lua table {@code " + node.luaName() + "}, which holds the classes nested here."));
		out.append(indent).append("@LuaGlobal(\"").append(node.luaName()).append("\")\n");
		out.append(indent).append("public ").append(node.parent == null ? "" : "static ").append("final class ")
				.append(node.javaName).append(" {\n\n");
		privateConstructor(out, node, indent + "\t");
		writeChildren(out, node, indent + "\t");
		out.append(indent).append("}\n");
	}

	private void writeElementTable(StringBuilder out, Node node, String indent) {
		Node elementType = node.elementType;
		List<String> names = node.elementNames;
		if (elementType == null || names == null) {
			return;
		}
		javadoc(out, indent, List.of("The Lua table {@code " + node.luaName() + "}: one {@code " + elementType.javaName
				+ "} object per name."));
		out.append(indent).append("@LuaGlobal(\"").append(node.luaName()).append("\")\n");
		out.append(indent).append("public ").append(node.parent == null ? "" : "static ").append("final class ")
				.append(node.javaName).append(" {\n\n");
		String inner = indent + "\t";
		privateConstructor(out, node, inner);
		for (String name : names) {
			out.append("\n");
			javadoc(out, inner, List.of("<p>Lua: {@code " + node.luaName() + "." + name + "}"));
			out.append(inner).append("public static final ").append(elementType.reference()).append(" ")
					.append(javaIdentifier(name)).append(" = LuaOnly.value();\n");
		}
		writeChildren(out, node, inner);
		out.append(indent).append("}\n");
	}

	private void writeChildren(StringBuilder out, Node node, String indent) {
		for (Node child : node.children.values()) {
			out.append("\n");
			writeNode(out, child, indent);
		}
	}

	private static void privateConstructor(StringBuilder out, Node node, String indent) {
		out.append(indent).append("private ").append(node.javaName).append("() {\n")
				.append(indent).append("\tthrow LuaOnly.error();\n")
				.append(indent).append("}\n");
	}

	private void writeEnum(StringBuilder out, Node node, ApiModel.EnumDef enumDef, String indent) {
		// A Java enum lowers to the table's own field, whatever its value, so an enum whose values are unknown is one
		boolean asJavaEnum = typeUses.getOrDefault(enumDef.name(), 0) > 0 || enumDef.placeholderValued();
		List<String> doc = new ArrayList<>(lines(enumDef.description()));
		doc.add(asJavaEnum
				? "<p>Lua: the table {@code " + enumDef.name() + "}, whose values the client compares with {@code ==}."
				: "<p>Lua: the table {@code " + enumDef.name() + "}; its values are plain "
						+ (enumDef.integerValued() ? "integers, such as bit flags." : "strings."));
		javadoc(out, indent, doc);
		out.append(indent).append("@LuaGlobal(\"").append(enumDef.name()).append("\")\n");
		String inner = indent + "\t";
		Set<String> javaNames = new HashSet<>();
		if (asJavaEnum) {
			// The client gives all but a few enum tables FromID, which turns a value into its name
			if (!overrides.has("no-from-id", enumDef.name())) {
				out.append(indent).append("@LuaFromId\n");
			}
			out.append(indent).append("public enum ").append(node.javaName).append(" {\n");
			List<String> constantsWritten = new ArrayList<>();
			List<String> aliases = new ArrayList<>();
			Map<String, String> firstWithValue = new HashMap<>();
			for (ApiModel.EnumValue value : enumDef.values()) {
				String luaKey = enumKey(value.name());
				String javaName = javaIdentifier(luaKey);
				if (!javaNames.add(javaName)) {
					warnings.add(enumDef.name() + "." + luaKey + " has the Java name of another value; it is left out.");
					continue;
				}
				StringBuilder constant = new StringBuilder("\n");
				List<String> valueDoc = new ArrayList<>(lines(value.comment()));
				String first = firstWithValue.putIfAbsent(value.literal(), javaName);
				String shown = enumDef.placeholderValued() ? "" : ", " + escape(value.literal());
				valueDoc.add("<p>Lua: {@code " + enumDef.name() + "." + luaKey + "}" + shown
						+ (first == null ? "." : ", the value of {@link #" + first + "}."));
				javadoc(constant, inner, valueDoc);
				if (!javaName.equals(luaKey)) {
					constant.append(inner).append("@LuaName(").append(javaString(luaKey)).append(")\n");
				}
				if (first != null) {
					// A second name for a value: Lua cannot tell the two apart, so in Java it is the first constant
					constant.append(inner).append("public static final ").append(node.javaName).append(" ")
							.append(javaName).append(" = ").append(first).append(";\n");
					aliases.add(constant.toString());
					continue;
				}
				constant.append(inner).append(javaName);
				constantsWritten.add(constant.toString());
			}
			out.append(String.join(",\n", constantsWritten)).append(";\n");
			aliases.forEach(out::append);
		} else {
			out.append(indent).append("public ").append(node.parent == null ? "" : "static ").append("final class ")
					.append(node.javaName).append(" {\n\n");
			privateConstructor(out, node, inner);
			for (ApiModel.EnumValue value : enumDef.values()) {
				String luaKey = enumKey(value.name());
				String javaName = javaIdentifier(luaKey);
				if (!javaNames.add(javaName)) {
					warnings.add(enumDef.name() + "." + luaKey + " has the Java name of another value; it is left out.");
					continue;
				}
				out.append("\n");
				List<String> valueDoc = new ArrayList<>(lines(value.comment()));
				valueDoc.add("<p>Lua: {@code " + enumDef.name() + "." + luaKey + "}");
				javadoc(out, inner, valueDoc);
				if (!javaName.equals(luaKey)) {
					out.append(inner).append("@LuaName(").append(javaString(luaKey)).append(")\n");
				}
				String literal = value.literal();
				String type = literal.startsWith("\"") ? "String" : literal.contains(".") ? "double" : "int";
				out.append(inner).append("public static final ").append(type).append(" ").append(javaName).append(" = ")
						.append(literal).append(";\n");
			}
		}
		writeChildren(out, node, inner);
		out.append(indent).append("}\n");
	}

	/**
	 * A table of hooks, {@code ui.Hook}, as a class of constants typed by the callback each hook takes, with those
	 * callbacks as functional interfaces: {@code Subscribe(ui.Hook<C, ?> hook, C callback)} then checks a lambda's
	 * parameters against its hook. The callbacks return nothing, as most Lua ones do; {@code hook.stoppable()} types the
	 * same hook for a callback that returns whether the listeners after it run, which Lua's may. A hook without a
	 * {@code hook} line takes a callback of the component alone.
	 */
	private void writeHooks(StringBuilder out, Node node, ApiModel.EnumDef enumDef, List<HookLine> lines,
			String indent) {
		String hook = node.reference();
		List<String> doc = new ArrayList<>(lines(enumDef.description()));
		doc.add("<p>Lua: the table {@code " + enumDef.name() + "}. Each constant names the callback its hook takes,"
				+ " which receives the component first.");
		doc.add("");
		doc.add("@param <C> the callback the hook takes");
		doc.add("@param <S> the same callback returning whether the listeners after it run");
		javadoc(out, indent, doc);
		out.append(indent).append("@LuaGlobal(\"").append(enumDef.name()).append("\")\n");
		out.append(indent).append("public ").append(node.parent == null ? "" : "static ").append("final class ")
				.append(node.javaName).append("<C, S> {\n\n");
		String inner = indent + "\t";
		privateConstructor(out, node, inner);
		Map<String, List<TypeRef.Param>> callbacks = new LinkedHashMap<>();
		Map<String, List<String>> firedBy = new LinkedHashMap<>();
		for (ApiModel.EnumValue value : enumDef.values()) {
			String luaKey = enumKey(value.name());
			List<HookLine> forms = lines.stream().filter(line -> line.luaName().equals(luaKey)).toList();
			if (forms.isEmpty()) {
				warnings.add(enumDef.name() + "." + luaKey + " has no hook line; its callback takes the component alone.");
				forms = List.of(new HookLine(luaKey, luaKey, "Notify", List.of()));
			}
			for (HookLine form : forms) {
				callbacks.putIfAbsent(form.callback(), form.params());
				firedBy.computeIfAbsent(form.callback(), _ -> new ArrayList<>()).add(form.javaName());
				out.append("\n");
				List<String> valueDoc = new ArrayList<>(lines(value.comment()));
				valueDoc.add("<p>Lua: {@code " + enumDef.name() + "." + luaKey + "}; its callback: {@link "
						+ form.callback() + "}.");
				javadoc(out, inner, valueDoc);
				if (!form.javaName().equals(luaKey)) {
					out.append(inner).append("@LuaName(").append(javaString(luaKey)).append(")\n");
				}
				String callback = hook + "." + form.callback();
				out.append(inner).append("public static final ").append(hook).append("<").append(callback).append(", ")
						.append(callback).append(".Stopping> ").append(javaIdentifier(form.javaName()))
						.append(" = LuaOnly.value();\n");
			}
		}
		out.append("\n");
		javadoc(out, inner, List.of("This hook, for a callback that returns whether the listeners after it run: false"
				+ " stops them.", "", "<p>Lua: the hook itself, whose function returns the boolean"));
		out.append(inner).append("@LuaSame\n");
		out.append(inner).append("public native ").append(hook).append("<S, S> stoppable();\n");
		Node component = byLuaName.get("ui.Component");
		String self = component == null ? "Object" : component.reference();
		callbacks.forEach((name, params) -> {
			List<JParam> javaParams = new ArrayList<>();
			javaParams.add(new JParam("self", JType.reference(self), "the component the hook fired on"));
			for (TypeRef.Param param : params) {
				javaParams.add(new JParam(parameterName(param.name(), javaParams), javaType(param.type()), ""));
			}
			String lua = "function(" + luaArguments(javaParams) + ")";
			out.append("\n");
			javadoc(out, inner, List.of("The callback of " + String.join(", ", firedBy.get(name)) + ".", "",
					"<p>Lua: {@code " + lua + "}"));
			out.append(inner).append("@FunctionalInterface\n");
			out.append(inner).append("public interface ").append(name).append(" {\n\n");
			out.append(inner).append("\tvoid call(").append(parameterList(javaParams, false)).append(");\n\n");
			javadoc(out, inner + "\t", List.of("The same callback, returning whether the listeners after it run.", "",
					"<p>Lua: {@code " + lua + "}, which returns the boolean"));
			out.append(inner).append("\t@FunctionalInterface\n");
			out.append(inner).append("\tinterface Stopping {\n");
			out.append(inner).append("\t\tboolean call(").append(parameterList(javaParams, false)).append(");\n");
			out.append(inner).append("\t}\n");
			out.append(inner).append("}\n");
		});
		writeChildren(out, node, inner);
		out.append(indent).append("}\n");
	}

	/**
	 * The parameters of a form as the hook table types them: a hook parameter becomes {@code ui.Hook<C, ?>} and the
	 * callback beside it {@code C}, so that the callback must be the one the hook takes; a hook without a callback, as
	 * {@code Unsubscribe(hook)} takes, becomes {@code ui.Hook<?, ?>}. Other forms are returned as they are.
	 */
	private List<JParam> hookTyped(List<JParam> params) {
		for (String table : hooks.keySet()) {
			Node node = byLuaName.get(table);
			if (node == null) {
				continue;
			}
			String hookType = node.reference();
			int hook = -1;
			int callback = -1;
			for (int index = 0; index < params.size(); index++) {
				String type = params.get(index).type().erased();
				if (type.equals(hookType)) {
					hook = index;
				} else if (type.startsWith("java.util.function.") || type.equals("Runnable")) {
					callback = index;
				}
			}
			if (hook < 0) {
				continue;
			}
			List<JParam> typed = new ArrayList<>(params);
			JParam hookParam = params.get(hook);
			typed.set(hook, new JParam(hookParam.name(), JType.reference(hookType + (callback < 0 ? "<?, ?>" : "<C, ?>")),
					hookParam.description()));
			if (callback >= 0) {
				JParam callbackParam = params.get(callback);
				typed.set(callback, new JParam(callbackParam.name(), JType.reference("C"), callbackParam.description()));
			}
			return typed;
		}
		return params;
	}

	/** An enum key as Lua indexes it: {@code NAME}, or the string inside {@code ["..."]}. */
	private static String enumKey(String key) {
		return key.startsWith("[\"") ? key.substring(2, key.length() - 2) : key;
	}

	private void writeConstantNamespace(StringBuilder out, Node node, String indent) {
		ConstantNamespace namespace = node.constants;
		if (namespace == null) {
			return;
		}
		String file = namespace.luaPath().substring(namespace.luaPath().indexOf('.') + 1) + ".csv";
		List<String> doc = new ArrayList<>();
		doc.add(namespace.rows().isEmpty()
				? "{@code " + namespace.luaPath() + "}: the stubs list no names for it, so only {@code FromID} is bound."
				: "{@code " + namespace.luaPath() + "}: " + String.format(Locale.ROOT, "%,d", namespace.rows().size())
						+ " names, from the stubs' {@code csvs/" + file + "}.");
		doc.add("<p>Java: {@code " + LuaConstants.PREFIX + "NAME}; Lua: {@code " + namespace.luaPath() + ".NAME}");
		javadoc(out, indent, doc);
		out.append(indent).append("@LuaGlobal(\"").append(namespace.luaPath()).append("\")\n");
		out.append(indent).append("public static final class ").append(node.javaName);
		if (!namespace.rows().isEmpty()) {
			out.append(" extends ").append(namespace.chunkPrefix()).append(namespace.chunkCount());
		}
		out.append(" {\n\n");
		String inner = indent + "\t";
		privateConstructor(out, node, inner);
		if (node.fromId instanceof TypeRef.Function function) {
			out.append("\n");
			List<JParam> params = new ArrayList<>();
			for (TypeRef.Param param : function.params()) {
				params.add(new JParam(parameterName(param.name(), params), javaType(param.type()), ""));
			}
			JType returned = function.returns().isEmpty() ? JType.VOID : javaType(function.returns().getFirst());
			javadoc(out, inner, List.of("The entry with the given ID.", "",
					"<p>Lua: {@code " + namespace.luaPath() + ".FromID(" + luaArguments(params) + ")}"));
			out.append(inner).append("public static native ").append(returned.text()).append(" FromID(")
					.append(parameterList(params, false)).append(");\n");
		}
		TypeRef byName = node.byName;
		if (byName != null) {
			// A name known only at run time: an unknown one reads -1 from an id table and nil from the others
			JType named = javaType(byName);
			boolean isId = namespace.luaPath().startsWith("id.");
			out.append("\n");
			javadoc(out, inner, List.of("The entry with the given name, for a name known only at run time: "
					+ (isId ? "-1" : "null") + " when there is none.", "",
					"<p>Lua: {@code " + namespace.luaPath() + "[name]}"));
			out.append(inner).append("@LuaIndex\n");
			out.append(inner).append("public static native ").append(isId ? named.text() : named.nullable().text())
					.append(" get(String name);\n");
		}
		out.append(indent).append("}\n");
	}

	private void writeSynthesizedPayload(StringBuilder out, Node node, EventInfo event, String indent) {
		javadoc(out, indent, List.of("The {@code " + event.name() + "} event passes no payload: a {@code @Subscribe}"
				+ " method takes this empty class, and its Lua function takes no parameter."));
		eventAnnotations(out, event, indent);
		out.append(indent).append("@LuaType(\"").append(event.payload()).append("\")\n");
		out.append(indent).append("public ").append(node.parent == null ? "" : "static ").append("final class ")
				.append(node.javaName).append(" {\n\n");
		privateConstructor(out, node, indent + "\t");
		out.append(indent).append("}\n");
	}

	private void eventAnnotations(StringBuilder out, EventInfo event, String indent) {
		out.append(indent).append("@LuaEvent(").append(event.predicate()
				? "value = \"" + event.name() + "\", returns = boolean.class"
				: "\"" + event.name() + "\"").append(")\n");
		String permission = overrides.value("permission", "Event." + event.name());
		if (permission != null) {
			out.append(indent).append("@LuaPermission(\"").append(permission).append("\")\n");
		}
		String flag = overrides.value("manifest-flag", "Event." + event.name());
		if (flag != null) {
			out.append(indent).append("@LuaManifestFlag(\"").append(flag).append("\")\n");
		}
	}

	private void writeClass(StringBuilder out, Node node, ApiModel.ClassDef classDef, String indent) {
		Shape shape = shape(classDef);
		String luaName = luaPath(classDef.name);
		EventInfo payload = node.payloadOf;
		List<String> doc = new ArrayList<>(lines(classDef.description));
		boolean hasNew = classDef.functions.stream().anyMatch(JavaBindings::isConstructor);
		doc.add(switch (shape) {
			case NAMESPACE -> "<p>Lua: the global table {@code " + luaName + "}.";
			case INSTANCE -> "<p>Lua: the global table {@code " + luaName + "}" + (hasNew
					? ", whose {@code new} makes its objects."
					: "; its objects come from the API.");
			case TYPE -> "<p>Lua: values of the type {@code " + luaName + "}" + (isBuiltByPlugin(node, classDef)
					? ": tables the plugin builds and passes to the API."
					: ", which the client hands out.");
		});
		javadoc(out, indent, doc);
		if (payload != null) {
			eventAnnotations(out, payload, indent);
		}
		memberAnnotations(out, classDef.name, indent);
		out.append(indent).append(shape == Shape.TYPE ? "@LuaType(\"" : "@LuaGlobal(\"").append(luaName).append("\")\n");
		String superclass = superclass(classDef, shape);
		boolean isFinal = shape == Shape.NAMESPACE || payload != null;
		out.append(indent).append("public ").append(node.parent == null ? "" : "static ").append(isFinal ? "final " : "")
				.append("class ").append(node.javaName).append(superclass == null ? "" : " extends " + superclass)
				.append(" {\n\n");
		String inner = indent + "\t";
		Map<String, Inherited> inherited = inheritedMethods(classDef);
		Set<String> signatures = new HashSet<>();
		constructors(out, node, classDef, shape, inner, signatures);
		boolean isStatic = shape == Shape.NAMESPACE;
		for (ApiModel.FieldDef field : classDef.fields) {
			if (luaName.equals("Event")) {
				eventField(out, field, inner);
			} else if (!overrides.has("skip", classDef.name + "." + field.name())) {
				field(out, classDef, field, isStatic, inner, signatures);
			}
		}
		for (ApiModel.FunctionDef function : classDef.functions) {
			if (!(shape == Shape.INSTANCE && isConstructor(function)) && !isLeftToSubclasses(classDef, function)) {
				method(out, classDef, function, shape, inner, signatures, inherited);
			}
		}
		operators(out, classDef, inner, signatures);
		writeChildren(out, node, inner);
		out.append(indent).append("}\n");
	}

	/**
	 * {@code operator <Class.name> <op> <fun(...) type>}: one of Lua's operators on the class's objects, which the
	 * stubs do not declare, as a method marked {@code @LuaOperator}: {@code a.plus(b)} is {@code a + b} in Lua.
	 */
	private void operators(StringBuilder out, ApiModel.ClassDef owner, String indent, Set<String> signatures) {
		for (Overrides.Entry entry : overrides.all("operator")) {
			int dot = entry.target().lastIndexOf('.');
			if (dot < 0 || !entry.target().substring(0, dot).equals(owner.name)) {
				continue;
			}
			TypeRef type = entry.arguments().size() < 2 ? null
					: TypeParser.parse(String.join(" ", entry.arguments().subList(1, entry.arguments().size())));
			if (!(type instanceof TypeRef.Function function) || function.params().size() != 1
					|| function.returns().size() != 1) {
				warnings.add("overrides.txt:" + entry.line() + ": operator " + entry.target() + " needs an operator and"
						+ " a fun(other): result type.");
				continue;
			}
			overrides.applied(entry, false);
			String operator = entry.arguments().getFirst();
			String javaName = entry.target().substring(dot + 1);
			TypeRef.Param param = function.params().getFirst();
			JType paramType = javaType(param.type());
			if (!signatures.add(javaName + "(" + paramType.erased() + ")")) {
				continue;
			}
			String receiver = receiver(owner, false);
			out.append("\n");
			javadoc(out, indent, List.of("Lua's {@code " + escape(operator) + "} on this object and the other.", "",
					"<p>Lua: {@code " + receiver + " " + escape(operator) + " " + param.name() + "}"));
			out.append(indent).append("@LuaOperator(").append(javaString(operator)).append(")\n");
			out.append(indent).append("public native ").append(javaType(function.returns().getFirst()).text()).append(" ")
					.append(javaName).append("(").append(paramType.text()).append(" ").append(param.name()).append(");\n");
		}
	}

	private static boolean isConstructor(ApiModel.FunctionDef function) {
		return function.name().equals("new") && function.style() == ApiModel.CallStyle.DOT;
	}

	/**
	 * Whether a method that takes the class's own type is left to the subclasses, because each declares it for its own
	 * type: {@code Vector:Dot(other : Vector)} means a vector of the caller's size, which only {@code Vector3:Dot(other
	 * : Vector3)} and the others say in Java. Bound on the base class too, it would accept a vector of another size.
	 */
	private boolean isLeftToSubclasses(ApiModel.ClassDef classDef, ApiModel.FunctionDef function) {
		boolean takesOwnType = function.params().stream().anyMatch(param -> param.type().withoutNil()
				instanceof TypeRef.Named named && model.resolveAlias(named.name()).equals(classDef.name));
		if (!takesOwnType) {
			return false;
		}
		List<ApiModel.ClassDef> subclasses = model.classes.values().stream()
				.filter(other -> other.superclass != null && model.resolveAlias(other.superclass).equals(classDef.name))
				.toList();
		return !subclasses.isEmpty() && subclasses.stream().allMatch(subclass -> subclass.functions.stream()
				.anyMatch(own -> own.name().equals(function.name()) && own.params().stream()
						.anyMatch(param -> param.type().withoutNil() instanceof TypeRef.Named named
								&& model.resolveAlias(named.name()).equals(subclass.name))));
	}

	/**
	 * The class's constructors: Lua's {@code new} for a global table of objects, {@code {}} for a data table the
	 * plugin builds, and otherwise one that only subclasses can call, so that plugin code cannot make the objects.
	 */
	private void constructors(StringBuilder out, Node node, ApiModel.ClassDef classDef, Shape shape, String indent,
			Set<String> signatures) {
		List<String> written = new ArrayList<>();
		boolean publicNoArguments = false;
		if (shape == Shape.INSTANCE) {
			for (ApiModel.FunctionDef function : classDef.functions) {
				if (!isConstructor(function)) {
					continue;
				}
				for (Form form : forms(classDef.name, function)) {
					if (!signatures.add("<init>(" + erased(form.params()) + ")")) {
						continue;
					}
					publicNoArguments |= form.params().isEmpty() && !function.varargs();
					StringBuilder constructor = new StringBuilder();
					List<String> doc = new ArrayList<>(lines(function.description()));
					doc.add("<p>Lua: {@code " + luaPath(classDef.name) + ".new(" + luaArguments(form.params()) + ")}");
					paramDocs(doc, form.params());
					javadoc(constructor, indent, doc);
					memberAnnotations(constructor, classDef.name + ".new", indent);
					constructor.append(indent).append("public ").append(node.javaName).append("(")
							.append(parameterList(form.params(), function.varargs())).append(") {\n")
							.append(indent).append("\tthrow LuaOnly.error();\n")
							.append(indent).append("}\n");
					written.add(constructor.toString());
				}
			}
		}
		if (shape == Shape.TYPE && isBuiltByPlugin(node, classDef)) {
			StringBuilder constructor = new StringBuilder();
			javadoc(constructor, indent, List.of("An empty table, for the plugin to fill in and pass to the API.", "",
					"<p>Lua: {@code {}}"));
			constructor.append(indent).append("public ").append(node.javaName).append("() {\n")
					.append(indent).append("\tthrow LuaOnly.error();\n")
					.append(indent).append("}\n");
			written.add(constructor.toString());
			publicNoArguments = true;
		}
		if (!publicNoArguments) {
			boolean closed = shape == Shape.NAMESPACE || node.payloadOf != null;
			out.append(indent).append(closed ? "private " : "protected ").append(node.javaName).append("() {\n")
					.append(indent).append("\tthrow LuaOnly.error();\n")
					.append(indent).append("}\n");
			written.forEach(constructor -> out.append("\n").append(constructor));
		} else {
			out.append(String.join("\n", written));
		}
	}

	private void memberAnnotations(StringBuilder out, String target, String indent) {
		String permission = overrides.value("permission", target);
		if (permission != null) {
			out.append(indent).append("@LuaPermission(\"").append(permission).append("\")\n");
		}
		if (overrides.has("dev-only", target)) {
			out.append(indent).append("@LuaDevOnly\n");
		}
	}

	private @Nullable String superclass(ApiModel.ClassDef classDef, Shape shape) {
		String superName = classDef.superclass;
		if (superName == null || shape == Shape.NAMESPACE) {
			return null;
		}
		Node node = byLuaName.get(model.resolveAlias(superName));
		ApiModel.ClassDef superDef = node == null ? null : node.classDef;
		if (node == null || superDef == null || shape(superDef) == Shape.NAMESPACE || node.payloadOf != null) {
			warnings.add(classDef.name + " extends " + superName + ", which has no class of objects; its Java class"
					+ " extends Object.");
			return null;
		}
		return node.reference();
	}

	/**
	 * Whether a value type is a data table the plugin builds, with a {@code {}} constructor: not a payload, and not a
	 * class the stubs give a global table, whose objects the client makes.
	 */
	private boolean isBuiltByPlugin(Node node, ApiModel.ClassDef classDef) {
		return node.payloadOf == null && classDef.binding != ApiModel.Binding.GLOBAL && isConstructible(classDef);
	}

	/**
	 * Whether reading the field gives a copy of the client's value: a field of a {@code copied} type, such as
	 * {@code Vector3}, on an object the client hands out, unless the field is {@code not-copied}. A data table the
	 * plugin builds holds its fields as they are.
	 */
	private boolean isCopied(ApiModel.ClassDef owner, ApiModel.FieldDef field) {
		if (!(field.type().withoutNil() instanceof TypeRef.Named named)
				|| !overrides.has("copied", model.resolveAlias(named.name()))
				|| overrides.has("not-copied", owner.name + "." + field.name())) {
			return false;
		}
		Node node = byLuaName.get(owner.name);
		return node == null || !isBuiltByPlugin(node, owner);
	}

	private boolean isConstructible(ApiModel.ClassDef classDef) {
		if (overrides.has("not-constructible", classDef.name)) {
			return false;
		}
		return overrides.has("constructible", classDef.name)
				|| (parameterTypes.contains(classDef.name) && classDef.functions.isEmpty());
	}

	/** Each method signature the superclasses declare, with its erased return type and whether it is static. */
	private Map<String, Inherited> inheritedMethods(ApiModel.ClassDef classDef) {
		Map<String, Inherited> found = new HashMap<>();
		Set<String> visited = new HashSet<>();
		String superName = classDef.superclass;
		while (superName != null && visited.add(superName)) {
			Node node = byLuaName.get(model.resolveAlias(superName));
			ApiModel.ClassDef superDef = node == null ? null : node.classDef;
			if (superDef == null) {
				break;
			}
			Shape superShape = shape(superDef);
			for (ApiModel.FunctionDef function : superDef.functions) {
				if (superShape == Shape.INSTANCE && isConstructor(function)) {
					continue;
				}
				boolean isStatic = superShape == Shape.INSTANCE && function.style() == ApiModel.CallStyle.DOT;
				String javaName = javaMethodName(superDef.name, function.name());
				for (Form form : forms(superDef.name, function)) {
					found.putIfAbsent(javaName + "(" + erased(form.params()) + ")",
							new Inherited(form.returned().erased(), isStatic));
				}
			}
			superName = superDef.superclass;
		}
		return found;
	}

	/** Whether one binding class extends another, directly or further up, by their Java references. */
	private boolean isSubclass(String subclass, String superclass) {
		Node node = null;
		for (Node candidate : byLuaName.values()) {
			if (candidate.reference().equals(subclass)) {
				node = candidate;
				break;
			}
		}
		Set<String> visited = new HashSet<>();
		while (node != null && node.classDef != null && visited.add(node.reference())) {
			String superName = node.classDef.superclass;
			node = superName == null ? null : byLuaName.get(model.resolveAlias(superName));
			if (node != null && node.reference().equals(superclass)) {
				return true;
			}
		}
		return false;
	}

	private void eventField(StringBuilder out, ApiModel.FieldDef field, String indent) {
		EventInfo event = events.get(field.name());
		if (event == null) {
			return;
		}
		Node payload = byLuaName.get(event.payload());
		String payloadType = payload == null ? "Object" : payload.reference();
		String type = (event.predicate() ? "EventPredicateSubscription<" : "EventSubscription<") + payloadType + ">";
		out.append("\n");
		List<String> doc = new ArrayList<>(lines(field.description()));
		doc.add("<p>Lua: {@code Event." + field.name() + ".Subscribe(name, callback)}");
		javadoc(out, indent, doc);
		memberAnnotations(out, "Event." + field.name(), indent);
		out.append(indent).append("public static final ").append(type).append(" ").append(field.name())
				.append(" = LuaOnly.value();\n");
	}

	private void field(StringBuilder out, ApiModel.ClassDef owner, ApiModel.FieldDef field, boolean isStatic,
			String indent, Set<String> signatures) {
		String target = owner.name + "." + field.name();
		TypeRef type = field.type();
		if (field.name().startsWith("[")) {
			// ---@field [Int] ui.StyleSheet: the table itself is indexed
			TypeRef keyType = TypeParser.parse(field.name().substring(1, field.name().length() - 1));
			JType key = javaType(keyType);
			if (!signatures.add("get(" + key.erased() + ")")) {
				return;
			}
			out.append("\n");
			javadoc(out, indent, List.of("The entry under the key.", "", "<p>Lua: {@code " + receiver(owner, isStatic)
					+ "[key]}"));
			out.append(indent).append("@LuaIndex\n");
			out.append(indent).append("public ").append(isStatic ? "static " : "").append("native ")
					.append(javaType(type).text()).append(" get(").append(key.text()).append(" key);\n");
			return;
		}
		if (isStatic && type.withoutNil() instanceof TypeRef.Function function) {
			// A function in a global table's field is a function of the table: utils.CalcTextWidth(fontID, text)
			ApiModel.FunctionDef asFunction = new ApiModel.FunctionDef(owner.name, field.name(), ApiModel.CallStyle.DOT,
					function.params().stream().map(TypeRef.Param::name).toList(),
					function.params().stream()
							.map(param -> new ApiModel.ParamDef(param.name(), param.type(), "", false))
							.toList(),
					function.returns().stream().map(returned -> new ApiModel.ReturnDef(returned, "", "")).toList(),
					List.of(), false, field.description(), "");
			method(out, owner, asFunction, Shape.NAMESPACE, indent, signatures, Map.of());
			return;
		}
		JType javaType = nullability(target, type, javaType(type));
		boolean readOnly = isReadOnly(owner, field);
		boolean copied = !isStatic && isCopied(owner, field);
		out.append("\n");
		List<String> doc = new ArrayList<>(lines(field.description()));
		String luaName = field.name();
		doc.add("<p>Lua: {@code " + receiver(owner, isStatic) + "." + luaName + "}" + (readOnly ? ", read-only" : "")
				+ (copied ? ", a copy: change a local copy and assign it back" : ""));
		javadoc(out, indent, doc);
		memberAnnotations(out, target, indent);
		if (copied) {
			out.append(indent).append("@LuaCopy\n");
		}
		String javaName = overrides.value("java-name", target);
		if (javaName == null) {
			javaName = javaIdentifier(luaName);
		}
		if (!javaName.equals(luaName)) {
			out.append(indent).append("@LuaName(").append(javaString(luaName)).append(")\n");
		}
		out.append(indent).append("public ").append(isStatic ? "static " : "").append(readOnly ? "final " : "")
				.append(javaType.text()).append(" ").append(javaName)
				.append(isStatic && readOnly ? " = LuaOnly.value()" : "").append(";\n");
	}

	/** The receiver a member's Lua form shows: the table for a static member, a name for the object otherwise. */
	private static String receiver(ApiModel.ClassDef owner, boolean isStatic) {
		return isStatic ? luaPath(owner.name) : lowerFirstWord(simpleLuaName(owner.name));
	}

	/** A type with the nullability the overrides give its member. */
	private JType nullability(String target, TypeRef type, JType javaType) {
		if (overrides.corrects("nullable", target, type.isNullable()) && !type.isNullable()) {
			return javaType.nullable();
		}
		if (overrides.corrects("not-nullable", target, !type.isNullable()) && type.isNullable()) {
			return javaType(type.withoutNil());
		}
		return javaType;
	}

	/**
	 * Whether a field is read-only: the docs say so, or the overrides do for the field or its whole class, or it is an
	 * event payload's, whose few writable fields the overrides name.
	 */
	private boolean isReadOnly(ApiModel.ClassDef owner, ApiModel.FieldDef field) {
		String target = owner.name + "." + field.name();
		if (overrides.has("writable", target)) {
			return false;
		}
		// Both overrides are asked even when the docs say so already, so that one the stubs catch up with is noticed
		boolean fieldOverride = overrides.corrects("read-only", target, field.readOnly());
		boolean classOverride = overrides.corrects("read-only", owner.name, field.readOnly());
		if (field.readOnly() || fieldOverride || classOverride) {
			return true;
		}
		Node node = byLuaName.get(owner.name);
		return node != null && node.payloadOf != null;
	}

	private void method(StringBuilder out, ApiModel.ClassDef owner, ApiModel.FunctionDef function, Shape shape,
			String indent, Set<String> signatures, Map<String, Inherited> inherited) {
		String target = owner.name + "." + function.name();
		if (overrides.has("skip", target)) {
			return;
		}
		boolean colon = function.style() == ApiModel.CallStyle.COLON;
		boolean isStatic = shape == Shape.NAMESPACE || (shape == Shape.INSTANCE && !colon);
		String javaName = javaMethodName(owner.name, function.name());
		for (Form untyped : forms(owner.name, function)) {
			Form form = new Form(hookTyped(untyped.params()), untyped.returned());
			boolean generic = form.params().stream().anyMatch(param -> param.type().text().equals("C"));
			String key = javaName + "(" + erased(form.params()) + ")";
			if (OBJECT_METHODS.contains(key)) {
				warnings.add(target + " has the signature of Object's " + key + "; it is left out.");
				continue;
			}
			if (!signatures.add(key) || !overridable(target, form, isStatic, inherited.get(key))) {
				continue;
			}
			out.append("\n");
			List<String> doc = new ArrayList<>(lines(function.description()));
			doc.add("<p>Lua: {@code " + receiver(owner, isStatic) + (colon ? ":" : ".") + function.name() + "("
					+ luaArguments(form.params()) + (function.varargs() ? (form.params().isEmpty() ? "..." : ", ...") : "")
					+ ")}");
			paramDocs(doc, form.params());
			if (generic) {
				doc.add("@param <C> the callback the hook takes");
			}
			if (!function.returns().isEmpty() && !form.returned().text().equals("void")) {
				String text = function.returns().getFirst().description();
				if (!text.isEmpty()) {
					doc.add("@return " + escape(text));
				}
			}
			javadoc(out, indent, doc);
			memberAnnotations(out, target, indent);
			if (shape == Shape.NAMESPACE && colon) {
				out.append(indent).append("@LuaColon\n");
			}
			if (!isStatic && !colon) {
				out.append(indent).append("@LuaDot\n");
			}
			if (!javaName.equals(function.name())) {
				out.append(indent).append("@LuaName(").append(javaString(function.name())).append(")\n");
			}
			out.append(indent).append("public ").append(isStatic ? "static " : "").append("native ")
					.append(generic ? "<C> " : "").append(form.returned().text()).append(" ").append(javaName).append("(")
					.append(parameterList(form.params(), function.varargs())).append(");\n");
		}
		List<String> names = overrides.arguments("returns", target);
		if (function.returns().size() > 1 && names != null && !names.isEmpty() && writtenRecords.add(target)) {
			out.append("\n");
			returnValues(out, owner, function, names, indent);
		}
	}

	/**
	 * Whether a class may declare a method with a signature a superclass declares too: when it returns the same, the
	 * inherited method serves; when it returns a subclass of what the inherited one returns, as {@code Vector2.Abs()}
	 * does where {@code Vector.Abs()} returns {@code Vector}, it is a covariant override; otherwise Java would reject
	 * it, and the inherited one is kept.
	 */
	private boolean overridable(String target, Form form, boolean isStatic, @Nullable Inherited inherited) {
		if (inherited == null) {
			return true;
		}
		String returned = form.returned().erased();
		if (inherited.isStatic() != isStatic || (!returned.equals(inherited.returned())
				&& !isSubclass(returned, inherited.returned()))) {
			warnings.add(target + "(" + erased(form.params()) + ") does not fit the method it inherits, which returns "
					+ inherited.returned() + "; the inherited one is kept.");
			return false;
		}
		return !returned.equals(inherited.returned());
	}

	/** The record a function with several returns gives back, {@code @LuaReturnValues}, nested in its owner. */
	private void returnValues(StringBuilder out, ApiModel.ClassDef owner, ApiModel.FunctionDef function,
			List<String> names, String indent) {
		List<String> doc = new ArrayList<>();
		doc.add("The values {@code " + luaPath(owner.name) + "." + function.name() + "} returns, in order.");
		List<String> components = new ArrayList<>();
		for (int index = 0; index < function.returns().size() && index + 1 < names.size(); index++) {
			ApiModel.ReturnDef returned = function.returns().get(index);
			String name = names.get(index + 1);
			doc.add("@param " + name + (returned.description().isEmpty() ? "" : " " + escape(returned.description())));
			components.add(javaType(returned.type()).text() + " " + name);
		}
		javadoc(out, indent, doc);
		out.append(indent).append("@LuaReturnValues\n");
		out.append(indent).append("public record ").append(names.getFirst()).append("(")
				.append(String.join(", ", components)).append(") {\n").append(indent).append("}\n");
	}

	/** What a function returns in Java: nothing, its one value, or the record that names its several values. */
	private JType returnType(String owner, ApiModel.FunctionDef function) {
		String target = owner + "." + function.name();
		List<ApiModel.ReturnDef> returns = function.returns();
		if (returns.isEmpty()) {
			return JType.VOID;
		}
		if (returns.size() > 1) {
			List<String> names = overrides.arguments("returns", target);
			if (names != null && !names.isEmpty()) {
				Node node = byLuaName.get(owner);
				return JType.reference((node == null ? luaPath(owner) : node.reference()) + "." + names.getFirst());
			}
			warnings.add(target + " returns " + returns.size() + " values, but the overrides name none; only the first"
					+ " is bound.");
		}
		TypeRef type = returns.getFirst().type();
		if (type instanceof TypeRef.Named named && named.name().equals("nil")) {
			return JType.VOID;
		}
		return nullability(target, type, javaType(type));
	}

	private String javaMethodName(String owner, String luaName) {
		String renamed = overrides.value("java-name", owner + "." + luaName);
		if (renamed != null) {
			return renamed;
		}
		return luaName.equals("new") ? "create" : javaIdentifier(luaName);
	}

	/**
	 * The Java forms of a function: its own and each {@code ---@overload}, each with shorter forms that leave out its
	 * optional trailing parameters, and one form per member type of a union parameter. Forms Java cannot tell apart
	 * are written once, the first one kept.
	 */
	private List<Form> forms(String owner, ApiModel.FunctionDef function) {
		JType declaredReturn = returnType(owner, function);
		List<Variant> variants = new ArrayList<>(lockstep(declaredParams(function), function, declaredReturn));
		for (TypeRef overload : function.overloads()) {
			if (overload instanceof TypeRef.Function shape) {
				List<ApiModel.ParamDef> params = new ArrayList<>();
				for (TypeRef.Param param : shape.params()) {
					if (!param.name().equals("self") && !param.name().equals("...")) {
						params.add(new ApiModel.ParamDef(param.name(), param.type(), "", false));
					}
				}
				variants.add(new Variant(params,
						shape.returns().isEmpty() ? declaredReturn : javaType(shape.returns().getFirst())));
			}
		}
		List<Form> forms = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (Variant variant : variants) {
			List<ApiModel.ParamDef> params = variant.params();
			int required = params.size();
			while (required > 0 && params.get(required - 1).optional()) {
				required--;
			}
			for (int length = params.size(); length >= required; length--) {
				for (List<JParam> expanded : expandUnions(params.subList(0, length))) {
					if (seen.add(erased(expanded))) {
						forms.add(new Form(expanded, variant.returned()));
					}
				}
			}
		}
		return forms;
	}

	/**
	 * A parameter list whose union parameters all have the same member types, as one variant per member type, with the
	 * return of the same union narrowed alike: {@code lerp(start, final, t)}, whose {@code start}, {@code final} and
	 * return are each {@code Float|Vector2|...}, becomes {@code lerp(Vector2, Vector2, double)} returning
	 * {@code Vector2}, and so on. Any other list is one variant, and its unions expand independently.
	 */
	private List<Variant> lockstep(List<ApiModel.ParamDef> params, ApiModel.FunctionDef function, JType declaredReturn) {
		List<TypeRef> options = null;
		for (ApiModel.ParamDef param : params) {
			if (param.type().withoutNil() instanceof TypeRef.Union union) {
				if (options == null) {
					options = union.options();
				} else if (!shown(options).equals(shown(union.options()))) {
					return List.of(new Variant(params, declaredReturn));
				}
			}
		}
		if (options == null) {
			return List.of(new Variant(params, declaredReturn));
		}
		List<String> shared = shown(options);
		TypeRef returned = function.returns().size() == 1 ? function.returns().getFirst().type() : null;
		boolean returnFollows = returned != null && returned.withoutNil() instanceof TypeRef.Union union
				&& shown(union.options()).equals(shared);
		List<Variant> variants = new ArrayList<>();
		for (TypeRef option : options) {
			List<ApiModel.ParamDef> narrowed = new ArrayList<>();
			for (ApiModel.ParamDef param : params) {
				boolean follows = param.type().withoutNil() instanceof TypeRef.Union union
						&& shown(union.options()).equals(shared);
				TypeRef type = !follows ? param.type()
						: param.type().isNullable() ? new TypeRef.Optional(option) : option;
				narrowed.add(new ApiModel.ParamDef(param.name(), type, param.description(), param.optionalName()));
			}
			JType variantReturn = declaredReturn;
			if (returnFollows && returned != null) {
				variantReturn = returned.isNullable() ? javaType(option).nullable() : javaType(option);
			}
			variants.add(new Variant(narrowed, variantReturn));
		}
		return variants;
	}

	private static List<String> shown(List<TypeRef> types) {
		return types.stream().map(TypeRef::show).toList();
	}

	/** A function's parameters from its {@code ---@param} tags, or untyped ones from its Lua parameter list. */
	private static List<ApiModel.ParamDef> declaredParams(ApiModel.FunctionDef function) {
		List<ApiModel.ParamDef> params = new ArrayList<>();
		if (!function.params().isEmpty()) {
			function.params().stream().filter(param -> !param.name().equals("...")).forEach(params::add);
			return params;
		}
		for (String name : function.luaParams()) {
			if (!name.equals("...")) {
				params.add(new ApiModel.ParamDef(name, TypeRef.Named.of("any"), "", false));
			}
		}
		return params;
	}

	private List<List<JParam>> expandUnions(List<ApiModel.ParamDef> params) {
		List<List<JParam>> results = new ArrayList<>();
		results.add(new ArrayList<>());
		for (ApiModel.ParamDef param : params) {
			TypeRef type = param.type().withoutNil();
			List<TypeRef> options = type instanceof TypeRef.Union union ? union.options() : List.of(type);
			if (results.size() * options.size() > MAX_UNION_OVERLOADS) {
				options = List.of(TypeRef.Named.of("any"));
			}
			boolean nullable = param.type().isNullable();
			List<List<JParam>> next = new ArrayList<>();
			for (List<JParam> partial : results) {
				for (TypeRef option : options) {
					List<JParam> extended = new ArrayList<>(partial);
					JType javaType = javaType(option);
					extended.add(new JParam(parameterName(param.name(), partial), nullable ? javaType.nullable() : javaType,
							escape(param.description())));
					next.add(extended);
				}
			}
			results = next;
		}
		return results;
	}

	private static String erased(List<JParam> params) {
		return params.stream().map(param -> param.type().erased()).collect(Collectors.joining(","));
	}

	private static String parameterName(String luaName, List<JParam> before) {
		String base = luaName.isEmpty() ? "value" : luaName;
		if (!SourceVersion.isName(base)) {
			base = SourceVersion.isKeyword(base) ? base + "Value" : "value";
		}
		String candidate = base;
		Set<String> taken = before.stream().map(JParam::name).collect(Collectors.toSet());
		for (int suffix = 2; taken.contains(candidate) || candidate.equals("args"); suffix++) {
			candidate = base + suffix;
		}
		return candidate;
	}

	private static String parameterList(List<JParam> params, boolean varargs) {
		List<String> written = new ArrayList<>();
		params.forEach(param -> written.add(param.type().text() + " " + param.name()));
		if (varargs) {
			written.add("Object... args");
		}
		return String.join(", ", written);
	}

	private static String luaArguments(List<JParam> params) {
		return params.stream().map(JParam::name).collect(Collectors.joining(", "));
	}

	private static void paramDocs(List<String> doc, List<JParam> params) {
		for (JParam param : params) {
			doc.add("@param " + param.name() + (param.description().isEmpty() ? "" : " " + param.description()));
		}
	}

	// ---- Types

	/**
	 * The Java type of a LuaCATS type: Lua's numbers, booleans and strings as Java's; {@code T[]} as {@code List<T>};
	 * {@code { [K]: V }} and {@code table<K, V>} as {@code Map<K, V>}; a tuple as a list; functions as
	 * {@code java.util.function} interfaces; {@code table}, {@code any} and unions as {@code Object}; nullable types
	 * annotated {@code @Nullable}.
	 */
	private JType javaType(TypeRef type) {
		if (type instanceof TypeRef.Named named && named.name().equals("nil")) {
			return JType.VOID;
		}
		if (type.isNullable()) {
			JType inner = javaType(type.withoutNil());
			return inner.text().equals("void") ? JType.OBJECT : inner.nullable();
		}
		return switch (type) {
			case TypeRef.Named named -> named(named);
			case TypeRef.ArrayOf array -> JType.reference("java.util.List<" + javaType(array.element()).boxed() + ">");
			case TypeRef.Optional optional -> javaType(optional.inner()).nullable();
			case TypeRef.Union _ -> JType.OBJECT;
			case TypeRef.Function function -> functional(function);
			case TypeRef.Table table -> table(table);
			case TypeRef.Literal _ -> JType.reference("String");
		};
	}

	private JType named(TypeRef.Named named) {
		String original = named.name();
		if (original.equals("Long") || original.equals("Uint64")) {
			return JType.primitive("long", "Long");
		}
		String name = model.resolveAlias(original);
		switch (name) {
			case "integer" -> {
				return JType.primitive("int", "Integer");
			}
			case "number" -> {
				return JType.primitive("double", "Double");
			}
			case "boolean" -> {
				return JType.primitive("boolean", "Boolean");
			}
			case "string" -> {
				return JType.reference("String");
			}
			case "nil" -> {
				return JType.VOID;
			}
			case "table" -> {
				return named.args().size() == 2
						? JType.reference("java.util.Map<" + javaType(named.args().get(0)).boxed() + ", "
								+ javaType(named.args().get(1)).boxed() + ">")
						: JType.OBJECT;
			}
			case "any", "unknown" -> {
				return JType.OBJECT;
			}
			case "function" -> {
				return JType.reference("Runnable");
			}
			default -> {
			}
		}
		ApiModel.ClassDef classDef = model.classes.get(name);
		if (classDef != null && "integer".equals(classDef.superclass)) {
			return JType.primitive("int", "Integer");
		}
		Node node = byLuaName.get(name);
		if (node == null) {
			warnings.add("The type " + original + " has no binding class; Object stands in for it.");
			return JType.OBJECT;
		}
		ApiModel.EnumDef enumDef = node.enumDef;
		if (enumDef != null && typeUses.getOrDefault(enumDef.name(), 0) == 0) {
			return enumDef.integerValued() ? JType.primitive("int", "Integer") : JType.reference("String");
		}
		return JType.reference(node.reference());
	}

	private JType functional(TypeRef.Function function) {
		List<TypeRef.Param> params = function.params().stream().filter(param -> !param.name().equals("...")).toList();
		TypeRef returned = function.returns().isEmpty() ? null : function.returns().getFirst();
		boolean returnsNothing = returned == null
				|| (returned instanceof TypeRef.Named named && named.name().equals("nil"));
		boolean returnsBoolean = returned != null && returned.withoutNil() instanceof TypeRef.Named named
				&& model.resolveAlias(named.name()).equals("boolean");
		String result = returned == null || returnsNothing ? "" : javaType(returned).boxed();
		String joined = params.stream().map(param -> javaType(param.type()).boxed()).collect(Collectors.joining(", "));
		String prefix = "java.util.function.";
		return switch (params.size()) {
			case 0 -> JType.reference(returnsNothing ? "Runnable"
					: returnsBoolean ? prefix + "BooleanSupplier"
					: prefix + "Supplier<" + result + ">");
			case 1 -> JType.reference(returnsNothing ? prefix + "Consumer<" + joined + ">"
					: returnsBoolean ? prefix + "Predicate<" + joined + ">"
					: prefix + "Function<" + joined + ", " + result + ">");
			case 2 -> JType.reference(returnsNothing ? prefix + "BiConsumer<" + joined + ">"
					: returnsBoolean ? prefix + "BiPredicate<" + joined + ">"
					: prefix + "BiFunction<" + joined + ", " + result + ">");
			default -> {
				warnings.add("The function type " + function.show() + " has more parameters than java.util.function"
						+ " covers; Object stands in for it.");
				yield JType.OBJECT;
			}
		};
	}

	private JType table(TypeRef.Table table) {
		if (table.isTuple()) {
			Set<String> types = table.entries().stream()
					.map(entry -> javaType(entry.value()).boxed())
					.collect(Collectors.toCollection(LinkedHashSet::new));
			return JType.reference("java.util.List<" + (types.size() == 1 ? types.iterator().next() : "Object") + ">");
		}
		if (table.entries().size() == 1) {
			TypeRef.Entry entry = table.entries().getFirst();
			TypeRef key = entry.keyType();
			if (key != null) {
				return JType.reference("java.util.Map<" + javaType(key).boxed() + ", " + javaType(entry.value()).boxed()
						+ ">");
			}
		}
		return JType.OBJECT;
	}

	// ---- Fixed files

	/**
	 * A source file: the header, the imports, and the classes. The classes name the JDK's types fully, which keeps the
	 * generator simple; here each becomes an import and its simple name, except where a class nested in the file takes
	 * that name ({@code ui.List}), and only a qualified name can reach the JDK's.
	 */
	private static String file(String classes, Set<String> shadowed) {
		Set<String> imports = new TreeSet<>(ANNOTATION_IMPORTS);
		String body = classes;
		Matcher qualified = JDK_TYPE.matcher(classes);
		Set<String> found = new TreeSet<>();
		while (qualified.find()) {
			found.add(qualified.group());
		}
		for (String name : found) {
			String simple = name.substring(name.lastIndexOf('.') + 1);
			if (!shadowed.contains(simple)) {
				imports.add(name);
				body = body.replaceAll("\\b" + Pattern.quote(name) + "\\b", simple);
			}
		}
		StringBuilder header = new StringBuilder("// Generated by Oneiro's bindings-gen from runescape-api-stubs on this"
				+ " machine; not for distribution.\npackage " + PACKAGE + ";\n\n");
		imports.forEach(name -> header.append("import ").append(name).append(";\n"));
		return header.append("\n").append(body).toString();
	}

	/** The simple names of the classes nested in a top-level class, at any depth. */
	private static Set<String> nestedNames(Node node) {
		Set<String> names = new HashSet<>();
		for (Node child : node.children.values()) {
			names.add(child.javaName);
			names.addAll(nestedNames(child));
		}
		return names;
	}

	private static String packageInfo() {
		return """
				/**
				 * The RS3 Client Plugin API as Oneiro's Java bindings: one class per Lua table, so that the Java reads like
				 * the Lua it becomes. Generated by Oneiro's bindings-gen from runescape-api-stubs on this machine.
				 */
				@NullMarked
				package %s;

				import org.jspecify.annotations.NullMarked;
				""".formatted(PACKAGE);
	}

	private static String luaOnly() {
		return """
				// Generated by Oneiro's bindings-gen; not for distribution.
				package %s;

				/** Bindings stand for Lua that the client provides; none of them runs on the JVM. */
				final class LuaOnly {

					private LuaOnly() {
					}

					/** The value of a binding field, which exists only in Lua. */
					static <T> T value() {
						throw error();
					}

					static UnsupportedOperationException error() {
						return new UnsupportedOperationException("RS3 bindings exist only in Lua; Oneiro compiles calls to"
								+ " them, they never run on the JVM.");
					}
				}
				""".formatted(PACKAGE);
	}

	private String subscription(boolean predicate) {
		String name = predicate ? "EventPredicateSubscription" : "EventSubscription";
		String callback = predicate ? "java.util.function.Predicate<T>" : "java.util.function.Consumer<T>";
		ApiModel.ClassDef stub = model.classes.get("EventSubscription");
		List<String> doc = new ArrayList<>(stub == null ? List.of() : lines(stub.description));
		doc.add(predicate
				? "<p>The callbacks of this event return a boolean that the client acts on."
				: "<p>The callbacks of this event return nothing.");
		doc.add("<p>Lua: {@code Event.Name.Subscribe(name, callback)}; a plugin class's {@code @Subscribe} methods are"
				+ " subscribed this way.");
		doc.add("@param <T> the event's payload class");
		StringBuilder out = new StringBuilder();
		javadoc(out, "", doc);
		out.append("@LuaType(\"EventSubscription\")\n");
		out.append("public final class ").append(name).append("<T> {\n\n");
		out.append("\tprivate ").append(name).append("() {\n\t\tthrow LuaOnly.error();\n\t}\n\n");
		javadoc(out, "\t", List.of("Subscribes the callback under the name, replacing what this plugin subscribed under"
				+ " it to this event before.", "", "<p>Lua: {@code Event.Name.Subscribe(name, callback)}",
				"@param name the subscription's name, such as {@code \"my-plugin.tick\"}",
				"@param callback called with the event's payload"));
		out.append("\t@LuaDot\n\tpublic native void Subscribe(String name, ").append(callback).append(" callback);\n\n");
		javadoc(out, "\t", List.of("Removes this plugin's subscription under the name.", "",
				"<p>Lua: {@code Event.Name.Unsubscribe(name)}", "@param name the name it was subscribed under"));
		out.append("\t@LuaDot\n\tpublic native void Unsubscribe(String name);\n}\n");
		return file(out.toString(), Set.of());
	}

	/** The global functions, {@code print}, {@code log}, {@code formatStr} and the rest, as static methods. */
	private String globals() {
		StringBuilder out = new StringBuilder();
		javadoc(out, "", List.of("The Lua globals that are functions: {@code Globals.log(text)} is {@code log(text)}.",
				"Import them statically to write {@code log(text)} in Java too.",
				"",
				"<p>Lua: the global functions."));
		out.append("@LuaGlobal(\"\")\npublic final class Globals {\n\n");
		out.append("\tprivate Globals() {\n\t\tthrow LuaOnly.error();\n\t}\n");
		Set<String> signatures = new HashSet<>();
		for (ApiModel.FunctionDef function : model.globalFunctions) {
			if (overrides.has("skip", function.name())) {
				continue;
			}
			String javaName = javaIdentifier(function.name());
			for (Form form : forms("", function)) {
				if (!signatures.add(javaName + "(" + erased(form.params()) + ")")) {
					continue;
				}
				out.append("\n");
				List<String> doc = new ArrayList<>(lines(function.description()));
				doc.add("<p>Lua: {@code " + function.name() + "(" + luaArguments(form.params())
						+ (function.varargs() ? (form.params().isEmpty() ? "..." : ", ...") : "") + ")}");
				paramDocs(doc, form.params());
				javadoc(out, "\t", doc);
				memberAnnotations(out, function.name(), "\t");
				out.append("\tpublic static native ").append(form.returned().text()).append(" ").append(javaName)
						.append("(").append(parameterList(form.params(), function.varargs())).append(");\n");
			}
		}
		out.append("}\n");
		return file(out.toString(), Set.of());
	}

	// ---- Text

	private static String javaIdentifier(String luaName) {
		if (SourceVersion.isName(luaName)) {
			return luaName;
		}
		String cleaned = luaName.replaceAll("[^A-Za-z0-9_$]", "_");
		if (cleaned.isEmpty() || !Character.isJavaIdentifierStart(cleaned.charAt(0))) {
			cleaned = "_" + cleaned;
		}
		return SourceVersion.isName(cleaned) ? cleaned : cleaned + "_";
	}

	private static String javaString(String text) {
		return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private static String simpleLuaName(String luaName) {
		return luaName.substring(luaName.lastIndexOf('.') + 1);
	}

	/** The name with its first word in lower case, an acronym whole: HTTPRequest is httpRequest, AABB is aabb. */
	private static String lowerFirstWord(String name) {
		int capitals = 0;
		while (capitals < name.length() && Character.isUpperCase(name.charAt(capitals))) {
			capitals++;
		}
		// Of an acronym followed by a word, the last capital starts that word
		int end = capitals > 1 && capitals < name.length() && Character.isLowerCase(name.charAt(capitals))
				? capitals - 1
				: capitals;
		return name.substring(0, end).toLowerCase(Locale.ROOT) + name.substring(end);
	}

	/** Text from the stubs as Javadoc lines, escaped. */
	private static List<String> lines(String text) {
		return text.isBlank() ? List.of() : text.lines().map(String::strip).map(JavaBindings::escape).toList();
	}

	/** A Javadoc comment of lines that are already Javadoc text. */
	private static void javadoc(StringBuilder out, String indent, List<String> lines) {
		if (lines.isEmpty()) {
			return;
		}
		out.append(indent).append("/**\n");
		boolean previousBlank = true;
		boolean tags = false;
		for (String line : lines) {
			// A blank line before each paragraph and before the block tags, as Javadoc is usually laid out
			boolean opensSection = line.startsWith("<p>") || (line.startsWith("@") && !tags);
			if (opensSection && !previousBlank) {
				out.append(indent).append(" *\n");
			}
			tags |= line.startsWith("@");
			out.append(indent).append(line.isEmpty() ? " *" : " * " + line.replace("*/", "*&#47;")).append("\n");
			previousBlank = line.isEmpty();
		}
		out.append(indent).append(" */\n");
	}

	/** Text from the stubs as Javadoc shows it: HTML's special characters and Javadoc's tag characters escaped. */
	private static String escape(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("@", "&#64;")
				.replace("{", "&#123;").replace("}", "&#125;");
	}
}
