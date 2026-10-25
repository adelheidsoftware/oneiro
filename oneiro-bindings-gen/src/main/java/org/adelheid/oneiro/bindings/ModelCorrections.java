package org.adelheid.oneiro.bindings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.adelheid.oneiro.bindings.JavaBindings.HookLine;

/**
 * The overrides file's corrections to the stubs' model, applied before any binding is written: members the client
 * names differently, the values of enums, field, return and parameter types, members and forms the stubs lack or
 * get wrong, superclasses, and the callbacks of UI hooks. Each records whether the stubs have caught up with it.
 */
final class ModelCorrections {

	private final JavaBindings bindings;
	private final ApiModel model;
	private final Overrides overrides;
	private final List<String> warnings;

	ModelCorrections(JavaBindings bindings) {
		this.bindings = bindings;
		this.model = bindings.model;
		this.overrides = bindings.overrides;
		this.warnings = bindings.warnings;
	}

	/**
	 * Applies every correction, renames first, since the other lines name members by their new names, and checks that
	 * each {@code operator} line names a class of the stubs.
	 */
	void apply() {
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
				warnings.add("overrides.txt:" + entry.line() + ": operator " + entry.target()
						+ " needs a class of the stubs.");
			}
		}
	}

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
				warnings.add("overrides.txt:" + entry.line() + ": rename " + entry.target()
						+ " needs a class of the stubs and one new name.");
				continue;
			}
			String oldName = entry.target().substring(dot + 1);
			String newName = entry.arguments().getFirst();
			boolean renamed = false;
			for (int index = 0; index < owner.fields.size(); index++) {
				ApiModel.FieldDef field = owner.fields.get(index);
				if (field.name().equals(oldName)) {
					owner.fields.set(index, field.withName(newName));
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
				warnings.add("overrides.txt:" + entry.line() + ": rename " + entry.target()
						+ " matches no field and no function, under either name.");
			}
		}
	}

	/**
	 * {@code values <enum> <NAME=literal...>}: the values the client gives an enum's names, where the stubs give
	 * others, such as each name as a string. Stubs that give the same values have caught up with the line.
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
				warnings.add("overrides.txt:" + entry.line() + ": values " + entry.target()
						+ " needs an enum of the stubs and NAME=value arguments.");
				continue;
			}
			List<ApiModel.EnumValue> values = new ArrayList<>();
			boolean agrees = true;
			for (ApiModel.EnumValue value : enumDef.values()) {
				String literal = given.remove(value.key());
				if (literal == null) {
					values.add(value);
				} else {
					agrees &= literal.equals(value.literal());
					values.add(new ApiModel.EnumValue(value.name(), literal, value.comment()));
				}
			}
			if (!given.isEmpty()) {
				warnings.add("overrides.txt:" + entry.line() + ": values " + entry.target()
						+ " names values the stubs lack: " + String.join(", ", given.keySet()) + ".");
			}
			overrides.applied(entry, agrees);
			model.enums.put(enumDef.name(), enumDef.withValues(values));
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
						owner.fields.set(index, field.withType(type));
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
	 * {@code field <Owner.name> <LuaCATS type>}: a field the docs give and the stubs lack. Stubs that have the field
	 * with that type have caught up with the line; with another type, the line's type stands. A class the stubs lack is
	 * declared by its fields, as a shape the docs describe and a {@code type} line uses, such as the keyed table of an
	 * achievement's tests.
	 */
	private void applyFieldOverrides() {
		for (Overrides.Entry entry : overrides.all("field")) {
			int dot = entry.target().lastIndexOf('.');
			if (dot < 0 || entry.arguments().isEmpty()) {
				warnings.add("overrides.txt:" + entry.line() + ": field " + entry.target()
						+ " needs a class and a type.");
				continue;
			}
			String ownerName = entry.target().substring(0, dot);
			ApiModel.ClassDef owner = model.classes.get(ownerName);
			if (owner == null) {
				owner = model.classNamed(ownerName);
				bindings.declaredClasses.put(ownerName, entry);
			}
			String name = entry.target().substring(dot + 1);
			TypeRef type = TypeParser.parse(String.join(" ", entry.arguments()));
			boolean present = false;
			for (int index = 0; index < owner.fields.size(); index++) {
				ApiModel.FieldDef field = owner.fields.get(index);
				if (field.name().equals(name)) {
					overrides.applied(entry, field.type().equals(type));
					owner.fields.set(index, field.withType(type));
					present = true;
				}
			}
			if (!present) {
				overrides.applied(entry, false);
				owner.fields.add(new ApiModel.FieldDef(name, type, "", false, false));
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
				warnings.add("overrides.txt:" + entry.line() + ": function " + target
						+ " needs a class of the stubs and a fun(...) type.");
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
			ApiModel.CallStyle style = target.charAt(separator) == ':'
					? ApiModel.CallStyle.COLON
					: ApiModel.CallStyle.DOT;
			ApiModel.FunctionDef added =
					ApiModel.FunctionDef.of(owner.name, name, style, function, "", "overrides.txt");
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
				List<String> typeWords = entry.arguments().subList(1, entry.arguments().size());
				TypeRef type = TypeParser.parse(String.join(" ", typeWords));
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
				warnings.add("overrides.txt:" + entry.line() + ": param " + entry.target() + " "
						+ String.join(" ", entry.arguments()) + " matches no parameter of a function of the stubs.");
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
			boolean known = enumDef != null && enumDef.values().stream().anyMatch(value -> value.key().equals(luaName));
			if (!known || entry.arguments().isEmpty()) {
				warnings.add("overrides.txt:" + entry.line() + ": hook " + target
						+ " needs a value of a table of hooks in the stubs and a callback name.");
				continue;
			}
			List<TypeRef.Param> params = new ArrayList<>();
			for (String argument : entry.arguments().subList(1, entry.arguments().size())) {
				int colon = argument.indexOf(':');
				params.add(new TypeRef.Param(argument.substring(0, colon),
						TypeParser.parse(argument.substring(colon + 1))));
			}
			String callback = entry.arguments().getFirst();
			List<TypeRef.Param> earlier = callbacks.putIfAbsent(callback, params);
			if (earlier != null && !types(earlier).equals(types(params))) {
				warnings.add("overrides.txt:" + entry.line() + ": hook " + target + " gives the callback " + callback
						+ " other parameter types than an earlier line; the earlier ones are kept.");
				params = earlier;
			}
			overrides.applied(entry, false);
			bindings.hooks.computeIfAbsent(table, _ -> new ArrayList<>())
					.add(new HookLine(javaName, luaName, callback, params));
		}
	}

	private static List<TypeRef> types(List<TypeRef.Param> params) {
		return params.stream().map(TypeRef.Param::type).toList();
	}

	/** {@code superclass <class> <superclass>}: the class the docs say a class extends, where the stubs give none. */
	private void applySuperclassOverrides() {
		for (Overrides.Entry entry : overrides.all("superclass")) {
			ApiModel.ClassDef classDef = model.classes.get(entry.target());
			String superName = entry.arguments().isEmpty() ? null : entry.arguments().getFirst();
			if (classDef == null || superName == null || !model.classes.containsKey(superName)) {
				warnings.add("overrides.txt:" + entry.line() + ": superclass " + entry.target()
						+ " needs two classes of the stubs.");
				continue;
			}
			overrides.applied(entry, superName.equals(classDef.superclass));
			classDef.superclass = superName;
		}
	}
}
