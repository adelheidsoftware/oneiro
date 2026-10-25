package org.adelheid.oneiro.bindings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.adelheid.oneiro.api.LuaConstants;
import org.adelheid.oneiro.bindings.JavaBindings.ConstantNamespace;
import org.adelheid.oneiro.bindings.JavaBindings.HookLine;
import org.adelheid.oneiro.bindings.JavaBindings.JParam;
import org.adelheid.oneiro.bindings.JavaBindings.JType;
import org.adelheid.oneiro.bindings.JavaBindings.Node;

/**
 * The bindings of the client's tables of constants: an enum table as a Java enum, the hook table as constants typed
 * by the callbacks they take, and each namespace of the {@code id}, {@code config} and {@code var} tables as a
 * class over the chunk classes that hold its names.
 */
final class ConstantTables {

	private final JavaBindings bindings;
	private final Overrides overrides;
	private final List<String> warnings;
	private final TypeMapping typeMapping;

	ConstantTables(JavaBindings bindings) {
		this.bindings = bindings;
		this.overrides = bindings.overrides;
		this.warnings = bindings.warnings;
		this.typeMapping = bindings.typeMapping();
	}

	/**
	 * An enum table: a Java enum when plugins use it as a type or its values are unknown, and otherwise a class of
	 * plain constants.
	 */
	void writeEnum(StringBuilder out, Node node, ApiModel.EnumDef enumDef, String indent) {
		String table = bindings.enumPath(node, enumDef);
		boolean rs3 = bindings.target.isRs3();
		// A Java enum lowers to the table's own field, whatever its value, so an enum whose values are unknown is one.
		// A (key) enum's values are its keys, which plugins write as strings, so its table holds plain constants.
		boolean asJavaEnum = !enumDef.keys()
				&& (bindings.typeUses.getOrDefault(enumDef.name(), 0) > 0 || enumDef.placeholderValued());
		List<String> doc = new ArrayList<>(JavaText.lines(enumDef.description()));
		doc.add(asJavaEnum
				? "<p>Lua: the table {@code " + table + "}, whose values " + (rs3 ? "the client" : "the library")
						+ " compares with {@code ==}."
				: "<p>Lua: the table {@code " + table + "}; its values are plain "
						+ (enumDef.integerValued() ? "integers, such as bit flags." : "strings."));
		if (enumDef.keys() && !enumDef.values().isEmpty()) {
			String example = JavaText.escape(enumDef.values().getFirst().key());
			doc.add("<p>A value of the type {@code " + enumDef.name() + "} is one of the table's keys, as a string,"
					+ " such as {@code \"" + example + "\"}.");
		}
		JavaText.javadoc(out, indent, doc);
		out.append(indent).append("@LuaGlobal(\"").append(table).append("\")\n");
		String inner = indent + "\t";
		Set<String> javaNames = new HashSet<>();
		if (asJavaEnum) {
			// The client gives all but a few enum tables FromID, which turns a value into its name
			if (rs3 && !overrides.has("no-from-id", enumDef.name())) {
				out.append(indent).append("@LuaFromId\n");
			}
			out.append(indent).append("public enum ").append(node.javaName).append(" {\n");
			List<String> constantsWritten = new ArrayList<>();
			List<String> aliases = new ArrayList<>();
			Map<String, String> firstWithValue = new HashMap<>();
			for (ApiModel.EnumValue value : enumDef.values()) {
				String luaKey = value.key();
				String javaName = JavaText.javaIdentifier(luaKey);
				if (!javaNames.add(javaName)) {
					warnings.add(enumDef.name() + "." + luaKey
							+ " has the Java name of another value; it is left out.");
					continue;
				}
				StringBuilder constant = new StringBuilder("\n");
				List<String> valueDoc = new ArrayList<>(JavaText.lines(value.comment()));
				String first = firstWithValue.putIfAbsent(value.literal(), javaName);
				String shown = enumDef.placeholderValued() ? "" : ", " + JavaText.escape(value.literal());
				valueDoc.add("<p>Lua: {@code " + table + "." + luaKey + "}" + shown
						+ (first == null ? "." : ", the value of {@link #" + first + "}."));
				JavaText.javadoc(constant, inner, valueDoc);
				if (!javaName.equals(luaKey)) {
					constant.append(inner).append("@LuaName(").append(JavaText.javaString(luaKey)).append(")\n");
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
			JavaBindings.privateConstructor(out, node, inner);
			for (ApiModel.EnumValue value : enumDef.values()) {
				String luaKey = value.key();
				String javaName = JavaText.javaIdentifier(luaKey);
				if (!javaNames.add(javaName)) {
					warnings.add(enumDef.name() + "." + luaKey
							+ " has the Java name of another value; it is left out.");
					continue;
				}
				out.append("\n");
				List<String> valueDoc = new ArrayList<>(JavaText.lines(value.comment()));
				valueDoc.add("<p>Lua: {@code " + table + "." + luaKey + "}");
				JavaText.javadoc(out, inner, valueDoc);
				if (!javaName.equals(luaKey)) {
					out.append(inner).append("@LuaName(").append(JavaText.javaString(luaKey)).append(")\n");
				}
				String literal = value.literal();
				String type = literal.startsWith("\"") ? "String" : literal.contains(".") ? "double" : "int";
				out.append(inner).append("public static final ").append(type).append(" ").append(javaName).append(" = ")
						.append(literal).append(";\n");
			}
		}
		bindings.writeChildren(out, node, inner);
		out.append(indent).append("}\n");
	}

	/**
	 * A table of hooks, {@code ui.Hook}, as a class of constants typed by the callback each hook takes, with those
	 * callbacks as functional interfaces, against which a member that takes a hook checks a lambda's parameters (see
	 * {@link #hookTyped}). The callbacks return nothing, as most Lua ones do; {@code hook.stoppable()} types the same
	 * hook for a callback that returns whether the listeners after it run, which Lua's may. A hook without a
	 * {@code hook} line takes a callback of the component alone.
	 */
	void writeHooks(StringBuilder out, Node node, ApiModel.EnumDef enumDef, List<HookLine> lines, String indent) {
		String hook = node.reference();
		List<String> doc = new ArrayList<>(JavaText.lines(enumDef.description()));
		doc.add("<p>Lua: the table {@code " + enumDef.name() + "}. Each constant names the callback its hook takes,"
				+ " which receives the component first.");
		doc.add("");
		doc.add("@param <C> the callback the hook takes");
		doc.add("@param <S> the same callback returning whether the listeners after it run");
		JavaText.javadoc(out, indent, doc);
		out.append(indent).append("@LuaGlobal(\"").append(enumDef.name()).append("\")\n");
		out.append(indent).append("public ").append(node.parent == null ? "" : "static ").append("final class ")
				.append(node.javaName).append("<C, S> {\n\n");
		String inner = indent + "\t";
		JavaBindings.privateConstructor(out, node, inner);
		Map<String, List<TypeRef.Param>> callbacks = new LinkedHashMap<>();
		Map<String, List<String>> firedBy = new LinkedHashMap<>();
		for (ApiModel.EnumValue value : enumDef.values()) {
			String luaKey = value.key();
			if (lines.stream().noneMatch(line -> line.luaName().equals(luaKey))) {
				warnings.add(enumDef.name() + "." + luaKey + " has no hook line; its callback takes the"
						+ " component alone.");
			}
			for (HookLine form : linesOf(lines, luaKey)) {
				callbacks.putIfAbsent(form.callback(), form.params());
				firedBy.computeIfAbsent(form.callback(), _ -> new ArrayList<>()).add(form.javaName());
				out.append("\n");
				List<String> valueDoc = new ArrayList<>(JavaText.lines(value.comment()));
				valueDoc.add("<p>Lua: {@code " + enumDef.name() + "." + luaKey + "}; its callback: {@link "
						+ form.callback() + "}.");
				JavaText.javadoc(out, inner, valueDoc);
				if (!form.javaName().equals(luaKey)) {
					out.append(inner).append("@LuaName(").append(JavaText.javaString(luaKey)).append(")\n");
				}
				String callback = hook + "." + form.callback();
				out.append(inner).append("public static final ").append(hook).append("<").append(callback).append(", ")
						.append(callback).append(".Stopping> ").append(JavaText.javaIdentifier(form.javaName()))
						.append(" = LuaOnly.value();\n");
			}
		}
		out.append("\n");
		JavaText.javadoc(out, inner, List.of(
				"This hook, for a callback that returns whether the listeners after it run: false stops them.",
				"",
				"<p>Lua: the hook itself, whose function returns the boolean"));
		out.append(inner).append("@LuaSame\n");
		out.append(inner).append("public native ").append(hook).append("<S, S> stoppable();\n");
		Node component = bindings.byLuaName.get("ui.Component");
		String self = component == null ? "Object" : component.reference();
		callbacks.forEach((name, params) -> {
			List<JParam> javaParams = new ArrayList<>();
			javaParams.add(new JParam("self", JType.reference(self), "the component the hook fired on"));
			for (TypeRef.Param param : params) {
				javaParams.add(new JParam(MemberBindings.parameterName(param.name(), javaParams),
						typeMapping.javaType(param.type()), ""));
			}
			String lua = "function(" + MemberBindings.luaArguments(javaParams) + ")";
			out.append("\n");
			JavaText.javadoc(out, inner, List.of("The callback of " + String.join(", ", firedBy.get(name)) + ".", "",
					"<p>Lua: {@code " + lua + "}"));
			out.append(inner).append("@FunctionalInterface\n");
			out.append(inner).append("public interface ").append(name).append(" {\n\n");
			out.append(inner).append("\tvoid call(").append(MemberBindings.parameterList(javaParams, false))
					.append(");\n\n");
			JavaText.javadoc(out, inner + "\t", List.of(
					"The same callback, returning whether the listeners after it run.",
					"",
					"<p>Lua: {@code " + lua + "}, which returns the boolean"));
			out.append(inner).append("\t@FunctionalInterface\n");
			out.append(inner).append("\tinterface Stopping {\n");
			out.append(inner).append("\t\tboolean call(").append(MemberBindings.parameterList(javaParams, false))
					.append(");\n");
			out.append(inner).append("\t}\n");
			out.append(inner).append("}\n");
		});
		bindings.writeChildren(out, node, inner);
		out.append(indent).append("}\n");
	}

	/** The lines of one hook, or for a hook without a line, one whose callback takes the component alone. */
	private static List<HookLine> linesOf(List<HookLine> lines, String luaKey) {
		List<HookLine> forms = lines.stream().filter(line -> line.luaName().equals(luaKey)).toList();
		return forms.isEmpty() ? List.of(new HookLine(luaKey, luaKey, "Notify", List.of())) : forms;
	}

	/** The callbacks a table's hooks take, in the order the table first names them. */
	private static List<String> callbacks(ApiModel.EnumDef enumDef, List<HookLine> lines) {
		return enumDef.values().stream()
				.flatMap(value -> linesOf(lines, value.key()).stream())
				.map(HookLine::callback)
				.distinct()
				.toList();
	}

	/**
	 * The forms of a member's parameters as the hook table types them, so that the callback must be the one the hook
	 * takes. For Java, a hook parameter beside a callback becomes {@code ui.Hook<C, ?>} and the callback {@code C}; for
	 * Kotlin, there is a form for each callback and its stopping kind, {@code ui.Hook<ui.Hook.Pointer, ?>} with a
	 * {@code ui.Hook.Pointer} (see {@link BindingsGenerator.Language}). A hook without a callback, as
	 * {@code Unsubscribe(hook)} takes, becomes {@code ui.Hook<?, ?>}. Other forms are returned as they are.
	 */
	List<List<JParam>> hookTyped(List<JParam> params) {
		for (String table : bindings.hooks.keySet()) {
			Node node = bindings.byLuaName.get(table);
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
			if (callback < 0) {
				return List.of(typedHook(params, hook, "?", -1));
			}
			if (bindings.language == BindingsGenerator.Language.JAVA) {
				return List.of(typedHook(params, hook, "C", callback));
			}
			List<List<JParam>> forms = new ArrayList<>();
			for (String name : callbacks(Objects.requireNonNull(node.enumDef), bindings.hooks.get(table))) {
				forms.add(typedHook(params, hook, hookType + "." + name, callback));
				forms.add(typedHook(params, hook, hookType + "." + name + ".Stopping", callback));
			}
			return forms;
		}
		return List.of(params);
	}

	/**
	 * The parameters with the hook's typed as {@code ui.Hook<callback, ?>}, and the callback's, when there is one, as
	 * that callback.
	 */
	private static List<JParam> typedHook(List<JParam> params, int hook, String callbackType, int callback) {
		List<JParam> typed = new ArrayList<>(params);
		JParam hookParam = params.get(hook);
		String hookType = hookParam.type().erased();
		typed.set(hook, new JParam(hookParam.name(), JType.reference(hookType + "<" + callbackType + ", ?>"),
				hookParam.description()));
		if (callback >= 0) {
			JParam callbackParam = params.get(callback);
			typed.set(callback,
					new JParam(callbackParam.name(), JType.reference(callbackType), callbackParam.description()));
		}
		return typed;
	}

	/**
	 * A namespace of the {@code id}, {@code config} or {@code var} tables: a class that extends the last of its chunk
	 * classes, with {@code FromID} and a lookup by name where the stubs give them.
	 */
	void writeConstantNamespace(StringBuilder out, Node node, String indent) {
		ConstantNamespace namespace = node.constants;
		if (namespace == null) {
			return;
		}
		String luaPath = namespace.luaPath();
		String file = luaPath.substring(luaPath.indexOf('.') + 1) + ".csv";
		List<String> doc = new ArrayList<>();
		if (namespace.rows().isEmpty()) {
			doc.add("{@code " + luaPath + "}: the stubs list no names for it, so only {@code FromID} is bound.");
		} else {
			String count = String.format(Locale.ROOT, "%,d", namespace.rows().size());
			doc.add("{@code " + luaPath + "}: " + count + " names, from the stubs' {@code csvs/" + file + "}.");
		}
		doc.add("<p>Java: {@code " + LuaConstants.PREFIX + "NAME}; Lua: {@code " + luaPath + ".NAME}");
		JavaText.javadoc(out, indent, doc);
		out.append(indent).append("@LuaGlobal(\"").append(luaPath).append("\")\n");
		out.append(indent).append("public static final class ").append(node.javaName);
		if (!namespace.rows().isEmpty()) {
			out.append(" extends ").append(namespace.chunkPrefix()).append(namespace.chunkCount());
		}
		out.append(" {\n\n");
		String inner = indent + "\t";
		JavaBindings.privateConstructor(out, node, inner);
		if (node.fromId instanceof TypeRef.Function function) {
			out.append("\n");
			List<JParam> params = new ArrayList<>();
			for (TypeRef.Param param : function.params()) {
				params.add(new JParam(MemberBindings.parameterName(param.name(), params),
						typeMapping.javaType(param.type()), ""));
			}
			JType returned = function.returns().isEmpty()
					? JType.VOID
					: typeMapping.javaType(function.returns().getFirst());
			JavaText.javadoc(out, inner, List.of("The entry with the given ID.", "",
					"<p>Lua: {@code " + luaPath + ".FromID(" + MemberBindings.luaArguments(params) + ")}"));
			out.append(inner).append("public static native ").append(returned.text()).append(" FromID(")
					.append(MemberBindings.parameterList(params, false)).append(");\n");
		}
		TypeRef byName = node.byName;
		if (byName != null) {
			// A name known only at run time: an unknown one reads -1 from an id table and nil from the others
			JType named = typeMapping.javaType(byName);
			boolean isId = luaPath.startsWith("id.");
			out.append("\n");
			JavaText.javadoc(out, inner, List.of(
					"The entry with the given name, for a name known only at run time: "
							+ (isId ? "-1" : "null") + " when there is none.",
					"",
					"<p>Lua: {@code " + luaPath + "[name]}"));
			out.append(inner).append("@LuaIndex\n");
			out.append(inner).append("public static native ").append(isId ? named.text() : named.nullable().text())
					.append(" get(String name);\n");
		}
		out.append(indent).append("}\n");
	}
}
