package org.adelheid.oneiro.bindings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.SourceVersion;
import org.adelheid.oneiro.bindings.JavaBindings.EventInfo;
import org.adelheid.oneiro.bindings.JavaBindings.Form;
import org.adelheid.oneiro.bindings.JavaBindings.Inherited;
import org.adelheid.oneiro.bindings.JavaBindings.JParam;
import org.adelheid.oneiro.bindings.JavaBindings.JType;
import org.adelheid.oneiro.bindings.JavaBindings.Node;
import org.adelheid.oneiro.bindings.JavaBindings.Shape;
import org.adelheid.oneiro.bindings.JavaBindings.Variant;
import org.jspecify.annotations.Nullable;

/**
 * A class's fields and functions as Java fields and methods, each with the annotations that give its Lua shape
 * and the Javadoc of its description. A function becomes one method for each of its forms: its own, each
 * {@code ---@overload}, the shorter forms that leave optional parameters out, and one for each member of a union
 * parameter, up to a limit.
 */
final class MemberBindings {

	/** The most forms the union parameters of one function expand into; past it, a union parameter is an Object. */
	private static final int MAX_UNION_OVERLOADS = 8;
	/** Methods of {@code Object} that a binding cannot declare with the same parameters. */
	private static final Set<String> OBJECT_METHODS = Set.of("getClass()", "hashCode()", "equals(Object)", "toString()",
			"notify()", "notifyAll()", "wait()", "clone()", "finalize()");

	private final JavaBindings bindings;
	private final ApiModel model;
	private final Overrides overrides;
	private final List<String> warnings;
	private final TypeMapping typeMapping;

	MemberBindings(JavaBindings bindings) {
		this.bindings = bindings;
		this.model = bindings.model;
		this.overrides = bindings.overrides;
		this.warnings = bindings.warnings;
		this.typeMapping = bindings.typeMapping();
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
		Node node = bindings.byLuaName.get(owner.name);
		return node == null || !bindings.isBuiltByPlugin(node, owner);
	}

	/** One of {@code Event}'s fields, as a constant of its event's subscription type. */
	void eventField(StringBuilder out, ApiModel.FieldDef field, String indent) {
		EventInfo event = bindings.events.get(field.name());
		if (event == null) {
			return;
		}
		Node payload = bindings.byLuaName.get(event.payload());
		String payloadType = payload == null ? "Object" : payload.reference();
		String type = (event.predicate() ? "EventPredicateSubscription<" : "EventSubscription<") + payloadType + ">";
		out.append("\n");
		List<String> doc = new ArrayList<>(JavaText.lines(field.description()));
		doc.add("<p>Lua: {@code Event." + field.name() + ".Subscribe(name, callback)}");
		JavaText.javadoc(out, indent, doc);
		bindings.memberAnnotations(out, "Event." + field.name(), indent);
		out.append(indent).append("public static final ").append(type).append(" ").append(field.name())
				.append(" = LuaOnly.value();\n");
	}

	/**
	 * A field: the {@code get} method of a table indexed by key, a method for a function in a global table's field,
	 * or a Java field. {@code signatures} holds the methods the class already declares.
	 */
	void field(StringBuilder out, ApiModel.ClassDef owner, ApiModel.FieldDef field, boolean isStatic,
			String indent, Set<String> signatures) {
		TypeMapping.Scope previous = typeMapping.scope(
				new TypeMapping.Scope(isStatic ? Set.of() : Set.copyOf(owner.typeParams), owner));
		try {
			writeField(out, owner, field, isStatic, indent, signatures);
		} finally {
			typeMapping.scope(previous);
		}
	}

	private void writeField(StringBuilder out, ApiModel.ClassDef owner, ApiModel.FieldDef field, boolean isStatic,
			String indent, Set<String> signatures) {
		String target = owner.name + "." + field.name();
		TypeRef type = field.type();
		if (field.name().startsWith("[")) {
			// ---@field [Int] ui.StyleSheet: the table itself is indexed
			TypeRef keyType = TypeParser.parse(field.name().substring(1, field.name().length() - 1));
			JType key = typeMapping.javaType(keyType);
			if (!signatures.add("get(" + key.erased() + ")")) {
				return;
			}
			out.append("\n");
			JavaText.javadoc(out, indent,
					List.of("The entry under the key.", "", "<p>Lua: {@code " + receiver(owner, isStatic) + "[key]}"));
			out.append(indent).append("@LuaIndex\n");
			out.append(indent).append("public ").append(isStatic ? "static " : "").append("native ")
					.append(typeMapping.javaType(type).text()).append(" get(").append(key.text()).append(" key);\n");
			return;
		}
		ApiModel.FunctionDef asFunction = fieldFunction(owner, field, isStatic);
		if (asFunction != null) {
			method(out, owner, asFunction, isStatic ? Shape.NAMESPACE : Shape.TYPE, indent, signatures, Map.of());
			return;
		}
		JType javaType = nullability(target, type, typeMapping.javaType(type));
		// A library plugin's global is the plugin's copy of the library's table, which the library never sees changed
		boolean readOnly = isReadOnly(owner, field) || (isStatic && bindings.target.isDependency());
		boolean copied = !isStatic && isCopied(owner, field);
		out.append("\n");
		List<String> doc = new ArrayList<>(JavaText.lines(field.description()));
		doc.addAll(bindings.notes(target));
		String luaName = field.name();
		doc.add("<p>Lua: {@code " + receiver(owner, isStatic) + "." + luaName + "}" + (readOnly ? ", read-only" : "")
				+ (copied ? ", a copy: change a local copy and assign it back" : ""));
		JavaText.javadoc(out, indent, doc);
		bindings.memberAnnotations(out, target, indent);
		if (field.deprecated()) {
			out.append(indent).append("@Deprecated\n");
		}
		if (copied) {
			out.append(indent).append("@LuaCopy\n");
		}
		String javaName = overrides.value("java-name", target);
		if (javaName == null) {
			javaName = JavaText.javaIdentifier(luaName);
		}
		if (!javaName.equals(luaName)) {
			out.append(indent).append("@LuaName(").append(JavaText.javaString(luaName)).append(")\n");
		}
		out.append(indent).append("public ").append(isStatic ? "static " : "").append(readOnly ? "final " : "")
				.append(javaType.text()).append(" ").append(javaName)
				.append(isStatic && readOnly ? " = LuaOnly.value()" : "").append(";\n");
	}

	/**
	 * The function a field of a function type stands for, or null for any other field. A function in a table's field is
	 * a function of the table, {@code utils.CalcTextWidth(fontID, text)}; one that takes self first is a method,
	 * called with a colon, as a highlight's {@code activate} is, on the table or on its objects.
	 */
	ApiModel.@Nullable FunctionDef fieldFunction(ApiModel.ClassDef owner, ApiModel.FieldDef field, boolean isStatic) {
		if (!(field.type().withoutNil() instanceof TypeRef.Function function)) {
			return null;
		}
		boolean method = !function.params().isEmpty() && function.params().getFirst().name().equals("self");
		if (!isStatic && !method) {
			return null;
		}
		TypeRef.Function called = method
				? new TypeRef.Function(function.params().subList(1, function.params().size()), function.returns())
				: function;
		return ApiModel.FunctionDef.of(owner.name, field.name(),
				method ? ApiModel.CallStyle.COLON : ApiModel.CallStyle.DOT, called, field.description(), "");
	}

	/** The receiver a member's Lua form shows: the table for a static member, a name for the object otherwise. */
	String receiver(ApiModel.ClassDef owner, boolean isStatic) {
		return isStatic
				? bindings.tablePath(owner)
				: JavaText.lowerFirstWord(JavaText.simpleLuaName(owner.name));
	}

	/** A type with the nullability the overrides give its member. */
	private JType nullability(String target, TypeRef type, JType javaType) {
		if (overrides.corrects("nullable", target, type.isNullable()) && !type.isNullable()) {
			return javaType.nullable();
		}
		if (overrides.corrects("not-nullable", target, !type.isNullable()) && type.isNullable()) {
			return typeMapping.javaType(type.withoutNil());
		}
		return javaType;
	}

	/**
	 * Whether a field is read-only: the docs say so, or the overrides do for the field or its whole class, or it is an
	 * event payload's, whose few writable fields the docs mark, or the overrides name.
	 */
	private boolean isReadOnly(ApiModel.ClassDef owner, ApiModel.FieldDef field) {
		String target = owner.name + "." + field.name();
		if (field.writable() || overrides.has("writable", target)) {
			return false;
		}
		// Both overrides are asked even when the docs say so already, so that one the stubs catch up with is noticed
		boolean fieldOverride = overrides.corrects("read-only", target, field.readOnly());
		boolean classOverride = overrides.corrects("read-only", owner.name, field.readOnly());
		if (field.readOnly() || fieldOverride || classOverride) {
			return true;
		}
		Node node = bindings.byLuaName.get(owner.name);
		return node != null && node.payloadOf != null;
	}

	/** A function, as one method for each of its forms; {@code signatures} holds the methods the class declares. */
	void method(StringBuilder out, ApiModel.ClassDef owner, ApiModel.FunctionDef function, Shape shape,
			String indent, Set<String> signatures, Map<String, Inherited> inherited) {
		String target = owner.name + "." + function.name();
		if (overrides.has("skip", target)) {
			return;
		}
		boolean colon = function.style() == ApiModel.CallStyle.COLON;
		boolean isStatic = shape == Shape.NAMESPACE || (shape == Shape.INSTANCE && !colon);
		// An instance method names its class's type variables; a function's own ---@generic ones are the method's
		Set<String> scope = new LinkedHashSet<>(isStatic ? List.of() : owner.typeParams);
		List<String> own = function.typeParams().stream().filter(variable -> !scope.contains(variable)).toList();
		scope.addAll(own);
		TypeMapping.Scope previous = typeMapping.scope(new TypeMapping.Scope(scope, owner));
		try {
			writeMethod(out, owner, function, shape, indent, signatures, inherited, isStatic, own);
		} finally {
			typeMapping.scope(previous);
		}
	}

	private void writeMethod(StringBuilder out, ApiModel.ClassDef owner, ApiModel.FunctionDef function, Shape shape,
			String indent, Set<String> signatures, Map<String, Inherited> inherited, boolean isStatic,
			List<String> typeParams) {
		String target = owner.name + "." + function.name();
		boolean colon = function.style() == ApiModel.CallStyle.COLON;
		String javaName = javaMethodName(owner.name, function.name());
		if (function.params().isEmpty() && function.returns().isEmpty() && function.overloads().isEmpty()
				&& inherited.keySet().stream().anyMatch(key -> key.startsWith(javaName + "("))) {
			// An override without annotations, as HighlightGroup:activate() is: the inherited method, typed as the
			// parent class declares it, serves the calls, which Lua sends to this class's function
			return;
		}
		List<Form> forms = new ArrayList<>();
		for (Form untyped : forms(owner.name, function)) {
			for (List<JParam> params : bindings.constantTables().hookTyped(untyped.params())) {
				forms.add(new Form(params, untyped.returned()));
			}
		}
		for (Form form : forms) {
			boolean generic = bindings.target.isRs3()
					&& form.params().stream().anyMatch(param -> param.type().text().equals("C"));
			List<String> variables = new ArrayList<>(generic ? List.of("C") : List.of());
			if (!typeParams.isEmpty()) {
				variables.add(typeVariables(typeParams));
			}
			String key = javaName + "(" + erased(form.params()) + ")";
			if (OBJECT_METHODS.contains(key)) {
				warnings.add(target + " has the signature of Object's " + key + "; it is left out.");
				continue;
			}
			if (!signatures.add(key) || !overridable(target, form, isStatic, inherited.get(key))) {
				continue;
			}
			out.append("\n");
			List<String> doc = new ArrayList<>(JavaText.lines(function.description()));
			doc.addAll(bindings.notes(target));
			doc.add("<p>Lua: {@code " + receiver(owner, isStatic) + (colon ? ":" : ".") + function.name() + "("
					+ luaArguments(form.params(), function.varargs()) + ")}");
			paramDocs(doc, form.params());
			if (generic) {
				doc.add("@param <C> the callback the hook takes");
			}
			for (String variable : typeParams) {
				doc.add("@param <" + variable + "> a type the call decides");
			}
			if (!function.returns().isEmpty() && !form.returned().text().equals("void")) {
				String text = function.returns().getFirst().description();
				if (!text.isEmpty()) {
					doc.add("@return " + JavaText.escape(text));
				}
			}
			JavaText.javadoc(out, indent, doc);
			bindings.memberAnnotations(out, target, indent);
			if (function.deprecated()) {
				out.append(indent).append("@Deprecated\n");
			}
			if (shape == Shape.NAMESPACE && colon) {
				out.append(indent).append("@LuaColon\n");
			}
			if (!isStatic && !colon) {
				out.append(indent).append("@LuaDot\n");
			}
			if (!javaName.equals(function.name())) {
				out.append(indent).append("@LuaName(").append(JavaText.javaString(function.name())).append(")\n");
			}
			out.append(indent).append("public ").append(isStatic ? "static " : "").append("native ")
					.append(variables.isEmpty() ? "" : "<" + String.join(", ", variables) + "> ")
					.append(form.returned().text()).append(" ").append(javaName)
					.append("(").append(parameterList(form.params(), function.varargs())).append(");\n");
		}
		List<String> names = returnNames(owner.name, function);
		if (function.returns().size() > 1 && names != null && !names.isEmpty() && bindings.writtenRecords.add(target)) {
			out.append("\n");
			// The record is a class of its own, which no method's type variables reach
			TypeMapping.Scope previous = typeMapping.scope(TypeMapping.Scope.NONE);
			try {
				returnValues(out, owner, function, names, indent);
			} finally {
				typeMapping.scope(previous);
			}
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
		boolean fits = returned.equals(inherited.returned()) || bindings.isSubclass(returned, inherited.returned());
		if (inherited.isStatic() != isStatic || !fits) {
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
		doc.add("The values {@code " + bindings.tablePath(owner) + "." + function.name() + "} returns, in order.");
		List<String> components = new ArrayList<>();
		for (int index = 0; index < function.returns().size() && index + 1 < names.size(); index++) {
			ApiModel.ReturnDef returned = function.returns().get(index);
			String name = names.get(index + 1);
			doc.add("@param " + name
					+ (returned.description().isEmpty() ? "" : " " + JavaText.escape(returned.description())));
			components.add(typeMapping.javaType(returned.type()).text() + " " + name);
		}
		JavaText.javadoc(out, indent, doc);
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
			List<String> names = returnNames(owner, function);
			if (names != null && !names.isEmpty()) {
				Node node = bindings.byLuaName.get(owner);
				String ownerReference = node == null ? JavaBindings.luaPath(owner) : bindings.qualified(node);
				return JType.reference(ownerReference + "." + names.getFirst());
			}
			warnings.add(target + " returns " + returns.size() + " values, but " + (bindings.target.isPlugin()
					? "its ---@return tags do not name them all"
					: "neither its ---@return tags nor the overrides name them") + "; only the first is bound.");
		}
		TypeRef type = returns.getFirst().type();
		if (type instanceof TypeRef.Named named && named.name().equals("nil")) {
			return JType.VOID;
		}
		return nullability(target, type, typeMapping.javaType(type));
	}

	/**
	 * The record and component names of a function's several return values: those of the overrides' {@code returns}
	 * line, or, where every {@code ---@return} names its value, the function's name and {@code Result}, and those
	 * names; null for a function with one value, or with several that are not all named.
	 */
	private @Nullable List<String> returnNames(String owner, ApiModel.FunctionDef function) {
		List<String> stated = overrides.arguments("returns", owner + "." + function.name());
		if (stated != null || function.returns().size() < 2
				|| function.returns().stream().anyMatch(returned -> returned.name().isEmpty())) {
			return stated;
		}
		String functionName = JavaText.javaIdentifier(function.name());
		List<String> names = new ArrayList<>();
		names.add(Character.toUpperCase(functionName.charAt(0)) + functionName.substring(1) + "Result");
		function.returns().forEach(returned -> names.add(JavaText.javaIdentifier(returned.name())));
		return names;
	}

	/** A function's Java name: the overrides' {@code java-name}, {@code create} for {@code new}, or its Lua name. */
	String javaMethodName(String owner, String luaName) {
		String renamed = overrides.value("java-name", owner + "." + luaName);
		if (renamed != null) {
			return renamed;
		}
		return luaName.equals("new") ? "create" : JavaText.javaIdentifier(luaName);
	}

	/**
	 * The Java forms of a function: its own and each {@code ---@overload}, each with shorter forms that leave out its
	 * optional trailing parameters, and one form per member type of a union parameter. Forms Java cannot tell apart
	 * are written once, the first one kept.
	 */
	List<Form> forms(String owner, ApiModel.FunctionDef function) {
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
				JType returned = shape.returns().isEmpty()
						? declaredReturn
						: typeMapping.javaType(shape.returns().getFirst());
				variants.add(new Variant(params, returned));
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
	private List<Variant> lockstep(List<ApiModel.ParamDef> params, ApiModel.FunctionDef function,
			JType declaredReturn) {
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
				variantReturn = returned.isNullable()
						? typeMapping.javaType(option).nullable() : typeMapping.javaType(option);
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
			String description = JavaText.escape(param.description());
			List<List<JParam>> next = new ArrayList<>();
			for (List<JParam> partial : results) {
				for (TypeRef option : options) {
					List<JParam> extended = new ArrayList<>(partial);
					JType javaType = typeMapping.javaType(option);
					extended.add(new JParam(parameterName(param.name(), partial),
							nullable ? javaType.nullable() : javaType, description));
					next.add(extended);
				}
			}
			results = next;
		}
		return results;
	}

	/**
	 * Type variables as a class or method declares them, {@code T extends @Nullable Object}: any Lua value, nil among
	 * them, can be one, as in {@code Event<Payload, nil>}.
	 */
	static String typeVariables(List<String> names) {
		return names.stream().map(name -> name + " extends @Nullable Object").collect(Collectors.joining(", "));
	}

	static String erased(List<JParam> params) {
		return params.stream().map(param -> param.type().erased()).collect(Collectors.joining(","));
	}

	/** A Java name for a parameter, from its Lua name, unlike the names of the parameters before it. */
	static String parameterName(String luaName, List<JParam> before) {
		String base = luaName.isEmpty() ? "value" : luaName;
		if (!SourceVersion.isName(base)) {
			base = SourceVersion.isKeyword(base) ? base + "Value" : "value";
		}
		String candidate = base;
		Set<String> taken = before.stream().map(JParam::name).collect(Collectors.toSet());
		// args is the varargs parameter's name
		for (int suffix = 2; taken.contains(candidate) || candidate.equals("args"); suffix++) {
			candidate = base + suffix;
		}
		return candidate;
	}

	static String parameterList(List<JParam> params, boolean varargs) {
		List<String> written = new ArrayList<>();
		params.forEach(param -> written.add(param.type().text() + " " + param.name()));
		if (varargs) {
			// Lua's ... takes nil as readily as any other value
			written.add("@Nullable Object... args");
		}
		return String.join(", ", written);
	}

	static String luaArguments(List<JParam> params) {
		return params.stream().map(JParam::name).collect(Collectors.joining(", "));
	}

	/** The arguments as a function's Lua call shows them, with {@code ...} for its varargs. */
	static String luaArguments(List<JParam> params, boolean varargs) {
		String named = luaArguments(params);
		return !varargs ? named : named.isEmpty() ? "..." : named + ", ...";
	}

	static void paramDocs(List<String> doc, List<JParam> params) {
		for (JParam param : params) {
			doc.add("@param " + param.name() + (param.description().isEmpty() ? "" : " " + param.description()));
		}
	}
}
