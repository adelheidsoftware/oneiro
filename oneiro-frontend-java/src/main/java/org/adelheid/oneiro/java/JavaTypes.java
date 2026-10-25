package org.adelheid.oneiro.java;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.adelheid.oneiro.api.LuaConstants;
import org.adelheid.oneiro.plugin.TypedSources;
import org.jspecify.annotations.Nullable;

/**
 * The questions about Java types that decide a translation, such as whether a value is a string, an integer or a
 * list, answered with javac's model of the plugin's sources and the JDK.
 */
final class JavaTypes {

	private static final String NULLABLE = "org.jspecify.annotations.Nullable";
	private static final String GENERATED = "javax.annotation.processing.Generated";
	private static final String API = "org.adelheid.oneiro.api.";
	private static final String LUA_NAME = API + "LuaName";
	private static final String LUA_MODULE = API + "LuaModule";
	private static final String LUA_GLOBAL = API + "LuaGlobal";
	private static final String LUA_CONSTANTS = API + "LuaConstants";
	private static final String LUA_TYPE = API + "LuaType";
	private static final String LUA_EVENT = API + "LuaEvent";
	private static final String LUA_RETURN_VALUES = API + "LuaReturnValues";
	static final String LUA_COLON = API + "LuaColon";
	static final String LUA_DOT = API + "LuaDot";
	static final String LUA_INDEX = API + "LuaIndex";
	static final String LUA_COPY = API + "LuaCopy";
	private static final String LUA_OPERATOR = API + "LuaOperator";
	static final String LUA_SAME = API + "LuaSame";
	static final String LUA_DEV_ONLY = API + "LuaDevOnly";
	static final String LUA_FROM_ID = API + "LuaFromId";
	static final String LUA_PERMISSION = API + "LuaPermission";
	static final String LUA_MANIFEST_FLAG = API + "LuaManifestFlag";
	private static final String LUA_REVIEW = API + "LuaReview";

	private final Types types;
	private final Elements elements;
	private final Set<TypeElement> pluginTypes;
	private final Set<TypeElement> objectTypes;
	private final @Nullable String compiledRootPackage;

	/** @param pluginTypes the classes declared in the plugin's own sources, as opposed to library classes */
	JavaTypes(Types types, Elements elements, Set<TypeElement> pluginTypes) {
		this(types, elements, pluginTypes, Set.of(), null);
	}

	/**
	 * @param pluginTypes the classes declared in the sources being compiled
	 * @param objectTypes the plugin's enums whose constants are Lua objects, not the strings of their names, and its
	 *     records that are classes, not plain tables
	 * @param compiledRootPackage for the dev source set, the plugin's root package, whose classes from the main source
	 *     set are plugin classes too, though they come compiled; null otherwise
	 */
	JavaTypes(Types types, Elements elements, Set<TypeElement> pluginTypes, Set<TypeElement> objectTypes,
			@Nullable String compiledRootPackage) {
		this.types = types;
		this.elements = elements;
		this.pluginTypes = Set.copyOf(pluginTypes);
		this.objectTypes = Set.copyOf(objectTypes);
		this.compiledRootPackage = compiledRootPackage;
	}

	/**
	 * Whether an enum of the plugin's has constants that are Lua objects, {@code Speed.new("FAST", 1, 3)}, because it
	 * has fields, constructors, methods or constant bodies, or code reads its order; a simple enum's constants are the
	 * strings of their names.
	 */
	boolean isObjectEnum(TypeMirror type) {
		return type instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element
				&& element.getKind() == ElementKind.ENUM && objectTypes.contains(element);
	}

	/** Whether a class is declared in the sources being compiled, rather than in the main source set's classes. */
	boolean isSourceType(TypeElement type) {
		return pluginTypes.contains(type);
	}

	boolean isString(TypeMirror type) {
		return is(type, "java.lang.String");
	}

	boolean isStringBuilder(TypeMirror type) {
		return is(type, "java.lang.StringBuilder");
	}

	/** {@code int}, {@code long}, {@code short} and {@code byte}, boxed or not: Lua integers. */
	boolean isIntegral(TypeMirror type) {
		return switch (unboxed(type).getKind()) {
			case INT, LONG, SHORT, BYTE -> true;
			default -> false;
		};
	}

	boolean isLong(TypeMirror type) {
		return unboxed(type).getKind() == TypeKind.LONG;
	}

	/** {@code double} and {@code float}, boxed or not: Lua floats. */
	boolean isFloating(TypeMirror type) {
		return switch (unboxed(type).getKind()) {
			case DOUBLE, FLOAT -> true;
			default -> false;
		};
	}

	boolean isBoolean(TypeMirror type) {
		return unboxed(type).getKind() == TypeKind.BOOLEAN;
	}

	boolean isChar(TypeMirror type) {
		return unboxed(type).getKind() == TypeKind.CHAR;
	}

	/** A {@code Stream}, {@code IntStream} or another stream, which Lua has only as a loop. */
	boolean isStream(TypeMirror type) {
		return isSubtype(type, "java.util.stream.BaseStream");
	}

	/** An {@code Optional}, or one of its primitive kin such as {@code OptionalInt}: in Lua, a value or nil. */
	boolean isOptional(TypeMirror type) {
		return is(type, "java.util.Optional") || is(type, "java.util.OptionalInt") || is(type, "java.util.OptionalLong")
				|| is(type, "java.util.OptionalDouble");
	}

	boolean isList(TypeMirror type) {
		return isSubtype(type, "java.util.List");
	}

	boolean isSet(TypeMirror type) {
		return isSubtype(type, "java.util.Set");
	}

	boolean isMap(TypeMirror type) {
		return isSubtype(type, "java.util.Map");
	}

	/**
	 * A queue or deque, such as {@code ArrayDeque} or {@code LinkedList}: a Lua list with its first element in front.
	 */
	boolean isDeque(TypeMirror type) {
		return isSubtype(type, "java.util.Queue");
	}

	/** Whether the type is exactly the named class or interface, ignoring type arguments. */
	boolean is(TypeMirror type, String qualifiedName) {
		return type.getKind() == TypeKind.DECLARED
				&& ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().contentEquals(qualifiedName);
	}

	boolean isSubtype(TypeMirror type, String qualifiedName) {
		TypeElement element = elements.getTypeElement(qualifiedName);
		return element != null
				&& type.getKind() == TypeKind.DECLARED
				&& types.isSubtype(types.erasure(type), types.erasure(element.asType()));
	}

	boolean isPluginType(Element element) {
		return element instanceof TypeElement type && (pluginTypes.contains(type) || isCompiledPluginType(type));
	}

	/** For the dev source set: a class of the main source set, compiled, in the plugin's root package. */
	private boolean isCompiledPluginType(TypeElement type) {
		String root = compiledRootPackage;
		if (root == null || isApi(type)) {
			return false;
		}
		String name = elements.getPackageOf(type).getQualifiedName().toString();
		return name.equals(root) || name.startsWith(root + ".");
	}

	/** Whether the type is an enum of the plugin's, whose values are the constants' names as Lua strings. */
	boolean isPluginEnum(TypeMirror type) {
		TypeElement plugin = pluginType(type);
		return plugin != null && plugin.getKind() == ElementKind.ENUM;
	}

	/**
	 * Whether a class of the plugin's implements the interface, so that its values are Lua objects with methods rather
	 * than plain functions.
	 */
	boolean isImplemented(TypeElement type) {
		TypeMirror erased = types.erasure(type.asType());
		return pluginTypes.stream().anyMatch(plugin -> plugin.getKind() != ElementKind.INTERFACE
				&& types.isSubtype(types.erasure(plugin.asType()), erased));
	}

	/** Whether a class is an inner class: a nested class that is not static, whose objects keep an outer object. */
	static boolean isInner(TypeElement type) {
		return type.getKind() == ElementKind.CLASS && type.getNestingKind() == NestingKind.MEMBER
				&& !type.getModifiers().contains(Modifier.STATIC);
	}

	/** Whether every value of the one type is a value of the other, their type arguments aside. */
	boolean isWithin(TypeMirror type, TypeMirror other) {
		return types.isSubtype(types.erasure(type), types.erasure(other));
	}

	/** Whether a class of the plugin's, not counting an enum constant's body, extends the class. */
	boolean hasPluginSubclass(TypeElement type) {
		return pluginTypes.stream().anyMatch(plugin -> plugin.getNestingKind() != NestingKind.ANONYMOUS
				&& plugin.getSuperclass() instanceof DeclaredType parent && parent.asElement().equals(type));
	}

	/**
	 * The classes of the plugin's whose objects are of the type, each the topmost of its line: the class itself, or for
	 * an interface each class that implements it while its superclass does not; in name order.
	 */
	List<TypeElement> instanceRoots(TypeElement type) {
		TypeMirror erased = types.erasure(type.asType());
		return pluginTypes.stream()
				.filter(plugin -> plugin.getKind() != ElementKind.INTERFACE
						&& plugin.getNestingKind() != NestingKind.ANONYMOUS
						&& types.isSubtype(types.erasure(plugin.asType()), erased))
				.filter(plugin -> !(plugin.getSuperclass() instanceof DeclaredType parent
						&& parent.asElement() instanceof TypeElement superclass && pluginTypes.contains(superclass)
						&& types.isSubtype(types.erasure(superclass.asType()), erased)))
				.sorted(Comparator.comparing(plugin -> plugin.getQualifiedName().toString()))
				.toList();
	}

	/**
	 * Whether the type is an interface of the plugin's whose values are plain Lua functions: it has one abstract
	 * method, so lambdas implement it, and no class of the plugin's implements it.
	 */
	boolean isFunctionalPluginInterface(TypeElement type) {
		return type.getKind() == ElementKind.INTERFACE && pluginTypes.contains(type)
				&& elements.isFunctionalInterface(type) && !isImplemented(type);
	}

	/**
	 * Whether a functional interface's method returns nothing, as {@code Consumer.accept} does, so that nothing reads
	 * the value of a function that stands for it; false for any other type.
	 */
	boolean returnsNothing(TypeMirror type) {
		if (!(type instanceof DeclaredType declared) || !(declared.asElement() instanceof TypeElement element)
				|| !elements.isFunctionalInterface(element)) {
			return false;
		}
		return ElementFilter.methodsIn(elements.getAllMembers(element)).stream()
				.filter(method -> method.getModifiers().contains(Modifier.ABSTRACT))
				.anyMatch(method -> method.getReturnType().getKind() == TypeKind.VOID);
	}

	/** The plugin class a type names, or null for library types, arrays and primitives. */
	@Nullable TypeElement pluginType(TypeMirror type) {
		if (type.getKind() != TypeKind.DECLARED) {
			return null;
		}
		TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
		return isPluginType(element) ? element : null;
	}

	/** A type argument of a parameterised type, such as the element type of a list; {@code Object} when raw. */
	TypeMirror typeArgument(TypeMirror type, int index) {
		if (type instanceof DeclaredType declared && declared.getTypeArguments().size() > index) {
			return declared.getTypeArguments().get(index);
		}
		return elements.getTypeElement("java.lang.Object").asType();
	}

	/** Whether a type use carries JSpecify's {@code @Nullable}. */
	boolean isNullable(TypeMirror type) {
		return type.getAnnotationMirrors().stream().anyMatch(annotation ->
				((TypeElement) annotation.getAnnotationType().asElement()).getQualifiedName().contentEquals(NULLABLE));
	}

	/**
	 * The type as the Lua Language Server writes it in {@code ---@param} and {@code ---@return}: {@code integer},
	 * {@code number}, {@code string}, {@code boolean}, {@code T[]}, {@code table<K, V>}, {@code table} for a record,
	 * {@code string} for an enum, {@code function} for a functional interface, a plugin class name, or {@code any};
	 * with a trailing {@code ?} when it may be nil, as an {@code Optional}'s value may.
	 */
	String luaType(TypeMirror type, boolean nullable) {
		if (isOptional(type)) {
			return is(type, "java.util.Optional")
					? luaType(typeArgument(type, 0), true)
					: is(type, "java.util.OptionalDouble") ? "number?" : "integer?";
		}
		String base;
		if (isIntegral(type)) {
			base = "integer";
		} else if (isFloating(type)) {
			base = "number";
		} else if (isBoolean(type)) {
			base = "boolean";
		} else if (isString(type) || isChar(type)) {
			base = "string";
		} else if (type instanceof ArrayType array) {
			base = luaType(array.getComponentType(), false) + "[]";
		} else if (isList(type) || isDeque(type)) {
			base = luaType(typeArgument(type, 0), false) + "[]";
		} else if (isSet(type)) {
			base = "table<" + luaType(typeArgument(type, 0), false) + ", true>";
		} else if (isMap(type)) {
			base = "table<" + luaType(typeArgument(type, 0), false) + ", "
					+ luaType(typeArgument(type, 1), false) + ">";
		} else {
			base = pluginLuaType(type);
		}
		return nullable && !base.equals("any") ? base + "?" : base;
	}

	/**
	 * A record is a table, an enum value a string, a functional interface a function; another class is its name. An API
	 * type is its Lua name, such as {@code NPCEntity}.
	 */
	private String pluginLuaType(TypeMirror type) {
		if (isObjectEnum(type)) {
			return ((DeclaredType) type).asElement().getSimpleName().toString();
		}
		if (type instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element) {
			String apiName = apiTypeName(element);
			if (apiName != null) {
				return apiName;
			}
			if (elements.isFunctionalInterface(element) && !pluginTypes.contains(element)) {
				return "function";
			}
		}
		TypeElement plugin = pluginType(type);
		if (plugin == null) {
			return "any";
		}
		if (isRecord(plugin) && !isObjectRecord(plugin)) {
			return "table";
		}
		if (plugin.getKind() == ElementKind.ENUM) {
			return "string";
		}
		if (isFunctionalPluginInterface(plugin)) {
			return "function";
		}
		return plugin.getSimpleName().toString();
	}

	/** The name a member has in the Lua: its {@code @LuaName}, or else its Java name. */
	String memberName(Element member) {
		String declared = annotationValue(member, LUA_NAME);
		if (declared != null) {
			return declared;
		}
		String name = member.getSimpleName().toString();
		// A constant table's names carry a prefix in Java so that each is a Java name; Lua has them without it
		if (member.getKind() == ElementKind.FIELD && member.getEnclosingElement() instanceof TypeElement owner
				&& hasAnnotation(owner, LUA_CONSTANTS) && name.startsWith(LuaConstants.PREFIX)) {
			return name.substring(LuaConstants.PREFIX.length());
		}
		return name;
	}

	/**
	 * The require path of the module an API class belongs to: the {@code @LuaModule} of its outermost class, which the
	 * bindings of a Lua module carry, such as the plugin's own {@code src/core/levels}; null for the class of a global
	 * table or of a type.
	 */
	@Nullable String module(TypeElement type) {
		TypeElement outermost = type;
		while (outermost.getEnclosingElement() instanceof TypeElement outer) {
			outermost = outer;
		}
		return annotationValue(outermost, LUA_MODULE);
	}

	/**
	 * Whether a class of the sources is one that Oneiro generated, the plugin's {@code Resources} and {@code Urls},
	 * which carry the binding annotations no class of the plugin's own may.
	 */
	static boolean isGenerated(TypeElement type) {
		for (AnnotationMirror mirror : type.getAnnotationMirrors()) {
			if (!isAnnotation(mirror, GENERATED)) {
				continue;
			}
			for (AnnotationValue value : mirror.getElementValues().values()) {
				if (value.getValue() instanceof List<?> generators && generators.stream().anyMatch(generator ->
						generator instanceof AnnotationValue named
								&& TypedSources.GENERATOR.equals(named.getValue()))) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The Lua path of a {@code @LuaGlobal} class's table, such as {@code ui.Text}, or {@code ""} for one whose members
	 * are globals; null for a class without the annotation.
	 */
	@Nullable String luaGlobal(TypeElement type) {
		return annotationValue(type, LUA_GLOBAL);
	}

	/**
	 * Whether a class stands for Lua that Oneiro does not write, with {@code @LuaGlobal} or {@code @LuaType}: the RS3
	 * bindings, the bindings of the plugin's own Lua, and the plugin's generated {@code Resources} and {@code Urls}.
	 * Such a class has no Lua of its own; code that uses it calls the Lua directly.
	 */
	boolean isApi(TypeElement type) {
		return hasAnnotation(type, LUA_GLOBAL) || hasAnnotation(type, LUA_TYPE);
	}

	/** The name an API type has in Lua Language Server annotations, such as {@code NPCEntity}; null for other types. */
	@Nullable String apiTypeName(TypeElement type) {
		String named = annotationValue(type, LUA_TYPE);
		if (named != null) {
			return named;
		}
		String global = annotationValue(type, LUA_GLOBAL);
		return global == null || global.isEmpty() ? null : global;
	}

	/** Whether a type is an API type: a binding class or enum. */
	boolean isApiType(TypeMirror type) {
		return type instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element
				&& isApi(element);
	}

	/**
	 * The event a payload class belongs to, with {@code @LuaEvent}, and whether its callbacks return a boolean; null
	 * for any other class.
	 */
	@Nullable Event event(TypeElement payload) {
		for (AnnotationMirror mirror : payload.getAnnotationMirrors()) {
			if (!isAnnotation(mirror, LUA_EVENT)) {
				continue;
			}
			String name = "";
			boolean returnsBoolean = false;
			for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
					: mirror.getElementValues().entrySet()) {
				switch (entry.getKey().getSimpleName().toString()) {
					case "value" -> name = entry.getValue().getValue().toString();
					case "returns" -> returnsBoolean = entry.getValue().getValue() instanceof TypeMirror returned
							&& returned.getKind() == TypeKind.BOOLEAN;
					default -> {
					}
				}
			}
			return new Event(name, returnsBoolean);
		}
		return null;
	}

	/** An event of the client's {@code Event} table, as a payload class's {@code @LuaEvent} names it. */
	record Event(String name, boolean returnsBoolean) {
	}

	/** Whether a type is a record marked {@code @LuaReturnValues}: the several values a Lua function returns. */
	boolean isReturnValues(TypeMirror type) {
		return type instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element
				&& isRecord(element) && hasAnnotation(element, LUA_RETURN_VALUES);
	}

	/** Whether another class of the sources being compiled overrides the method, so that a call may run either. */
	boolean isOverridden(ExecutableElement method) {
		Set<Modifier> flags = method.getModifiers();
		if (flags.contains(Modifier.STATIC) || flags.contains(Modifier.PRIVATE) || flags.contains(Modifier.FINAL)) {
			return false;
		}
		TypeElement owner = owner(method);
		for (TypeElement type : pluginTypes) {
			if (type.equals(owner) || !types.isSubtype(types.erasure(type.asType()), types.erasure(owner.asType()))) {
				continue;
			}
			for (ExecutableElement other : ElementFilter.methodsIn(type.getEnclosedElements())) {
				if (elements.overrides(other, method, type)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Whether a class is the plugin's entry point, the one that extends {@code Plugin} and becomes {@code main.lua}.
	 */
	boolean isPluginEntry(TypeElement type) {
		return directSuperclass(type, API + "Plugin");
	}

	/** Whether a class is the dev source set's entry point, the one that extends {@code DevPlugin}. */
	boolean isDevEntry(TypeElement type) {
		return directSuperclass(type, API + "DevPlugin");
	}

	/** The other classes of the sources being compiled that extend the same base as this entry point, by name. */
	List<String> otherEntries(TypeElement entry) {
		boolean dev = isDevEntry(entry);
		return pluginTypes.stream()
				.filter(type -> !type.equals(entry) && (dev ? isDevEntry(type) : isPluginEntry(type)))
				.map(type -> type.getQualifiedName().toString())
				.sorted()
				.toList();
	}

	private static boolean directSuperclass(TypeElement type, String superclass) {
		return type.getSuperclass() instanceof DeclaredType declared
				&& ((TypeElement) declared.asElement()).getQualifiedName().contentEquals(superclass);
	}

	/** The group of a {@code @ConfigGroup} interface of settings, or null for any other type. */
	@Nullable String configGroup(TypeElement type) {
		return annotationValue(type, API + "ConfigGroup");
	}

	/** The interface of settings a type names, or null when it is not one. */
	@Nullable TypeElement configType(TypeMirror type) {
		TypeElement plugin = pluginType(type);
		return plugin != null && configGroup(plugin) != null ? plugin : null;
	}

	/** Whether a method subscribes to an event, with {@code @Subscribe}. */
	static boolean isSubscriber(Element method) {
		return hasAnnotation(method, API + "Subscribe");
	}

	/** The values of a {@code @ConfigItem} on a method: its key, name and description; null without one. */
	@Nullable ConfigItem configItem(Element method) {
		for (AnnotationMirror mirror : method.getAnnotationMirrors()) {
			if (!isAnnotation(mirror, API + "ConfigItem")) {
				continue;
			}
			String key = "";
			String name = "";
			String description = "";
			for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
					: mirror.getElementValues().entrySet()) {
				String value = entry.getValue().getValue().toString();
				switch (entry.getKey().getSimpleName().toString()) {
					case "keyName" -> key = value;
					case "name" -> name = value;
					case "description" -> description = value;
					default -> {
					}
				}
			}
			return new ConfigItem(key, name, description);
		}
		return null;
	}

	/** One setting of a {@code @ConfigGroup} interface, as its {@code @ConfigItem} describes it. */
	record ConfigItem(String keyName, String name, String description) {
	}

	/** Whether an element carries the annotation, by its qualified name. */
	static boolean hasAnnotation(Element element, String annotation) {
		return element.getAnnotationMirrors().stream().anyMatch(mirror -> isAnnotation(mirror, annotation));
	}

	/** The value of the annotation on the element, or else on the class that declares it; null when neither has it. */
	@Nullable String inherited(Element member, String annotation) {
		String own = annotationValue(member, annotation);
		if (own != null || !(member.getEnclosingElement() instanceof TypeElement owner)) {
			return own;
		}
		return annotationValue(owner, annotation);
	}

	/**
	 * The area moderation reviews the member in, from its own {@code @LuaReview}: an event payload's is about
	 * subscribing to its event, not about its fields, so a member does not take its class's. Null when it has none.
	 */
	@Nullable String reviewArea(Element member) {
		return annotationValue(member, LUA_REVIEW);
	}

	/** Whether the element or the class that declares it carries the annotation. */
	static boolean hasOwnOrClass(Element member, String annotation) {
		return hasAnnotation(member, annotation)
				|| (member.getEnclosingElement() instanceof TypeElement owner && hasAnnotation(owner, annotation));
	}

	private static boolean isAnnotation(AnnotationMirror mirror, String annotation) {
		return ((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().contentEquals(annotation);
	}

	/** Whether a type is an exception, whose Lua form is the error value {@code pcall} hands back. */
	boolean isThrowable(TypeMirror type) {
		return isSubtype(type, "java.lang.Throwable");
	}

	/** Whether a type is a record: a plain Lua table, or, with members of its own, an object record. */
	static boolean isRecord(Element type) {
		return type.getKind() == ElementKind.RECORD;
	}

	/**
	 * Whether a record declares members of its own, methods, constructors, static members or nested types, or
	 * implements an interface, and so is a class whose fields are its components, {@code Tile.new(x, y)}; a record
	 * with only its components is a plain table, {@code { x = x, y = y }}.
	 */
	boolean isObjectRecord(Element type) {
		return isRecord(type) && type instanceof TypeElement record && objectTypes.contains(record);
	}

	/** Whether a method is the accessor of one of its record's components, so that a call is a field read. */
	static boolean isRecordAccessor(ExecutableElement method) {
		return method.getEnclosingElement() instanceof TypeElement owner && isRecord(owner)
				&& owner.getRecordComponents().stream().anyMatch(component -> method.equals(component.getAccessor()));
	}

	/** The Lua operator an API method stands for, {@code +} for {@code Vector3.plus}, or null for a plain method. */
	static @Nullable String operator(ExecutableElement method) {
		return annotationValue(method, LUA_OPERATOR);
	}

	/** The value of an annotation's {@code value()} on an element, or null when the element does not carry it. */
	private static @Nullable String annotationValue(Element element, String annotation) {
		for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
			if (isAnnotation(mirror, annotation)) {
				for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
						: mirror.getElementValues().entrySet()) {
					if (entry.getKey().getSimpleName().contentEquals("value")) {
						return entry.getValue().getValue().toString();
					}
				}
			}
		}
		return null;
	}

	/** The class that declares a member; every member Oneiro looks at belongs to one. */
	static TypeElement owner(Element member) {
		if (member.getEnclosingElement() instanceof TypeElement type) {
			return type;
		}
		throw new IllegalStateException("no enclosing class for " + member);
	}

	private TypeMirror unboxed(TypeMirror type) {
		if (type.getKind() != TypeKind.DECLARED) {
			return type;
		}
		try {
			return types.unboxedType(type);
		} catch (IllegalArgumentException notABox) {
			return type;
		}
	}
}
