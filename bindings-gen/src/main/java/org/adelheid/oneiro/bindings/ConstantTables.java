package org.adelheid.oneiro.bindings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

	void writeEnum(StringBuilder out, Node node, ApiModel.EnumDef enumDef, String indent) {
		// A Java enum lowers to the table's own field, whatever its value, so an enum whose values are unknown is one
		boolean asJavaEnum = bindings.typeUses.getOrDefault(enumDef.name(), 0) > 0 || enumDef.placeholderValued();
		List<String> doc = new ArrayList<>(JavaText.lines(enumDef.description()));
		doc.add(asJavaEnum
				? "<p>Lua: the table {@code " + enumDef.name() + "}, whose values the client compares with {@code ==}."
				: "<p>Lua: the table {@code " + enumDef.name() + "}; its values are plain "
						+ (enumDef.integerValued() ? "integers, such as bit flags." : "strings."));
		JavaText.javadoc(out, indent, doc);
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
				valueDoc.add("<p>Lua: {@code " + enumDef.name() + "." + luaKey + "}" + shown
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
				valueDoc.add("<p>Lua: {@code " + enumDef.name() + "." + luaKey + "}");
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
	 * callbacks as functional interfaces: {@code Subscribe(ui.Hook<C, ?> hook, C callback)} then checks a lambda's
	 * parameters against its hook. The callbacks return nothing, as most Lua ones do; {@code hook.stoppable()} types
	 * the same hook for a callback that returns whether the listeners after it run, which Lua's may. A hook without a
	 * {@code hook} line takes a callback of the component alone.
	 */
	void writeHooks(StringBuilder out, Node node, ApiModel.EnumDef enumDef, List<HookLine> lines,
			String indent) {
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
			List<HookLine> forms = lines.stream().filter(line -> line.luaName().equals(luaKey)).toList();
			if (forms.isEmpty()) {
				warnings.add(enumDef.name() + "." + luaKey + " has no hook line; its callback takes the"
						+ " component alone.");
				forms = List.of(new HookLine(luaKey, luaKey, "Notify", List.of()));
			}
			for (HookLine form : forms) {
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
		JavaText.javadoc(out, inner, List.of("This hook, for a callback that returns whether the listeners after it"
				+ " run: false stops them.", "", "<p>Lua: the hook itself, whose function returns the boolean"));
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
			JavaText.javadoc(out, inner + "\t", List.of("The same callback, returning whether the listeners after"
					+ " it run.", "", "<p>Lua: {@code " + lua + "}, which returns the boolean"));
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

	/**
	 * The parameters of a form as the hook table types them: a hook parameter becomes {@code ui.Hook<C, ?>} and the
	 * callback beside it {@code C}, so that the callback must be the one the hook takes; a hook without a callback, as
	 * {@code Unsubscribe(hook)} takes, becomes {@code ui.Hook<?, ?>}. Other forms are returned as they are.
	 */
	List<JParam> hookTyped(List<JParam> params) {
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
			List<JParam> typed = new ArrayList<>(params);
			JParam hookParam = params.get(hook);
			typed.set(hook,
					new JParam(hookParam.name(), JType.reference(hookType + (callback < 0 ? "<?, ?>" : "<C, ?>")),
					hookParam.description()));
			if (callback >= 0) {
				JParam callbackParam = params.get(callback);
				typed.set(callback,
						new JParam(callbackParam.name(), JType.reference("C"), callbackParam.description()));
			}
			return typed;
		}
		return params;
	}

	void writeConstantNamespace(StringBuilder out, Node node, String indent) {
		ConstantNamespace namespace = node.constants;
		if (namespace == null) {
			return;
		}
		String file = namespace.luaPath().substring(namespace.luaPath().indexOf('.') + 1) + ".csv";
		List<String> doc = new ArrayList<>();
		doc.add(namespace.rows().isEmpty()
				? "{@code " + namespace.luaPath() + "}: the stubs list no names for it, so only {@code FromID} is"
						+ " bound."
				: "{@code " + namespace.luaPath() + "}: " + String.format(Locale.ROOT, "%,d", namespace.rows().size())
						+ " names, from the stubs' {@code csvs/" + file + "}.");
		doc.add("<p>Java: {@code " + LuaConstants.PREFIX + "NAME}; Lua: {@code " + namespace.luaPath() + ".NAME}");
		JavaText.javadoc(out, indent, doc);
		out.append(indent).append("@LuaGlobal(\"").append(namespace.luaPath()).append("\")\n");
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
					? JType.VOID : typeMapping.javaType(function.returns().getFirst());
			JavaText.javadoc(out, inner, List.of("The entry with the given ID.", "",
					"<p>Lua: {@code " + namespace.luaPath() + ".FromID(" + MemberBindings.luaArguments(params) + ")}"));
			out.append(inner).append("public static native ").append(returned.text()).append(" FromID(")
					.append(MemberBindings.parameterList(params, false)).append(");\n");
		}
		TypeRef byName = node.byName;
		if (byName != null) {
			// A name known only at run time: an unknown one reads -1 from an id table and nil from the others
			JType named = typeMapping.javaType(byName);
			boolean isId = namespace.luaPath().startsWith("id.");
			out.append("\n");
			JavaText.javadoc(out, inner, List.of("The entry with the given name, for a name known only at run time: "
					+ (isId ? "-1" : "null") + " when there is none.", "",
					"<p>Lua: {@code " + namespace.luaPath() + "[name]}"));
			out.append(inner).append("@LuaIndex\n");
			out.append(inner).append("public static native ").append(isId ? named.text() : named.nullable().text())
					.append(" get(String name);\n");
		}
		out.append(indent).append("}\n");
	}
}
