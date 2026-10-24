package org.adelheid.oneiro.java;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.field;
import static org.adelheid.oneiro.lua.Expression.name;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Comment;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.FunctionBody;
import org.adelheid.oneiro.lua.Statement;
import org.adelheid.oneiro.lua.Trivia;
import org.jspecify.annotations.Nullable;

/**
 * What a class takes from the interfaces it implements, which Lua's metatables do not give it: the default methods
 * of the plugin's interfaces that it does not override, copied into its table, and for the library's
 * {@code Comparable} and functional interfaces, the metamethods that let {@code <}, {@code table.sort} and a call
 * use its objects.
 */
final class InheritedMembers {

	private final UnitLowering unit;
	private final JavaTypes types;

	InheritedMembers(UnitLowering unit) {
		this.unit = unit;
		this.types = unit.types;
	}

	/**
	 * The default methods a class takes from the plugin's interfaces without overriding them, which Lua copies into its
	 * table, {@code Herbal.describe = Describable.describe}; one its superclass took already comes through that.
	 */
	List<UnitLowering.Member> inheritedDefaults(TypeElement owner, String table) {
		if (owner.getKind() == ElementKind.INTERFACE) {
			return List.of();
		}
		TypeElement superclass = unit.pluginSuperclass(owner);
		Set<Element> fromSuperclass = superclass == null
				? Set.of() : Set.copyOf(unit.elements.getAllMembers(superclass));
		Set<Element> members = Set.copyOf(unit.elements.getAllMembers(owner));
		List<UnitLowering.Member> copies = new ArrayList<>();
		for (TypeElement implemented : pluginInterfaces(owner)) {
			for (ExecutableElement method : ElementFilter.methodsIn(implemented.getEnclosedElements())) {
				if (!method.getModifiers().contains(Modifier.DEFAULT) || !members.contains(method)
						|| fromSuperclass.contains(method)) {
					continue;
				}
				String luaName = types.memberName(method);
				Statement copy = Statement.assignment(field(name(table), luaName),
						field(unit.moduleReference(implemented), luaName));
				Set<Element> references = unit.fileTypes.contains(implemented) ? Set.of(implemented, method) : Set.of();
				copies.add(new UnitLowering.Member(List.of(), false, null, List.of(Block.Entry.of(copy)), List.of(),
						references, unit.ownTable(owner)));
			}
		}
		return copies;
	}

	/** The plugin's interfaces a class implements, directly or through others, each once, nearest first. */
	private List<TypeElement> pluginInterfaces(TypeElement owner) {
		List<TypeElement> found = new ArrayList<>();
		List<TypeMirror> pending = new ArrayList<>(owner.getInterfaces());
		while (!pending.isEmpty()) {
			if (pending.removeFirst() instanceof DeclaredType declared
					&& declared.asElement() instanceof TypeElement type && types.isPluginType(type)
					&& !found.contains(type)) {
				found.add(type);
				pending.addAll(type.getInterfaces());
			}
		}
		return found;
	}

	/**
	 * What a class needs for the library interfaces it implements: {@code __lt} for {@code Comparable}, so that
	 * {@code table.sort} and {@code <} order its objects by {@code compareTo}, and {@code __call} for a functional
	 * interface, so that an object stands where Lua expects a function. Lua looks metamethods up in the class's own
	 * table, so each subclass has its own.
	 */
	List<UnitLowering.Member> libraryMetamethods(TypeElement owner, String table) {
		if (owner.getKind() == ElementKind.INTERFACE) {
			return List.of();
		}
		List<UnitLowering.Member> metamethods = new ArrayList<>();
		if (types.isSubtype(owner.asType(), "java.lang.Comparable")) {
			// An enum's constants order by their ordinal, which its compareTo compares
			boolean enumConstants = owner.getKind() == ElementKind.ENUM;
			Expression lessThan = enumConstants
					? binary(field(name("a"), "ordinal"), BinaryOperator.LESS, field(name("b"), "ordinal"))
					: binary(Expression.methodCall(name("a"), "compareTo", name("b")), BinaryOperator.LESS,
							Expression.integer(0));
			Statement function = new Statement.FunctionDeclaration(List.of(table, "__lt"), null,
					FunctionBody.of(List.of("a", "b"), Block.of(new Statement.Return(List.of(lessThan)))));
			String comment = enumConstants
					? "Orders the constants as they are declared, as Java's natural order does"
					: "Orders by compareTo, as Java's natural order does";
			metamethods.add(new UnitLowering.Member(List.of(), true, null,
					List.of(new Block.Entry(function, new Trivia(true, List.of(Comment.line(comment)), null), null)),
					List.of(), Set.of(), unit.ownTable(owner)));
		}
		String functional = libraryFunctionalMethod(owner);
		if (functional != null) {
			Expression called = Expression.methodCall(name("self"), functional, new Expression.Vararg());
			Statement function = new Statement.FunctionDeclaration(List.of(table), "__call",
					new FunctionBody(List.of(), true, Block.of(new Statement.Return(List.of(called)))));
			Comment comment = Comment.line("Called as a function, as library code calls " + functional);
			metamethods.add(new UnitLowering.Member(List.of(), true, null,
					List.of(new Block.Entry(function, new Trivia(true, List.of(comment), null), null)),
					List.of(), Set.of(), unit.ownTable(owner)));
		}
		return metamethods;
	}

	/**
	 * The method of the functional library interface a class implements, such as {@code accept} for a
	 * {@code Consumer}; null when it implements none.
	 */
	private @Nullable String libraryFunctionalMethod(TypeElement owner) {
		List<TypeMirror> pending = new ArrayList<>(owner.getInterfaces());
		TypeElement superclass = unit.pluginSuperclass(owner);
		if (superclass != null) {
			pending.add(superclass.asType());
		}
		while (!pending.isEmpty()) {
			if (!(pending.removeFirst() instanceof DeclaredType declared)
					|| !(declared.asElement() instanceof TypeElement type)) {
				continue;
			}
			// Interfaces meant as functions say so, as Runnable and Consumer do; Comparable and AutoCloseable do not
			if (!types.isPluginType(type) && type.getKind() == ElementKind.INTERFACE
					&& type.getAnnotation(FunctionalInterface.class) != null) {
				for (ExecutableElement method : ElementFilter.methodsIn(unit.elements.getAllMembers(type))) {
					if (method.getModifiers().contains(Modifier.ABSTRACT)
							&& !method.getSimpleName().contentEquals("equals")
							&& !method.getSimpleName().contentEquals("hashCode")
							&& !method.getSimpleName().contentEquals("toString")) {
						return method.getSimpleName().toString();
					}
				}
			}
			pending.addAll(type.getInterfaces());
			if (type.getSuperclass() instanceof DeclaredType parent && parent.asElement() instanceof TypeElement next
					&& types.isPluginType(next)) {
				pending.add(parent);
			}
		}
		return null;
	}
}
