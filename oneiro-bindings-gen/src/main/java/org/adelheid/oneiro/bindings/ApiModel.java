package org.adelheid.oneiro.bindings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Everything a set of LuaCATS files declares, in the shape the generator needs: classes with fields and functions (and
 * how each function is called), enums, aliases, global functions, the modules the files export, and the lines the
 * reader did not understand. Classes are merged by name, because several files repeat {@code ---@class ui} and add to
 * it.
 */
final class ApiModel {

	/** How a class's table is bound in Lua, which decides between static members, instances and singletons. */
	enum Binding {
		/** {@code Player = {}} or {@code ui.Text = {}}: a global table the plugin can reach. */
		GLOBAL,
		/** {@code local NPCEntity = {}}: the table exists only in the stub; the type describes client objects. */
		LOCAL,
		/** No table at all: a payload, settings or data shape. */
		NONE,
	}

	/** {@code A.B()} versus {@code A:B()}. */
	enum CallStyle {
		DOT,
		COLON,
	}

	/** A {@code ---@class}, merged across files. */
	static final class ClassDef {
		final String name;
		final List<String> typeParams = new ArrayList<>();
		@Nullable String superclass;
		String description = "";
		Binding binding = Binding.NONE;
		final List<FieldDef> fields = new ArrayList<>();
		final List<FunctionDef> functions = new ArrayList<>();
		final List<OperatorDef> operators = new ArrayList<>();
		/**
		 * The tables the class's own table holds, by field name, each by the class or enum it is: the modules a module
		 * requires into its fields, as {@code zyklib.reactive}, and the classes and enums declared in its fields, as
		 * {@code disposable.DisposableGroup}.
		 */
		final Map<String, String> tables = new LinkedHashMap<>();
		final List<String> files = new ArrayList<>();

		ClassDef(String name) {
			this.name = name;
		}
	}

	/**
	 * A {@code ---@field}, or a field that code assigns; {@code readOnly} comes from the "(Read-only)" the docs put at
	 * the start of the description, and {@code writable} from their "(Writeable)", which a field of a type whose fields
	 * are read-only, as an event payload's, can be.
	 */
	record FieldDef(String name, TypeRef type, String description, boolean readOnly, boolean writable,
			boolean deprecated) {

		FieldDef withName(String newName) {
			return new FieldDef(newName, type, description, readOnly, writable, deprecated);
		}

		FieldDef withType(TypeRef newType) {
			return new FieldDef(name, newType, description, readOnly, writable, deprecated);
		}
	}

	/**
	 * A {@code ---@operator}: one of Lua's operators on a class's objects, by LuaCATS's name for it, {@code add}, with
	 * the other operand's type, null for a unary operator, and the result's.
	 */
	record OperatorDef(String kind, @Nullable TypeRef operand, TypeRef result) {
	}

	/** A {@code ---@param}. An optional parameter has a nullable type or a name ending in {@code ?}. */
	record ParamDef(String name, TypeRef type, String description, boolean optionalName) {

		boolean optional() {
			return optionalName || type.isNullable();
		}
	}

	/** A {@code ---@return}. */
	record ReturnDef(TypeRef type, String name, String description) {
	}

	/**
	 * A {@code function Owner.Name(...)} or {@code function Owner:Name(...)} with its annotations. {@code owner} is the
	 * resolved class name, or the raw table expression when it matched no class; empty for a global function.
	 * {@code typeParams} are the names its {@code ---@generic} declares.
	 */
	record FunctionDef(
			String owner,
			String name,
			CallStyle style,
			List<String> luaParams,
			List<ParamDef> params,
			List<ReturnDef> returns,
			List<TypeRef> overloads,
			boolean varargs,
			String description,
			String file,
			List<String> typeParams,
			boolean deprecated) {

		FunctionDef {
			luaParams = List.copyOf(luaParams);
			params = List.copyOf(params);
			returns = List.copyOf(returns);
			overloads = List.copyOf(overloads);
			typeParams = List.copyOf(typeParams);
		}

		/**
		 * The function a {@code fun(...)} type describes, for a function the stubs give only as a type. Its {@code ...}
		 * parameter makes it a varargs function rather than a parameter of its own.
		 */
		static FunctionDef of(String owner, String name, CallStyle style, TypeRef.Function function,
				String description, String file) {
			List<TypeRef.Param> params = function.params().stream()
					.filter(param -> !param.name().equals("..."))
					.toList();
			return new FunctionDef(owner, name, style,
					function.params().stream().map(TypeRef.Param::name).toList(),
					params.stream().map(param -> new ParamDef(param.name(), param.type(), "", false)).toList(),
					function.returns().stream().map(returned -> new ReturnDef(returned, "", "")).toList(),
					List.of(), params.size() < function.params().size(), description, file, List.of(), false);
		}

		FunctionDef withOwner(String newOwner) {
			return new FunctionDef(newOwner, name, style, luaParams, params, returns, overloads, varargs, description,
					file, typeParams, deprecated);
		}

		FunctionDef withReturns(List<ReturnDef> newReturns) {
			return new FunctionDef(owner, name, style, luaParams, params, newReturns, overloads, varargs, description,
					file, typeParams, deprecated);
		}

		FunctionDef withName(String newName) {
			return new FunctionDef(owner, newName, style, luaParams, params, returns, overloads, varargs, description,
					file, typeParams, deprecated);
		}

		FunctionDef withOverloads(List<TypeRef> newOverloads) {
			return new FunctionDef(owner, name, style, luaParams, params, returns, newOverloads, varargs, description,
					file, typeParams, deprecated);
		}

		FunctionDef withParams(List<ParamDef> newParams) {
			return new FunctionDef(owner, name, style, luaParams, newParams, returns, overloads, varargs, description,
					file, typeParams, deprecated);
		}
	}

	/** One value of a {@code ---@enum} table. {@code literal} is the Lua source, such as {@code 0} or {@code "INT"}. */
	record EnumValue(String name, String literal, String comment) {

		/** The key as Lua indexes it: {@code NAME}, or the string inside {@code ["..."]}. */
		String key() {
			return name.startsWith("[\"") ? name.substring(2, name.length() - 2) : name;
		}
	}

	/**
	 * A {@code ---@enum}. With {@code keys}, from {@code ---@enum (key)}, a value of the enum's type is one of its
	 * keys, {@code "HEAD"}, rather than what a key holds. With {@code fromId}, its table has a {@code FromID} function,
	 * which turns a value into its name, as the client gives most of its enum tables.
	 */
	record EnumDef(String name, String description, List<EnumValue> values, String file, boolean keys,
			boolean fromId) {

		EnumDef {
			values = List.copyOf(values);
		}

		EnumDef withValues(List<EnumValue> newValues) {
			return new EnumDef(name, description, newValues, file, keys, fromId);
		}

		EnumDef withFromId() {
			return new EnumDef(name, description, values, file, keys, true);
		}

		/** Whether the values are numbers rather than strings. */
		boolean integerValued() {
			return values.stream().allMatch(value -> !value.literal().startsWith("\""));
		}

		/**
		 * Whether every value is its own name as a string, {@code NONE = "NONE"}: what the stubs write where the docs
		 * give no values, or give them in a column the stubs' generator does not read. Such a value is not the
		 * client's.
		 */
		boolean placeholderValued() {
			return !values.isEmpty()
					&& values.stream().allMatch(value -> value.literal().equals("\"" + value.key() + "\""));
		}
	}

	/** A {@code ---@alias}. */
	record AliasDef(String name, TypeRef target, String file) {
	}

	/**
	 * A module: a file that returns a table, which {@code require(path)} gives. {@code export} names the class of that
	 * table: the class the file declares for it, or, for a table it declares no class for, a class named by the path,
	 * {@code src/core/seeds}, which holds what the file defines on the table, and which is then {@code implicit}.
	 */
	record ModuleDef(String path, String export, String file, boolean implicit) {
	}

	/** A line the reader could not place, with a normalised pattern used to group similar lines. */
	record Unparsed(String pattern, String file, int line, String text) {
	}

	final Map<String, ClassDef> classes = new LinkedHashMap<>();
	final Map<String, EnumDef> enums = new LinkedHashMap<>();
	final Map<String, AliasDef> aliases = new LinkedHashMap<>();
	final List<FunctionDef> globalFunctions = new ArrayList<>();
	/** The modules by their {@code require} path. */
	final Map<String, ModuleDef> modules = new LinkedHashMap<>();
	final List<Unparsed> unparsed = new ArrayList<>();

	ClassDef classNamed(String name) {
		return classes.computeIfAbsent(name, ClassDef::new);
	}

	/** Every function, global or owned by a class. */
	List<FunctionDef> allFunctions() {
		List<FunctionDef> all = new ArrayList<>(globalFunctions);
		classes.values().forEach(classDef -> all.addAll(classDef.functions));
		return all;
	}

	/**
	 * How often each class or enum is used as a type in fields, parameters, returns and superclasses, with aliases
	 * resolved. The {@code self} parameter of an {@code ---@overload fun(self: X, ...)} is not a use: it only says the
	 * overload is a colon call.
	 */
	Map<String, Integer> typeUses() {
		Map<String, Integer> uses = new HashMap<>();
		for (ClassDef classDef : classes.values()) {
			classDef.fields.forEach(field -> countUses(field.type(), uses));
			String superclass = classDef.superclass;
			if (superclass != null) {
				uses.merge(resolveAlias(superclass), 1, Integer::sum);
			}
		}
		for (FunctionDef function : allFunctions()) {
			function.params().forEach(param -> countUses(param.type(), uses));
			function.returns().forEach(returned -> countUses(returned.type(), uses));
			function.overloads().forEach(overload -> countUses(overload, uses));
		}
		return uses;
	}

	/** Follows {@code ---@alias Text ui.Text} chains to the class, enum or built-in type they name. */
	String resolveAlias(String name) {
		String current = name;
		for (int guard = 0; guard < 10; guard++) {
			AliasDef alias = aliases.get(current);
			if (alias == null || !(alias.target() instanceof TypeRef.Named named) || !named.args().isEmpty()) {
				break;
			}
			current = named.name();
		}
		return current;
	}

	private void countUses(TypeRef type, Map<String, Integer> uses) {
		switch (type) {
			case TypeRef.Named named -> {
				uses.merge(resolveAlias(named.name()), 1, Integer::sum);
				named.args().forEach(arg -> countUses(arg, uses));
			}
			case TypeRef.Function function -> {
				function.params().stream()
						.filter(param -> !param.name().equals("self"))
						.forEach(param -> countUses(param.type(), uses));
				function.returns().forEach(returned -> countUses(returned, uses));
			}
			default -> type.children().forEach(child -> countUses(child, uses));
		}
	}
}
