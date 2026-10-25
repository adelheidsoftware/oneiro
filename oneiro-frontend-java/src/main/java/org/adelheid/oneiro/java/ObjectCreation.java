package org.adelheid.oneiro.java;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import java.util.ArrayList;
import java.util.HashSet;
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
import org.adelheid.oneiro.lowering.Helper;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.FunctionBody;

/**
 * {@code new} in Lua: an object of a plugin class from its {@code new}, a record with only its components as a
 * table, an anonymous class as a function or as a table of its members, and the client's and the library's
 * classes from their own constructors. Creating what Lua has no object for, such as the plugin class itself, is
 * rejected.
 */
final class ObjectCreation {

	private final BodyLowering body;
	private final UnitLowering unit;
	private final ApiCalls api;
	private final LibraryCalls library;
	private final JavaTypes types;

	ObjectCreation(BodyLowering body) {
		this.body = body;
		this.unit = body.unit;
		this.api = body.api();
		this.library = body.library();
		this.types = body.types();
	}

	Expression lower(NewClassTree creation) {
		if (creation.getClassBody() != null) {
			return anonymous(creation, creation.getClassBody());
		}
		ExecutableElement constructor = (ExecutableElement) unit.element(creation);
		TypeElement type = JavaTypes.owner(constructor);
		if (types.isApi(type)) {
			return api.construct(creation, constructor, type);
		}
		if (types.isReturnValues(type.asType())) {
			throw new Rejection(creation, type.getSimpleName() + " stands for several Lua values, which exist only"
					+ " where a function returns them; create it only in a return statement: return new "
					+ type.getSimpleName() + "(...).");
		}
		if (JavaTypes.isRecord(type) && !types.isObjectRecord(type)) {
			return record(type, creation.getArguments());
		}
		if (types.isPluginEntry(type) || types.isDevEntry(type)) {
			throw new Rejection(creation, "The client creates the plugin class, once, when the plugin starts; use its"
					+ " members directly instead of creating another.");
		}
		if (types.isPluginType(type)) {
			List<Expression> arguments = new ArrayList<>();
			if (JavaTypes.isInner(type) && type.getEnclosingElement() instanceof TypeElement outer) {
				// An inner object gets its outer one first: outer.new Inner(), or the object making it
				ExpressionTree enclosing = creation.getEnclosingExpression();
				arguments.add(enclosing != null ? body.expression(enclosing) : body.receiverFor(outer, creation));
			}
			arguments.addAll(body.arguments((ExecutableElement) unit.element(creation), creation.getArguments()));
			return new Expression.Call(Expression.field(unit.moduleReference(type), "new"), arguments);
		}
		return library.construct(creation, type);
	}

	/**
	 * An anonymous class. One that implements a functional interface with its one method, no fields and no use of
	 * {@code this} is a function, as a lambda is. Any other is an object: a table of the class's fields and methods,
	 * with the interfaces' default methods it does not replace, or for a subclass its superclass's object given them
	 * through the helper {@code withMembers}.
	 */
	private Expression anonymous(NewClassTree creation, ClassTree classBody) {
		TypeElement anonymous = (TypeElement) unit.element(classBody);
		TypeElement base = (TypeElement) ((DeclaredType) unit.type(creation.getIdentifier())).asElement();
		List<MethodTree> methods = new ArrayList<>();
		List<VariableTree> fields = new ArrayList<>();
		for (Tree member : classBody.getMembers()) {
			switch (member) {
				case MethodTree method when unit.declaredInSource(unit.element(method)) -> {
					if (method.getModifiers().getFlags().contains(Modifier.PRIVATE)) {
						throw new Rejection(method, "A private method of an anonymous class would be a local of the"
								+ " whole file in Lua; make it a method the class's others call on self.");
					}
					methods.add(method);
				}
				case MethodTree _ -> {
					// The constructor javac gives the class
				}
				case VariableTree field -> fields.add(field);
				default -> throw new Rejection(member, "An anonymous class here holds fields and methods only; move"
						+ " this into a class of its own.");
			}
		}
		boolean functionalBase = base.getKind() == ElementKind.INTERFACE
				&& (types.isFunctionalPluginInterface(base)
						|| (!types.isPluginType(base) && base.getAnnotation(FunctionalInterface.class) != null));
		if (functionalBase && methods.size() == 1 && fields.isEmpty() && !usesThis(classBody)) {
			MethodTree method = methods.getFirst();
			BodyLowering inner = new BodyLowering(unit, body.hasSelf, false);
			inner.inherit(body);
			List<String> parameters = method.getParameters().stream().map(inner::declare).toList();
			BlockTree methodBody = method.getBody();
			if (methodBody == null) {
				throw new IllegalStateException("an anonymous class's method without a body");
			}
			return new Expression.FunctionExpression(FunctionBody.of(parameters, inner.block(methodBody)));
		}
		if (functionalBase && !types.isPluginType(base)) {
			throw new Rejection(creation, base.getSimpleName() + " stands for a function in Lua, which carries no"
					+ " fields or other methods; write it as a lambda, or as a class of the plugin's.");
		}
		List<Expression.TableField> members = new ArrayList<>();
		BodyLowering inner = new BodyLowering(unit, true, false);
		inner.inherit(body);
		inner.anonymousObject = true;
		for (VariableTree field : fields) {
			inner.declare(field);
			ExpressionTree initializer = field.getInitializer();
			Expression value = initializer == null
					? unit.defaultValue(unit.type(field)) : inner.expression(initializer);
			if (value != null) {
				members.add(new Expression.TableField.Named(types.memberName(unit.element(field)), value));
			}
		}
		for (MethodTree method : methods) {
			BodyLowering methodLowering = new BodyLowering(unit, true, false);
			methodLowering.inherit(body);
			methodLowering.anonymousObject = true;
			List<String> parameters = new ArrayList<>(List.of("self"));
			method.getParameters().forEach(parameter -> parameters.add(methodLowering.declare(parameter)));
			BlockTree methodBody = method.getBody();
			if (methodBody == null) {
				throw new IllegalStateException("an anonymous class's method without a body");
			}
			members.add(new Expression.TableField.Named(types.memberName(unit.element(method)),
					new Expression.FunctionExpression(FunctionBody.of(parameters, methodLowering.block(methodBody)))));
		}
		Set<Element> own = new HashSet<>(unit.elements.getAllMembers(anonymous));
		for (TypeMirror implemented : anonymous.getInterfaces()) {
			if (((DeclaredType) implemented).asElement() instanceof TypeElement face && types.isPluginType(face)) {
				for (ExecutableElement method : ElementFilter.methodsIn(face.getEnclosedElements())) {
					if (method.getModifiers().contains(Modifier.DEFAULT) && own.contains(method)) {
						members.add(new Expression.TableField.Named(types.memberName(method),
								Expression.field(unit.moduleReference(face), types.memberName(method))));
					}
				}
			}
		}
		if (!(types.isPluginType(base) && base.getKind() == ElementKind.CLASS)) {
			// A table of fields and methods is an object already
			return new Expression.Table(members);
		}
		Expression object = new Expression.Call(Expression.field(unit.moduleReference(base), "new"),
				body.arguments(creation.getArguments()));
		unit.use(Helper.WITH_MEMBERS);
		return Helper.WITH_MEMBERS.invoke(object, new Expression.Table(members));
	}

	/** Whether code uses {@code this}, outside the classes inside it. */
	private static boolean usesThis(Tree tree) {
		return new TreeSearch() {
			@Override
			public Boolean visitIdentifier(IdentifierTree node, Void unused) {
				return node.getName().contentEquals("this");
			}

			@Override
			public Boolean visitClass(ClassTree node, Void unused) {
				// The class itself is the one being asked about; a class inside it has its own this
				return node.equals(tree) ? super.visitClass(node, null) : false;
			}
		}.foundIn(tree);
	}

	/** A record as the plain Lua table it is: {@code { spriteId = id, visible = shown }}, one field per component. */
	private Expression record(TypeElement type, List<? extends ExpressionTree> values) {
		List<Expression.TableField> fields = new ArrayList<>();
		for (int index = 0; index < values.size(); index++) {
			String component = types.memberName(type.getRecordComponents().get(index).getAccessor());
			fields.add(new Expression.TableField.Named(component, body.expression(values.get(index))));
		}
		return new Expression.Table(fields);
	}
}
