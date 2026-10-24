package org.adelheid.oneiro.bindings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.lang.model.SourceVersion;
import org.adelheid.oneiro.api.LuaConstants;
import org.jspecify.annotations.Nullable;

/**
 * Writes the Java sources of the RS3 bindings from the stubs' model and the overrides: one class per global table,
 * with nested classes for nested tables, so that {@code ui.Text} in Lua is {@code ui.Text} in Java. Each class and
 * member carries the annotations that tell Oneiro its Lua shape, and the Javadoc of the RS3 documentation the stubs
 * were generated from. The names of the {@code id}, {@code config} and {@code var} namespaces are not written as
 * sources: they are returned for {@link ConstantChunks}, and the namespace classes extend them.
 *
 * <p>This class has the model corrected by {@link ModelCorrections}, arranges the classes in their tree, and writes
 * each class's declaration and constructors. {@link MemberBindings} writes the members, {@link ConstantTables} the
 * tables of constants, {@link TypeMapping} chooses the Java types, and {@link SourceFiles} writes each file's header
 * and the files that come from no table.
 */
final class JavaBindings {

	/** The package of the generated bindings. */
	static final String PACKAGE = "org.adelheid.oneiro.rs3";
	/** How many names each chunk class of a constant namespace holds. */
	static final int CHUNK_SIZE = 20_000;

	private static final String NULLABLE = "@Nullable";

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

	final ApiModel model;
	private final Map<String, CsvConstants.Namespace> csvs;
	final Overrides overrides;
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
	final Map<String, EventInfo> events = new LinkedHashMap<>();
	private final List<ConstantNamespace> constants = new ArrayList<>();
	final Set<String> writtenRecords = new HashSet<>();
	/** The classes the stubs lack that {@code field} lines declare, with the first line of each. */
	final Map<String, Overrides.Entry> declaredClasses = new LinkedHashMap<>();
	/** The hook lines, by the Lua name of the table of hooks they describe, {@code ui.Hook}, in order. */
	final Map<String, List<HookLine>> hooks = new LinkedHashMap<>();

	JavaBindings(Stubs stubs, Overrides overrides, List<String> warnings) {
		this.model = stubs.model();
		this.csvs = stubs.namespaces();
		this.overrides = overrides;
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
				warnings.add("overrides.txt:" + entry.line() + ": field " + entry.target() + " declares the class "
						+ name + ", which the stubs lack and no type line uses; check its name.");
			}
		});
		collectParameterTypes();
		collectEvents();
		buildTree();
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

	/** The constant namespaces whose chunk classes the namespace classes extend. */
	List<ConstantNamespace> constants() {
		return constants;
	}

	/** Every source file, by its path under the source root. */
	Map<String, String> sources() {
		Map<String, String> files = new TreeMap<>();
		String folder = PACKAGE.replace('.', '/') + "/";
		files.put(folder + "package-info.java", SourceFiles.packageInfo());
		files.put(folder + "LuaOnly.java", SourceFiles.luaOnly());
		files.put(folder + "EventSubscription.java", sourceFiles.subscription(false));
		files.put(folder + "EventPredicateSubscription.java", sourceFiles.subscription(true));
		files.put(folder + "Globals.java", sourceFiles.globals());
		Set<String> lowerCaseNames = new HashSet<>();
		for (String fixed : List.of("LuaOnly", "EventSubscription", "EventPredicateSubscription", "Globals")) {
			lowerCaseNames.add(fixed.toLowerCase(Locale.ROOT));
		}
		for (Node top : tops.values()) {
			if (!lowerCaseNames.add(top.javaName.toLowerCase(Locale.ROOT))) {
				throw new IllegalStateException("Two binding classes differ only in case, which some file systems"
						+ " cannot hold apart, or one takes the name of a fixed class: " + top.javaName);
			}
			StringBuilder source = new StringBuilder();
			writeNode(source, top, "");
			files.put(folder + top.javaName + ".java",
					SourceFiles.file(source.toString(), SourceFiles.nestedNames(top)));
		}
		return files;
	}

	// ---- Preparation

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
			warnings.add("overrides.txt:" + entry.line() + ": element-table " + entry.target()
					+ " needs a class of the stubs, a type name and the element names.");
			return;
		}
		// Stubs that have all the elements as the table's fields describe the table themselves, so the class is left as
		// they give it. Stubs that have only some of them changed in a way this line cannot follow.
		List<String> elementNames = entry.arguments().subList(1, entry.arguments().size());
		Set<String> fieldNames = new HashSet<>();
		classDef.fields.forEach(field -> fieldNames.add(field.name()));
		List<String> named = elementNames.stream().filter(fieldNames::contains).toList();
		if (!named.isEmpty()) {
			if (named.size() == elementNames.size()) {
				overrides.applied(entry, true);
			} else {
				warnings.add("overrides.txt:" + entry.line() + ": element-table " + entry.target()
						+ " is left as the stubs give it, which name some of its elements as fields ("
						+ String.join(", ", named) + ") but not all; check the line against the stubs.");
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

	private Node node(String luaName) {
		List<String> segments = List.of(luaName.split("\\.", -1));
		Node current = tops.computeIfAbsent(javaTopName(segments.getFirst()),
				name -> new Node(name, segments.getFirst(), null));
		for (int index = 1; index < segments.size(); index++) {
			Node parent = current;
			String segment = segments.get(index);
			current = parent.children.computeIfAbsent(JavaText.javaIdentifier(segment),
					name -> new Node(name, segment, parent));
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
			constantTables.writeHooks(out, node, enumDef, hooks.get(enumDef.name()), indent);
		} else if (enumDef != null) {
			constantTables.writeEnum(out, node, enumDef, indent);
		} else if (node.constants != null) {
			constantTables.writeConstantNamespace(out, node, indent);
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
		JavaText.javadoc(out, indent,
				List.of("The Lua table {@code " + node.luaName() + "}, which holds the classes nested here."));
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
		JavaText.javadoc(out, indent, List.of("The Lua table {@code " + node.luaName() + "}: one {@code "
				+ elementType.javaName + "} object per name."));
		out.append(indent).append("@LuaGlobal(\"").append(node.luaName()).append("\")\n");
		out.append(indent).append("public ").append(node.parent == null ? "" : "static ").append("final class ")
				.append(node.javaName).append(" {\n\n");
		String inner = indent + "\t";
		privateConstructor(out, node, inner);
		for (String name : names) {
			out.append("\n");
			JavaText.javadoc(out, inner, List.of("<p>Lua: {@code " + node.luaName() + "." + name + "}"));
			out.append(inner).append("public static final ").append(elementType.reference()).append(" ")
					.append(JavaText.javaIdentifier(name)).append(" = LuaOnly.value();\n");
		}
		writeChildren(out, node, inner);
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
		List<String> doc = new ArrayList<>(JavaText.lines(classDef.description));
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
		JavaText.javadoc(out, indent, doc);
		if (payload != null) {
			eventAnnotations(out, payload, indent);
		}
		memberAnnotations(out, classDef.name, indent);
		out.append(indent).append(shape == Shape.TYPE ? "@LuaType(\"" : "@LuaGlobal(\"").append(luaName)
				.append("\")\n");
		String superclass = superclass(classDef, shape);
		boolean isFinal = shape == Shape.NAMESPACE || payload != null;
		out.append(indent).append("public ").append(node.parent == null ? "" : "static ")
				.append(isFinal ? "final " : "").append("class ").append(node.javaName)
				.append(superclass == null ? "" : " extends " + superclass).append(" {\n\n");
		String inner = indent + "\t";
		Map<String, Inherited> inherited = inheritedMethods(classDef);
		Set<String> signatures = new HashSet<>();
		constructors(out, node, classDef, shape, inner, signatures);
		boolean isStatic = shape == Shape.NAMESPACE;
		for (ApiModel.FieldDef field : classDef.fields) {
			if (luaName.equals("Event")) {
				memberBindings.eventField(out, field, inner);
			} else if (!overrides.has("skip", classDef.name + "." + field.name())) {
				memberBindings.field(out, classDef, field, isStatic, inner, signatures);
			}
		}
		for (ApiModel.FunctionDef function : classDef.functions) {
			if (!(shape == Shape.INSTANCE && isConstructor(function)) && !isLeftToSubclasses(classDef, function)) {
				memberBindings.method(out, classDef, function, shape, inner, signatures, inherited);
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
				warnings.add("overrides.txt:" + entry.line() + ": operator " + entry.target()
						+ " needs an operator and a fun(other): result type.");
				continue;
			}
			overrides.applied(entry, false);
			String operator = entry.arguments().getFirst();
			String javaName = entry.target().substring(dot + 1);
			TypeRef.Param param = function.params().getFirst();
			JType paramType = typeMapping.javaType(param.type());
			if (!signatures.add(javaName + "(" + paramType.erased() + ")")) {
				continue;
			}
			String receiver = MemberBindings.receiver(owner, false);
			String escaped = JavaText.escape(operator);
			out.append("\n");
			JavaText.javadoc(out, indent, List.of(
					"Lua's {@code " + escaped + "} on this object and the other.",
					"",
					"<p>Lua: {@code " + receiver + " " + escaped + " " + param.name() + "}"));
			out.append(indent).append("@LuaOperator(").append(JavaText.javaString(operator)).append(")\n");
			out.append(indent).append("public native ")
					.append(typeMapping.javaType(function.returns().getFirst()).text()).append(" ").append(javaName)
					.append("(").append(paramType.text()).append(" ").append(param.name()).append(");\n");
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
				if (!isConstructor(function)) {
					continue;
				}
				for (Form form : memberBindings.forms(classDef.name, function)) {
					if (!signatures.add("<init>(" + MemberBindings.erased(form.params()) + ")")) {
						continue;
					}
					publicNoArguments |= form.params().isEmpty() && !function.varargs();
					StringBuilder constructor = new StringBuilder();
					List<String> doc = new ArrayList<>(JavaText.lines(function.description()));
					doc.add("<p>Lua: {@code " + luaPath(classDef.name) + ".new("
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

	/** The annotations the overrides give a member or class: the permission it needs, and whether it is dev-only. */
	void memberAnnotations(StringBuilder out, String target, String indent) {
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
			warnings.add(classDef.name + " extends " + superName
					+ ", which has no class of objects; its Java class extends Object.");
			return null;
		}
		return node.reference();
	}

	/**
	 * Whether a value type is a data table the plugin builds, with a {@code {}} constructor: not a payload, and not a
	 * class the stubs give a global table, whose objects the client makes.
	 */
	boolean isBuiltByPlugin(Node node, ApiModel.ClassDef classDef) {
		return node.payloadOf == null && classDef.binding != ApiModel.Binding.GLOBAL && isConstructible(classDef);
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
				String javaName = memberBindings.javaMethodName(superDef.name, function.name());
				for (Form form : memberBindings.forms(superDef.name, function)) {
					found.putIfAbsent(javaName + "(" + MemberBindings.erased(form.params()) + ")",
							new Inherited(form.returned().erased(), isStatic));
				}
			}
			superName = superDef.superclass;
		}
		return found;
	}

	/** Whether one binding class extends another, directly or further up, by their Java references. */
	boolean isSubclass(String subclass, String superclass) {
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
}
