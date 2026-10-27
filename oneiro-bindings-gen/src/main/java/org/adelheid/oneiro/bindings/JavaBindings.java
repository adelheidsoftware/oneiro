package org.adelheid.oneiro.bindings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.lang.model.SourceVersion;
import org.adelheid.oneiro.api.LuaConstants;
import org.jspecify.annotations.Nullable;

/**
 * Writes the Java sources of a set of bindings from a model and its overrides: one class per table, with nested classes
 * for nested tables, so that {@code ui.Text} in Lua is {@code ui.Text} in Java. A global table is a class of the
 * package; a library's module is one too, named as its {@code require} binds it, and the tables its own table holds in
 * fields are nested in it, so that {@code widgets.Counter} in Lua is {@code widgets.Counter} in Java. The
 * plugin's own modules are classes of the plugin's packages, each in the package of its file's folder. Each class and
 * member carries the annotations that tell Oneiro its Lua shape, and the Javadoc of its description. A type the Lua
 * names but does not declare, such as {@code ui.Component}, is the RS3 bindings' class, or another library's.
 *
 * <p>The RS3 API's own parts are written for its bindings alone ({@link BindingsTarget}): the events, the hooks, and
 * the {@code id}, {@code config} and {@code var} namespaces, whose names are not written as sources: they are returned
 * for {@link ConstantChunks}, and the namespace classes extend them.
 *
 * <p>This class has the model corrected by {@link ModelCorrections}, arranges the classes in their tree, and writes
 * each class's declaration and constructors. {@link MemberBindings} writes the members, {@link ConstantTables} the
 * tables of constants, {@link TypeMapping} chooses the Java types, and {@link SourceFiles} writes each file's header
 * and the files that come from no table.
 */
final class JavaBindings {

	/** How many names each chunk class of a constant namespace holds. */
	static final int CHUNK_SIZE = 20_000;

	private static final String NULLABLE = "@Nullable";
	/** What separates the words of a file's name, {@code opening_table}. */
	private static final Pattern NOT_WORD = Pattern.compile("[^A-Za-z0-9]+");

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
	enum Shape {
		/**
		 * A global table that is a namespace or a singleton: static members, colon functions with {@code @LuaColon}.
		 */
		NAMESPACE,
		/** A global table of constructors and the type of the objects they make. */
		INSTANCE,
		/** Values without a table to reach: entities, payloads, settings and data shapes. */
		TYPE,
	}

	/** A Java type as a binding writes it, with its boxed form for type arguments. */
	record JType(String text, String boxed, boolean primitive) {

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
	record JParam(String name, JType type, String description) {
	}

	/** One Java form of a Lua function: its parameters and what it returns. */
	record Form(List<JParam> params, JType returned) {
	}

	/** A Lua parameter list and the Java return type that goes with it, before optional parameters are left out. */
	record Variant(List<ApiModel.ParamDef> params, JType returned) {
	}

	/** A method a superclass declares: its erased return type, and whether it is static. */
	record Inherited(String returned, boolean isStatic) {
	}

	/** An event of the client's {@code Event} table. */
	record EventInfo(String name, String payload, boolean predicate) {
	}

	/**
	 * One hook constant: its Java and Lua names (the same, but for a second form of one hook), the callback interface
	 * it takes, and the callback's parameters after the component.
	 */
	record HookLine(String javaName, String luaName, String callback, List<TypeRef.Param> params) {
	}

	/** A class to write, at its place in the tree of nested classes. */
	static final class Node {
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
		/** For a library's module, as a top-level class: the path {@code require} loads it by. */
		@Nullable String module;
		/**
		 * For a top-level class: the file that declares its table or type, or for a namespace that a dotted name
		 * makes, the file that declares that name, in the folder of Lua files; empty when none does.
		 */
		String file = "";

		Node(String javaName, String luaSegment, @Nullable Node parent) {
			this.javaName = javaName;
			this.luaSegment = luaSegment;
			this.parent = parent;
		}

		/** The class's reference from anywhere in its package: {@code ui.Text}. */
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

		/** The top-level class this one is nested in, or this one. */
		Node top() {
			return parent == null ? this : parent.top();
		}
	}

	final ApiModel model;
	private final Map<String, CsvConstants.Namespace> csvs;
	final Overrides overrides;
	final BindingsTarget target;
	/**
	 * The bindings whose classes a type this model names but does not declare resolves to, in order: the RS3 API's,
	 * then the other libraries'. The generator fills it before the sources are written.
	 */
	final List<JavaBindings> foreign = new ArrayList<>();
	final BindingsGenerator.Language language;
	final List<String> warnings;
	final Map<String, Integer> typeUses;
	private final SourceFiles sourceFiles;
	private final TypeMapping typeMapping;
	private final ModelCorrections corrections;
	private final ConstantTables constantTables;
	private final MemberBindings memberBindings;
	private final Set<String> parameterTypes = new HashSet<>();
	private final Map<String, Node> tops = new TreeMap<>();
	/** The node of each Lua class and enum name, and of each synthesized payload. */
	final Map<String, Node> byLuaName = new HashMap<>();
	/** The nodes of a library's module tables, whose Lua path comes from their module, not from a class's name. */
	private final Set<Node> moduleTables = new HashSet<>();
	/** The classes and enums placed as module tables, which are not placed again by name. */
	private final Set<String> placed = new HashSet<>();
	final Map<String, EventInfo> events = new LinkedHashMap<>();
	private final List<ConstantNamespace> constants = new ArrayList<>();
	/** The operators the compilers lower, by LuaCATS's name: Lua's operator, and the method's name, Kotlin's. */
	private static final Map<String, String[]> LOWERED_OPERATORS = Map.of("add", new String[] {"+", "plus"},
			"sub", new String[] {"-", "minus"}, "mul", new String[] {"*", "times"}, "div", new String[] {"/", "div"});
	final Set<String> writtenRecords = new HashSet<>();
	/** The callback interfaces of more values than java.util.function's take, {@code Function3}, that members name. */
	final Set<String> callbacks = new TreeSet<>();
	/** The classes the stubs lack that {@code field} lines declare, with the first line of each. */
	final Map<String, Overrides.Entry> declaredClasses = new LinkedHashMap<>();
	/** The hook lines, by the Lua name of the table of hooks they describe, {@code ui.Hook}, in order. */
	final Map<String, List<HookLine>> hooks = new LinkedHashMap<>();
	/** The permission each member or table marked {@code @LuaPermission} needs, by its Lua path. */
	final Map<String, String> permissions = new TreeMap<>();
	/** The Lua paths of the members marked {@code @LuaDevOnly}. */
	final Set<String> devOnly = new TreeSet<>();

	/**
	 * The names the model's Lua takes in the global table, the client's for the RS3 API: the first part of each global
	 * class's table path and enum's name, such as {@code Event}, {@code ui} and {@code id}, and the global functions.
	 */
	Set<String> globals() {
		Set<String> names = new TreeSet<>();
		model.classes.values().stream()
				.filter(classDef -> classDef.binding == ApiModel.Binding.GLOBAL)
				.forEach(classDef -> names.add(firstPart(luaPath(classDef.name))));
		model.enums.keySet().forEach(name -> names.add(firstPart(name)));
		model.globalFunctions.forEach(function -> names.add(firstPart(function.name())));
		return names;
	}

	private static String firstPart(String luaName) {
		int dot = luaName.indexOf('.');
		return dot < 0 ? luaName : luaName.substring(0, dot);
	}

	/**
	 * @param csvs the name lists of the RS3 API's constant namespaces; empty for a library
	 */
	JavaBindings(ApiModel model, Map<String, CsvConstants.Namespace> csvs, Overrides overrides, BindingsTarget target,
			BindingsGenerator.Language language, List<String> warnings) {
		this.model = model;
		this.csvs = csvs;
		this.overrides = overrides;
		this.target = target;
		this.language = language;
		this.warnings = warnings;
		this.corrections = new ModelCorrections(this);
		this.sourceFiles = new SourceFiles(this);
		this.typeMapping = new TypeMapping(this);
		this.memberBindings = new MemberBindings(this);
		this.constantTables = new ConstantTables(this);
		corrections.apply();
		this.typeUses = model.typeUses();
		declaredClasses.forEach((name, entry) -> {
			if (typeUses.getOrDefault(name, 0) == 0) {
				warnings.add(overrides.source() + ":" + entry.line() + ": field " + entry.target()
						+ " declares the class " + name
						+ ", which the stubs lack and no type line uses; check its name.");
			}
		});
		collectParameterTypes();
		if (target.isRs3()) {
			collectEvents();
		}
		buildTree();
		Node global = dependencyGlobal();
		if (target.isDependency() && (global == null || global.classDef == null)) {
			warnings.add("The types of " + target.library() + " declare neither the global " + target.library()
					+ " nor a main.lua that returns the library's table, so no class stands for the global.");
		}
	}

	/** For a library plugin, the class of its global, named like its alias; null for other bindings, or none. */
	private @Nullable Node dependencyGlobal() {
		return target.isDependency() ? tops.get(target.library()) : null;
	}

	TypeMapping typeMapping() {
		return typeMapping;
	}

	ConstantTables constantTables() {
		return constantTables;
	}

	MemberBindings memberBindings() {
		return memberBindings;
	}

	/**
	 * The names of the top-level classes by their packages: the one package's, or for the plugin's own Lua, those of
	 * each package its files are in; the callback interfaces are in the first package, the target's.
	 */
	Map<String, Set<String>> topNamesByPackage() {
		Map<String, Set<String>> packages = new TreeMap<>();
		for (Node top : tops.values()) {
			packages.computeIfAbsent(packageOf(top), name -> new TreeSet<>()).add(top.javaName);
		}
		packages.computeIfAbsent(target.packageName(), name -> new TreeSet<>()).addAll(callbacks);
		return packages;
	}

	/**
	 * The package of a top-level class: the target's one package, or for the plugin's own Lua, the package of the file
	 * that declares it ({@link BindingsTarget#packageOf}).
	 */
	String packageOf(Node top) {
		return target.packageOf(top.file);
	}

	/**
	 * The top-level classes, each by its name, or for the plugin's own Lua, whose classes are in several packages, by
	 * its name with its package, so that {@code core/levels.lua} and {@code ui/levels.lua} are two classes.
	 */
	Map<String, Node> tops() {
		return Collections.unmodifiableMap(tops);
	}

	/** The key of a top-level class in {@link #tops()}, for the file that declares it. */
	private String topKey(String javaName, String file) {
		return target.isPlugin() ? target.packageOf(file) + "." + javaName : javaName;
	}

	/**
	 * A class's reference as the generated code names it: from anywhere in the one package, {@code ui.Text}; for the
	 * plugin's own Lua, whose classes are in several packages, with its package, which {@link SourceFiles#file} turns
	 * into an import or leaves out within the package.
	 */
	String qualified(Node node) {
		return target.isPlugin() ? packageOf(node.top()) + "." + node.reference() : node.reference();
	}

	/** The constant namespaces whose chunk classes the namespace classes extend. */
	List<ConstantNamespace> constants() {
		return constants;
	}

	/** Every source file, by its path under the source root. */
	Map<String, String> sources() {
		Map<String, String> files = new TreeMap<>();
		String folder = folder(target.packageName());
		List<String> fixedNames = new ArrayList<>(List.of("LuaOnly"));
		if (!target.isPlugin()) {
			// The plugin's packages are its own, whose package-info its sources may hold, so each class is marked
			files.put(folder + "package-info.java", sourceFiles.packageInfo());
		}
		if (target.isRs3()) {
			files.put(folder + "EventSubscription.java", sourceFiles.subscription(false));
			files.put(folder + "EventPredicateSubscription.java", sourceFiles.subscription(true));
			files.put(folder + "Globals.java", sourceFiles.globals());
			fixedNames.addAll(List.of("EventSubscription", "EventPredicateSubscription", "Globals"));
		} else {
			model.globalFunctions.forEach(function -> warnings.add("The global function " + function.name()
					+ " is left out: the bindings of Lua other than the client's bind its tables."));
		}
		Map<String, Set<String>> lowerCaseNames = new HashMap<>();
		for (Node top : tops.values()) {
			String packageName = packageOf(top);
			Set<String> taken = lowerCaseNames.computeIfAbsent(packageName, name -> lowerCase(fixedNames));
			if (!taken.add(top.javaName.toLowerCase(Locale.ROOT))) {
				throw new IllegalStateException("Two binding classes differ only in case, which some file systems"
						+ " cannot hold apart, or one takes the name of a fixed class: " + top.javaName);
			}
			StringBuilder source = new StringBuilder();
			writeNode(source, top, "");
			files.put(folder(packageName) + top.javaName + ".java",
					sourceFiles.file(source.toString(), SourceFiles.nestedNames(top), packageName));
		}
		// The callbacks are known once the classes that name them are written
		for (String callback : callbacks) {
			if (!lowerCaseNames.computeIfAbsent(target.packageName(), name -> lowerCase(fixedNames))
					.add(callback.toLowerCase(Locale.ROOT))) {
				throw new IllegalStateException("A binding class takes the name of a callback interface: " + callback);
			}
			files.put(folder + callback + ".java", sourceFiles.callback(callback));
		}
		for (String packageName : target.isPlugin() ? lowerCaseNames.keySet() : Set.of(target.packageName())) {
			files.put(folder(packageName) + "LuaOnly.java", sourceFiles.luaOnly(packageName));
		}
		return files;
	}

	private static String folder(String packageName) {
		return packageName.replace('.', '/') + "/";
	}

	private static Set<String> lowerCase(List<String> names) {
		Set<String> lowerCase = new HashSet<>();
		names.forEach(name -> lowerCase.add(name.toLowerCase(Locale.ROOT)));
		return lowerCase;
	}

	// ---- Preparation

	/**
	 * The classes some function takes as a parameter, alone or as one type of a union such as
	 * {@code string|TextOptions}: the data tables a plugin may build.
	 */
	private void collectParameterTypes() {
		for (ApiModel.FunctionDef function : model.allFunctions()) {
			for (ApiModel.ParamDef param : function.params()) {
				TypeRef type = param.type().withoutNil();
				List<TypeRef> options = type instanceof TypeRef.Union union ? union.options() : List.of(type);
				for (TypeRef option : options) {
					if (option instanceof TypeRef.Named named) {
						parameterTypes.add(model.resolveAlias(named.name()));
					}
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
				warnings.add("Event." + field.name()
						+ " is not an EventSubscription<Payload, Return>; it is left out.");
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
		placeModules();
		for (ApiModel.ClassDef classDef : model.classes.values()) {
			if (isGenerated(classDef) && !placed.contains(classDef.name)) {
				Node node = node(luaPath(classDef.name), classDef.files.isEmpty() ? "" : classDef.files.getFirst());
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
			if (placed.contains(enumDef.name())) {
				continue;
			}
			Node node = node(enumDef.name(), enumDef.file());
			node.enumDef = enumDef;
			byLuaName.put(enumDef.name(), node);
		}
		if (!target.isRs3()) {
			return;
		}
		for (EventInfo event : events.values()) {
			Node node = byLuaName.get(event.payload());
			if (node == null) {
				node = node(event.payload(), "");
				byLuaName.put(event.payload(), node);
			}
			node.payloadOf = event;
		}
		for (String global : List.of("id", "config", "var")) {
			Node node = tops.get(javaTopName(global));
			if (node != null) {
				constantNamespaces(node, global);
			}
		}
	}

	/**
	 * A library's modules, placed where Lua reaches them. A module whose table no other module's table holds in a field
	 * is a top-level class, named as its {@code require} binds it; the tables its table holds in fields, the modules it
	 * requires there and the classes and enums it declares there, are classes nested in it, and theirs in them. A table
	 * that two modules hold is placed where it is reached first.
	 *
	 * <p>A library plugin's code is read the same way, but the plugin reaches only one of its tables, what its
	 * {@code main.lua} returns, which the client gives it as the global of its alias: that module is the class named
	 * like the alias, and the plugin cannot require the others, so a table of theirs that it does not hold is no class.
	 */
	private void placeModules() {
		Set<String> held = new HashSet<>();
		model.classes.values().forEach(classDef -> held.addAll(classDef.tables.values()));
		for (ApiModel.ModuleDef module : new TreeMap<>(model.modules).values()) {
			ApiModel.ClassDef classDef = model.classes.get(module.export());
			if (held.contains(module.export()) || classDef == null || placed.contains(classDef.name)) {
				continue;
			}
			if (target.isDependency()) {
				if (module.path().equals("main")) {
					Node node = new Node(target.library(), target.library(), null);
					tops.put(node.javaName, node);
					placeTable(node, classDef);
				}
				continue;
			}
			String name = moduleName(module, classDef);
			String key = topKey(name, module.file());
			if (tops.containsKey(key)) {
				warnings.add("The module " + module.path() + " would be the class " + key
						+ ", which another module is already; it is left out.");
				if (module.implicit()) {
					// Its table's class is named by the module's path, which no class is named by
					placed.add(classDef.name);
				}
				continue;
			}
			Node node = new Node(name, name, null);
			node.module = module.path();
			node.file = module.file();
			tops.put(key, node);
			placeTable(node, classDef);
		}
		if (target.isDependency()) {
			model.modules.values().stream()
					.filter(module -> module.implicit() && !placed.contains(module.export()))
					.forEach(module -> placed.add(module.export()));
		}
	}

	private void placeTable(Node node, ApiModel.ClassDef classDef) {
		node.classDef = classDef;
		placed.add(classDef.name);
		moduleTables.add(node);
		byLuaName.put(classDef.name, node);
		for (Map.Entry<String, String> table : classDef.tables.entrySet()) {
			String field = table.getKey();
			String type = table.getValue();
			String javaName = JavaText.javaIdentifier(field);
			ApiModel.EnumDef enumDef = model.enums.get(type);
			ApiModel.ClassDef held = model.classes.get(type);
			if (placed.contains(type) || (held != null && !isGenerated(held)) || (held == null && enumDef == null)) {
				continue;
			}
			if (node.children.containsKey(javaName)) {
				warnings.add(classDef.name + "." + field
						+ " has the Java name of another of its tables; it is left out.");
				continue;
			}
			Node child = new Node(javaName, field, node);
			node.children.put(javaName, child);
			if (held != null) {
				placeTable(child, held);
			} else {
				child.enumDef = enumDef;
				placed.add(type);
				moduleTables.add(child);
				byLuaName.put(type, child);
			}
		}
	}

	/**
	 * The name of a module's top-level class, which is also the local its {@code require} binds: the last name of the
	 * class it declares for its table, {@code widgets}, or for a table it declares none for, its file's name as a Java
	 * class is named, {@code OpeningTable} for {@code opening_table.lua}.
	 */
	private static String moduleName(ApiModel.ModuleDef module, ApiModel.ClassDef classDef) {
		if (!module.implicit()) {
			return JavaText.javaIdentifier(JavaText.simpleLuaName(classDef.name));
		}
		String file = module.path().substring(module.path().lastIndexOf('/') + 1);
		String name = NOT_WORD.splitAsStream(file)
				.filter(word -> !word.isEmpty())
				.map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
				.collect(Collectors.joining());
		return JavaText.javaIdentifier(name);
	}

	/** Whether a class is a module's table that the module declares no class for, named by the module's path. */
	private boolean isImplicit(ApiModel.ClassDef classDef) {
		return model.modules.values().stream()
				.anyMatch(module -> module.implicit() && module.export().equals(classDef.name));
	}

	/**
	 * Whether a class has a table plugins reach: a global one, or a module's. A library plugin exposes one global, its
	 * alias, so a global its types declare outside it is a type.
	 */
	private boolean hasTable(ApiModel.ClassDef classDef) {
		String path = luaPath(classDef.name);
		boolean global = classDef.binding == ApiModel.Binding.GLOBAL && (!target.isDependency()
				|| path.equals(target.library()) || path.startsWith(target.library() + "."));
		return global || placed.contains(classDef.name);
	}

	/**
	 * The Lua path of a class's table, as its members' Javadoc shows it and its annotation gives it: from its module
	 * for a module's table, {@code widgets.Counter}, and otherwise the class's own name.
	 */
	String tablePath(ApiModel.ClassDef classDef) {
		Node node = byLuaName.get(classDef.name);
		return node != null && moduleTables.contains(node) ? node.luaName() : luaPath(classDef.name);
	}

	/** The Lua path of an enum table: from its module for a module's, and otherwise the enum's own name. */
	String enumPath(Node node, ApiModel.EnumDef enumDef) {
		return moduleTables.contains(node) ? node.luaName() : enumDef.name();
	}

	/**
	 * Where a table is, for a class's Javadoc: a global table, the module a top-level class is, or a table in a
	 * module.
	 */
	private String tableDescription(Node node, String path) {
		if (!moduleTables.contains(node)) {
			return "the global table {@code " + path + "}";
		}
		Node top = node.top();
		if (top.module == null) {
			// A library plugin's global, which the client gives the plugin as a copy of what its main.lua returns
			return node == top
					? "the global {@code " + path + "}, the plugin's copy of the library's table"
					: "the table {@code " + path + "} of the global {@code " + top.luaSegment + "}";
		}
		String module = "{@code " + top.module + "}";
		return node == top
				? "the module " + module + ", the table {@code require(\"" + top.module + "\")} gives"
				: "the table {@code " + path + "} of the module " + module;
	}

	/** Every class but the generic EventSubscription, which has templates of its own, and the integer ID types. */
	private boolean isGenerated(ApiModel.ClassDef classDef) {
		return !classDef.name.equals("EventSubscription") && !"integer".equals(classDef.superclass)
				&& !overrides.has("skip", classDef.name);
	}

	/** The Lua path of a class's table: {@code _global.id} is the table {@code id}. */
	static String luaPath(String luaName) {
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

	/**
	 * The node of a class or enum by its Lua name, made with the classes it nests in where they are missing: its first
	 * part is a top-level class, in the package of the file that declares the name, and each part after it a class
	 * nested in the one before. For the plugin's own Lua, whose classes are in several packages, the longest start of
	 * the name that names a class already placed is that class, wherever it is, so that {@code core.Levels.Options}
	 * nests in the module {@code core.Levels}.
	 */
	private Node node(String luaName, String file) {
		List<String> segments = List.of(luaName.split("\\.", -1));
		@Nullable Node current = null;
		int nested = 1;
		if (target.isPlugin()) {
			for (int length = segments.size() - 1; length > 0 && current == null; length--) {
				current = byLuaName.get(String.join(".", segments.subList(0, length)));
				nested = length;
			}
		}
		if (current == null) {
			String javaName = javaTopName(segments.getFirst());
			current = tops.computeIfAbsent(topKey(javaName, file), key -> {
				Node top = new Node(javaName, segments.getFirst(), null);
				top.file = file;
				return top;
			});
		}
		for (int index = nested; index < segments.size(); index++) {
			Node parent = current;
			String segment = segments.get(index);
			current = parent.children.computeIfAbsent(JavaText.javaIdentifier(segment),
					name -> new Node(name, segment, parent));
		}
		return current;
	}

	/**
	 * The namespaces of {@code id}, {@code config} and {@code var}: each field of their class, typed by a class of its
	 * own, {@code id.NPC} with {@code ---@field [string] integer} and {@code ---@field FromID fun(...)}, or by a table,
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
			TypeRef element = null;
			TypeRef fromId = null;
			ApiModel.ClassDef namespaceClass = field.type() instanceof TypeRef.Named named
					? model.classes.get(named.name())
					: null;
			if (field.type() instanceof TypeRef.Table table) {
				for (TypeRef.Entry entry : table.entries()) {
					if (entry.keyType() != null) {
						element = entry.value();
					} else if (entry.name().equals("FromID")) {
						fromId = entry.value();
					}
				}
			} else if (namespaceClass != null) {
				for (ApiModel.FieldDef member : namespaceClass.fields) {
					if (member.name().equals("[string]")) {
						element = member.type();
					} else if (member.name().equals("FromID")) {
						fromId = member.type();
					}
				}
			}
			if (element == null) {
				continue;
			}
			Node node = new Node(JavaText.javaIdentifier(field.name()), field.name(), holder);
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
	 * rest are left out with one warning for the whole table, because a large table can leave out thousands of names,
	 * which a build log should not list.
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
					: namespace.startsWith("config.") ? "; look them up with FromID and their numeric IDs instead"
					: "";
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
				return target.packageName() + "." + node.binaryName();
			}
		}
		warnings.add("The names of type " + type.show() + " have no binding class; they are left out.");
		return null;
	}

	// ---- Writing classes

	private Shape shape(ApiModel.ClassDef classDef) {
		if (!hasTable(classDef)) {
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
				.anyMatch(function -> function.style() == ApiModel.CallStyle.DOT && isBound(classDef, function));
		return hasTableFunctions ? Shape.INSTANCE : Shape.TYPE;
	}

	private void writeNode(StringBuilder out, Node node, String indent) {
		ApiModel.EnumDef enumDef = node.enumDef;
		ApiModel.ClassDef classDef = node.classDef;
		EventInfo payload = node.payloadOf;
		if (enumDef != null && hooks.containsKey(enumDef.name())) {
			constantTables.writeHooks(out, node, enumDef, hooks.get(enumDef.name()), indent);
		} else if (enumDef != null) {
			constantTables.writeEnum(out, node, enumDef, indent);
		} else if (node.constants != null) {
			constantTables.writeConstantNamespace(out, node, indent);
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
		if (target.isRs3()) {
			JavaText.javadoc(out, indent,
					List.of("The Lua table {@code " + node.luaName() + "}, which holds the classes nested here."));
			out.append(indent).append("@LuaGlobal(\"").append(node.luaName()).append("\")\n");
		} else {
			// LuaCATS names a library's types freely, a.b.Name with no table a.b in Lua
			JavaText.javadoc(out, indent, List.of("Holds the types named {@code " + node.luaName()
					+ ".}..., for which Lua has no table."));
		}
		out.append(indent).append("public ").append(node.parent == null ? "" : "static ").append("final class ")
				.append(node.javaName).append(" {\n\n");
		privateConstructor(out, node, indent + "\t");
		writeChildren(out, node, indent + "\t");
		out.append(indent).append("}\n");
	}

	void writeChildren(StringBuilder out, Node node, String indent) {
		for (Node child : node.children.values()) {
			out.append("\n");
			writeNode(out, child, indent);
		}
	}

	static void privateConstructor(StringBuilder out, Node node, String indent) {
		out.append(indent).append("private ").append(node.javaName).append("() {\n")
				.append(indent).append("\tthrow LuaOnly.error();\n")
				.append(indent).append("}\n");
	}

	private void writeSynthesizedPayload(StringBuilder out, Node node, EventInfo event, String indent) {
		JavaText.javadoc(out, indent, List.of("The {@code " + event.name() + "} event passes no payload: a"
				+ " {@code @Subscribe} method takes this empty class, and its Lua function takes no parameter."));
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
			permissions.put("Event." + event.name(), permission);
			out.append(indent).append("@LuaPermission(\"").append(permission).append("\")\n");
		}
		String flag = overrides.value("manifest-flag", "Event." + event.name());
		if (flag != null) {
			out.append(indent).append("@LuaManifestFlag(\"").append(flag).append("\")\n");
		}
		reviewAnnotation(out, "Event." + event.name(), indent);
	}

	private void writeClass(StringBuilder out, Node node, ApiModel.ClassDef classDef, String indent) {
		Shape shape = shape(classDef);
		String luaName = luaPath(classDef.name);
		String path = tablePath(classDef);
		EventInfo payload = node.payloadOf;
		List<String> doc = new ArrayList<>(JavaText.lines(classDef.description));
		doc.addAll(notes(classDef.name));
		boolean hasNew = classDef.functions.stream()
				.anyMatch(function -> isConstructor(classDef, function) && isBound(classDef, function));
		// What the RS3 API hands out and takes, a library or the plugin's Lua does
		String provider = target.isRs3() ? "the API" : target.provider();
		doc.add(switch (shape) {
			case NAMESPACE -> "<p>Lua: " + tableDescription(node, path) + ".";
			case INSTANCE -> "<p>Lua: " + tableDescription(node, path) + (hasNew
					? ", whose {@code new} makes its objects."
					: "; its objects come from " + provider + ".");
			case TYPE -> "<p>Lua: values of the type {@code " + luaName + "}" + (isBuiltByPlugin(node, classDef)
					? ": tables the plugin builds and passes to " + provider + "."
					: isSharedByBuilt(node)
							? ": the fields its subclasses share, whose tables the plugin builds and passes to "
									+ provider + "."
							: ", which " + target.provider() + " hands out.");
		});
		JavaText.javadoc(out, indent, doc);
		if (payload != null) {
			eventAnnotations(out, payload, indent);
		}
		memberAnnotations(out, classDef.name, indent);
		if (node.module != null) {
			out.append(indent).append("@LuaModule(").append(JavaText.javaString(node.module)).append(")\n");
		}
		boolean global = node == dependencyGlobal();
		if (global && target.optional()) {
			out.append(indent).append("@LuaOptional\n");
		}
		if (shape != Shape.TYPE && moduleTables.contains(node) && !path.equals(luaName) && !isImplicit(classDef)) {
			// A module's table whose class has a name of its own, which Lua Language Server annotations use
			out.append(indent).append("@LuaType(\"").append(luaName).append("\")\n");
		}
		out.append(indent).append(shape == Shape.TYPE ? "@LuaType(\"" : "@LuaGlobal(\"")
				.append(shape == Shape.TYPE ? luaName : path).append("\")\n");
		String superclass = superclass(classDef, shape);
		boolean isFinal = shape == Shape.NAMESPACE || payload != null;
		// The type variables of a class of objects; a namespace's functions are static, which cannot name them
		String variables = shape == Shape.NAMESPACE || classDef.typeParams.isEmpty()
				? ""
				: "<" + MemberBindings.typeVariables(classDef.typeParams) + ">";
		out.append(indent).append("public ").append(node.parent == null ? "" : "static ")
				.append(isFinal ? "final " : "").append("class ").append(node.javaName).append(variables)
				.append(superclass == null ? "" : " extends " + superclass).append(" {\n\n");
		String inner = indent + "\t";
		Map<String, Inherited> inherited = inheritedMethods(classDef);
		Set<String> signatures = new HashSet<>();
		constructors(out, node, classDef, shape, inner, signatures);
		if (global) {
			dependencyMembers(out, inner);
		}
		boolean isStatic = shape == Shape.NAMESPACE;
		for (ApiModel.FieldDef field : classDef.fields) {
			if (global && isDependencyMember(field.name())) {
				warnings.add(target.library() + "." + field.name() + " has the name of a member the plugin's class of"
						+ " the global has; it is left out.");
			} else if (target.isRs3() && luaName.equals("Event")) {
				memberBindings.eventField(out, field, inner);
			} else if (!overrides.has("skip", classDef.name + "." + field.name())) {
				memberBindings.field(out, classDef, field, isStatic, inner, signatures);
			}
		}
		for (ApiModel.FunctionDef function : classDef.functions) {
			if (global && isDependencyMember(function.name())) {
				warnings.add(target.library() + "." + function.name() + " has the name of a member the plugin's class"
						+ " of the global has; it is left out.");
			} else if (!(shape == Shape.INSTANCE && isConstructor(classDef, function))
					&& !isLeftToSubclasses(classDef, function)) {
				memberBindings.method(out, classDef, function, shape, inner, signatures, inherited);
			}
		}
		operators(out, classDef, inner, signatures);
		writeChildren(out, node, inner);
		out.append(indent).append("}\n");
	}

	/**
	 * The members the class of a library plugin's global has for the plugin, which the library has not: its alias, a
	 * constant to compare with the identifier an {@code OptionalDependencyChanged} payload names it by, and for an
	 * optional dependency, {@code isLoaded()}, the check that code reaching the global needs.
	 */
	private void dependencyMembers(StringBuilder out, String indent) {
		String alias = target.library();
		out.append("\n");
		JavaText.javadoc(out, indent, List.of("The alias the build gives the dependency, which is the global it is to"
				+ " the plugin, and which an {@code OptionalDependencyChanged} payload names it by.", "",
				"<p>Lua: {@code \"" + alias + "\"}, the alias itself"));
		out.append(indent).append("public static final String ALIAS = ").append(JavaText.javaString(alias))
				.append(";\n");
		if (target.optional()) {
			out.append("\n");
			JavaText.javadoc(out, indent, List.of("Whether the library is loaded: the player has it and has it turned"
					+ " on. Code reaches the global only where this check reaches it: inside {@code if (" + alias
					+ ".isLoaded())}, or after {@code if (!" + alias + ".isLoaded()) return;} in the same method.", "",
					"<p>Lua: {@code if " + alias + " then}, or elsewhere {@code " + alias + " ~= nil}"));
			out.append(indent).append("public static native boolean isLoaded();\n");
		}
	}

	/** Whether a name is one of the members the class of a library plugin's global has for the plugin. */
	private boolean isDependencyMember(String name) {
		return name.equals("ALIAS") || (target.optional() && name.equals("isLoaded"));
	}

	/**
	 * Lua's operators on the class's objects, as methods marked {@code @LuaOperator}: {@code a.plus(b)} is
	 * {@code a + b} in Lua. They come from the definitions' {@code ---@operator} lines, named as Kotlin names the
	 * operator functions, and from {@code operator <Class.name> <op> <fun(...) type>} lines for the definitions that
	 * do not declare them. The compilers lower {@code +}, {@code -}, {@code *} and {@code /}, so another operator is
	 * left out with a warning.
	 */
	private void operators(StringBuilder out, ApiModel.ClassDef owner, String indent, Set<String> signatures) {
		for (ApiModel.OperatorDef operator : owner.operators) {
			String[] lowered = LOWERED_OPERATORS.get(operator.kind());
			TypeRef operand = operator.operand();
			if (lowered == null || operand == null) {
				warnings.add(owner.name + ": the operator " + operator.kind()
						+ " is left out, as the compilers lower only +, -, * and /.");
				continue;
			}
			// The operand is the other object, or a number that scales this one
			boolean scalar = operand instanceof TypeRef.Named named && named.name().equals("number");
			writeOperator(out, owner, indent, signatures, lowered[0], lowered[1],
					new TypeRef.Param(scalar ? "scale" : "other", operand), operator.result());
		}
		for (Overrides.Entry entry : overrides.all("operator")) {
			int dot = entry.target().lastIndexOf('.');
			if (dot < 0 || !entry.target().substring(0, dot).equals(owner.name)) {
				continue;
			}
			TypeRef type = entry.arguments().size() < 2 ? null
					: TypeParser.parse(String.join(" ", entry.arguments().subList(1, entry.arguments().size())));
			if (!(type instanceof TypeRef.Function function) || function.params().size() != 1
					|| function.returns().size() != 1) {
				warnings.add(overrides.source() + ":" + entry.line() + ": operator " + entry.target()
						+ " needs an operator and a fun(other): result type.");
				continue;
			}
			overrides.applied(entry, false);
			writeOperator(out, owner, indent, signatures, entry.arguments().getFirst(),
					entry.target().substring(dot + 1), function.params().getFirst(), function.returns().getFirst());
		}
	}

	/** One operator's method, unless the class declares one of its name and parameter type already. */
	private void writeOperator(StringBuilder out, ApiModel.ClassDef owner, String indent, Set<String> signatures,
			String operator, String javaName, TypeRef.Param param, TypeRef result) {
		JType paramType = typeMapping.javaType(param.type());
		if (!signatures.add(javaName + "(" + paramType.erased() + ")")) {
			return;
		}
		String receiver = memberBindings.receiver(owner, false);
		String escaped = JavaText.escape(operator);
		out.append("\n");
		JavaText.javadoc(out, indent, List.of(
				"Lua's {@code " + escaped + "} on this object and the other.",
				"",
				"<p>Lua: {@code " + receiver + " " + escaped + " " + param.name() + "}"));
		out.append(indent).append("@LuaOperator(").append(JavaText.javaString(operator)).append(")\n");
		out.append(indent).append("public native ").append(typeMapping.javaType(result).text()).append(" ")
				.append(javaName).append("(").append(paramType.text()).append(" ").append(param.name()).append(");\n");
	}

	/**
	 * Whether a function is the class's constructor: its table's {@code new}, unless that gives an object of another
	 * class, as {@code Confirm.new} gives a {@code Dialog}, which makes it a function of the table, {@code create}.
	 */
	private boolean isConstructor(ApiModel.ClassDef owner, ApiModel.FunctionDef function) {
		if (!function.name().equals("new") || function.style() != ApiModel.CallStyle.DOT) {
			return false;
		}
		if (function.returns().isEmpty() || !(function.returns().getFirst().type().withoutNil()
				instanceof TypeRef.Named named)) {
			return true;
		}
		String returned = model.resolveAlias(named.name());
		return returned.equals(owner.name) || !model.classes.containsKey(returned);
	}

	/**
	 * Whether a method that takes the class's own type is left to the subclasses, because each declares it for its own
	 * type: {@code Vector:Dot(other : Vector)} means a vector of the caller's size, which only {@code Vector3:Dot(other
	 * : Vector3)} and the others say in Java. Bound on the base class too, it would accept a vector of another size.
	 */
	private boolean isLeftToSubclasses(ApiModel.ClassDef classDef, ApiModel.FunctionDef function) {
		if (!takesType(function, classDef.name)) {
			return false;
		}
		List<ApiModel.ClassDef> subclasses = model.classes.values().stream()
				.filter(other -> other.superclass != null && model.resolveAlias(other.superclass).equals(classDef.name))
				.toList();
		return !subclasses.isEmpty() && subclasses.stream().allMatch(subclass -> subclass.functions.stream()
				.anyMatch(own -> own.name().equals(function.name()) && takesType(own, subclass.name)));
	}

	/** Whether one of the function's parameters has the type of the named class. */
	private boolean takesType(ApiModel.FunctionDef function, String className) {
		return function.params().stream().anyMatch(param -> param.type().withoutNil() instanceof TypeRef.Named named
				&& model.resolveAlias(named.name()).equals(className));
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
				if (!isConstructor(classDef, function) || !isBound(classDef, function)) {
					continue;
				}
				TypeMapping.Scope previous =
						typeMapping.scope(new TypeMapping.Scope(Set.copyOf(classDef.typeParams), classDef));
				List<Form> forms;
				try {
					forms = memberBindings.forms(classDef.name, function);
				} finally {
					typeMapping.scope(previous);
				}
				for (Form form : forms) {
					if (!signatures.add("<init>(" + MemberBindings.erased(form.params()) + ")")) {
						continue;
					}
					publicNoArguments |= form.params().isEmpty() && !function.varargs();
					StringBuilder constructor = new StringBuilder();
					List<String> doc = new ArrayList<>(JavaText.lines(function.description()));
					doc.addAll(notes(classDef.name + ".new"));
					doc.add("<p>Lua: {@code " + tablePath(classDef) + ".new("
							+ MemberBindings.luaArguments(form.params()) + ")}");
					MemberBindings.paramDocs(doc, form.params());
					JavaText.javadoc(constructor, indent, doc);
					memberAnnotations(constructor, classDef.name + ".new", indent);
					constructor.append(indent).append("public ").append(node.javaName).append("(")
							.append(MemberBindings.parameterList(form.params(), function.varargs())).append(") {\n")
							.append(indent).append("\tthrow LuaOnly.error();\n")
							.append(indent).append("}\n");
					written.add(constructor.toString());
				}
			}
		}
		if (shape == Shape.TYPE && isBuiltByPlugin(node, classDef)) {
			StringBuilder constructor = new StringBuilder();
			JavaText.javadoc(constructor, indent, List.of(
					"An empty table, for the plugin to fill in and pass to the API.", "", "<p>Lua: {@code {}}"));
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

	/** Whether the overrides leave a function of the class unbound, with a {@code skip} line. */
	private boolean isBound(ApiModel.ClassDef classDef, ApiModel.FunctionDef function) {
		return !overrides.has("skip", classDef.name + "." + function.name());
	}

	/**
	 * The paragraphs the {@code note} lines add to the Javadoc of a class or member: what the client does that its
	 * description does not say.
	 */
	List<String> notes(String target) {
		return overrides.notes(target).stream().map(note -> "<p>In the client: " + JavaText.escape(note)).toList();
	}

	/**
	 * The annotations the overrides give a member or class: the permission it needs, the area moderation reviews it in,
	 * and whether it is dev-only.
	 */
	void memberAnnotations(StringBuilder out, String target, String indent) {
		String permission = overrides.value("permission", target);
		if (permission != null) {
			permissions.put(luaPath(target), permission);
			out.append(indent).append("@LuaPermission(\"").append(permission).append("\")\n");
		}
		reviewAnnotation(out, target, indent);
		if (overrides.has("dev-only", target)) {
			devOnly.add(luaPath(target));
			out.append(indent).append("@LuaDevOnly\n");
		}
	}

	private void reviewAnnotation(StringBuilder out, String target, String indent) {
		String area = overrides.value("review", target);
		if (area != null) {
			out.append(indent).append("@LuaReview(\"").append(area).append("\")\n");
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
			warnings.add(classDef.name + " extends " + superName
					+ ", which has no class of objects; its Java class extends Object.");
			return null;
		}
		return qualified(node);
	}

	/**
	 * Whether a value type is a data table the plugin builds, with a {@code {}} constructor: not a payload, and not a
	 * class the stubs give a global table, whose objects the client makes.
	 */
	boolean isBuiltByPlugin(Node node, ApiModel.ClassDef classDef) {
		return node.payloadOf == null && !hasTable(classDef) && isConstructible(classDef);
	}

	private boolean isConstructible(ApiModel.ClassDef classDef) {
		// A local table, local InvType = {}, describes objects the client makes
		if (overrides.has("not-constructible", classDef.name) || classDef.binding == ApiModel.Binding.LOCAL) {
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
				if (superShape == Shape.INSTANCE && isConstructor(superDef, function)) {
					continue;
				}
				boolean isStatic = superShape == Shape.INSTANCE && function.style() == ApiModel.CallStyle.DOT;
				inherit(found, superDef, function, isStatic);
			}
			// The methods its fields of function types stand for, as a highlight's activate
			for (ApiModel.FieldDef field : superDef.fields) {
				ApiModel.FunctionDef asFunction =
						memberBindings.fieldFunction(superDef, field, superShape == Shape.NAMESPACE);
				if (asFunction != null) {
					inherit(found, superDef, asFunction, superShape == Shape.NAMESPACE);
				}
			}
			superName = superDef.superclass;
		}
		return found;
	}

	private void inherit(Map<String, Inherited> found, ApiModel.ClassDef superDef, ApiModel.FunctionDef function,
			boolean isStatic) {
		String javaName = memberBindings.javaMethodName(superDef.name, function.name());
		TypeMapping.Scope previous = typeMapping.scope(
				new TypeMapping.Scope(isStatic ? Set.of() : Set.copyOf(superDef.typeParams), superDef));
		try {
			for (Form form : memberBindings.forms(superDef.name, function)) {
				found.putIfAbsent(javaName + "(" + MemberBindings.erased(form.params()) + ")",
						new Inherited(form.returned().erased(), isStatic));
			}
		} finally {
			typeMapping.scope(previous);
		}
	}

	/** Whether one binding class extends another, directly or further up, by their Java references. */
	boolean isSubclass(String subclass, String superclass) {
		for (Node candidate : byLuaName.values()) {
			if (qualified(candidate).equals(subclass)) {
				return superclasses(candidate).stream().anyMatch(node -> qualified(node).equals(superclass));
			}
		}
		return false;
	}

	/**
	 * Whether a data table the plugin builds extends the class, which then holds the fields those tables share, such
	 * as a library's options that every component's options extend.
	 */
	private boolean isSharedByBuilt(Node node) {
		for (Node other : byLuaName.values()) {
			ApiModel.ClassDef otherDef = other.classDef;
			if (otherDef != null && isBuiltByPlugin(other, otherDef) && superclasses(other).contains(node)) {
				return true;
			}
		}
		return false;
	}

	/** The classes a class extends, the nearest first, as far up as the bindings have them. */
	private List<Node> superclasses(Node node) {
		List<Node> found = new ArrayList<>();
		Node current = node;
		while (current.classDef != null) {
			String superName = current.classDef.superclass;
			Node next = superName == null ? null : byLuaName.get(model.resolveAlias(superName));
			if (next == null || next == node || found.contains(next)) {
				break;
			}
			found.add(next);
			current = next;
		}
		return found;
	}
}
