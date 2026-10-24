package org.adelheid.oneiro.java;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.field;
import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Chunk;
import org.adelheid.oneiro.lua.Comment;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.FunctionBody;
import org.adelheid.oneiro.lua.Statement;
import org.adelheid.oneiro.lua.Trivia;
import org.jspecify.annotations.Nullable;

/**
 * A {@code @ConfigGroup} interface as a module of functions over the client's {@code PersistentDB}. A getter, a
 * default method that returns the default, reads the setting with the typed accessor of its type, and gives the
 * default where nothing is stored. A setter, an abstract method with one parameter, stores it. Each setting is kept
 * under {@code "<group>.<keyName>"}.
 */
final class SettingsModule {

	private final UnitLowering unit;
	private final JavaTypes types;

	SettingsModule(UnitLowering unit) {
		this.unit = unit;
		this.types = unit.types;
	}

	/**
	 * The settings module of a {@code @ConfigGroup} declaration, at {@code path} in the plugin; null when the
	 * declaration has errors, which are reported.
	 */
	@Nullable LuaModule lowerConfig(ClassTree type, TypeElement element, String path) {
		String group = types.configGroup(element);
		if (group == null) {
			return null;
		}
		if (type.getKind() != Tree.Kind.INTERFACE) {
			unit.error(unit.start(type), "@ConfigGroup marks an interface of settings; make " + type.getSimpleName()
					+ " an interface whose methods carry @ConfigItem.");
			return null;
		}
		List<Comment> header = new ArrayList<>(unit.header(type));
		if (!header.isEmpty()) {
			header.add(Comment.line(""));
		}
		header.add(Comment.line("Each setting is kept in PersistentDB under \"" + group + ".\" and its key."));
		unit.useGlobal("PersistentDB");
		List<UnitLowering.Member> members = new ArrayList<>();
		int cursor = unit.start(type);
		for (Tree member : type.getMembers()) {
			if (unit.isSynthetic(member)) {
				continue;
			}
			List<CommentIndex.SourceComment> leading = unit.comments.take(cursor, unit.start(member));
			cursor = unit.end(member);
			try {
				if (!(member instanceof MethodTree method)) {
					throw new Rejection(member, "An interface of settings holds only its settings' getters and setters,"
							+ " each with @ConfigItem.");
				}
				members.add(setting(method, group, leading));
			} catch (Rejection rejection) {
				unit.reject(rejection);
			}
		}
		List<Block.Entry> body = unit.assembly().assemble(new Expression.Table(List.of()), false, members);
		if (unit.diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == Diagnostic.Severity.ERROR)) {
			return null;
		}
		return new LuaModule(path, new Chunk(header, new Block(body, List.of())));
	}

	/** One getter or setter of a setting, as a function of the settings module. */
	private UnitLowering.Member setting(MethodTree method, String group, List<CommentIndex.SourceComment> leading) {
		ExecutableElement element = (ExecutableElement) unit.element(method);
		JavaTypes.ConfigItem item = types.configItem(element);
		if (item == null) {
			throw new Rejection(method, "Every method of an interface of settings is a setting's getter or setter, with"
					+ " @ConfigItem(keyName = ..., name = ...).");
		}
		String key = group + "." + item.keyName();
		List<Comment> lines = new ArrayList<>(BodyLowering.comments(leading));
		lines.add(Comment.line(item.description().isEmpty() ? item.name() : item.name() + ": " + item.description()));
		DocCommentTree doc = unit.trees.getDocCommentTree(unit.path(method));
		if (doc != null) {
			DocText.lines(doc.getFullBody()).forEach(line -> lines.add(Comment.line(line)));
		}
		BlockTree body = method.getBody();
		BodyLowering lowering = new BodyLowering(unit, false, false);
		FunctionBody function;
		if (body != null) {
			if (!method.getParameters().isEmpty() || body.getStatements().size() != 1
					|| !(body.getStatements().getFirst() instanceof ReturnTree returned)
					|| returned.getExpression() == null) {
				throw new Rejection(method, "A setting's getter is a default method without parameters whose body only"
						+ " returns the default value, such as default int count() { return 5; }.");
			}
			TypeMirror type = element.getReturnType();
			Accessor accessor = accessor(type, method);
			lines.add(Comment.annotation("@return " + types.luaType(type, false)));
			function = FunctionBody.of(List.of(), getter(key, accessor, returned.getExpression(), lowering));
		} else {
			if (element.getReturnType().getKind() != TypeKind.VOID || method.getParameters().size() != 1) {
				throw new Rejection(method, "A setting's setter is an abstract void method with one parameter, the new"
						+ " value, such as void setCount(int count); a getter is a default method that returns the"
						+ " default value.");
			}
			VariableTree parameter = method.getParameters().getFirst();
			Accessor accessor = accessor(unit.type(parameter), parameter);
			String local = lowering.declare(parameter);
			lines.add(Comment.annotation("@param " + local + " " + types.luaType(unit.type(parameter), false)));
			// An object enum's constant is kept by its name
			Expression stored = accessor.enumType() != null && types.isObjectEnum(accessor.enumType().asType())
					? field(name(local), "name")
					: name(local);
			Expression store = Expression.methodCall(name("PersistentDB"), "Set" + accessor.suffix(),
					Expression.string(key), stored);
			function = FunctionBody.of(List.of(local), Block.of(new Statement.CallStatement(store)));
		}
		Statement declaration = new Statement.FunctionDeclaration(List.of(unit.moduleName, types.memberName(element)),
				null, function);
		return new UnitLowering.Member(List.of(element), true, null,
				List.of(new Block.Entry(declaration, new Trivia(true, lines, null), unit.origin(method))), List.of(),
				unit.assembly().references(method), Set.of());
	}

	/** How a setting of a type is kept: the typed accessor's suffix, and the enum whose constants it holds, if any. */
	private record Accessor(String suffix, @Nullable TypeElement enumType) {
	}

	private Accessor accessor(TypeMirror type, Tree where) {
		if (types.isBoolean(type)) {
			return new Accessor("Bool", null);
		}
		if (types.isIntegral(type)) {
			return new Accessor("Int", null);
		}
		if (types.isFloating(type)) {
			return new Accessor("Float", null);
		}
		if (types.isString(type)) {
			return new Accessor("String", null);
		}
		if (types.isPluginEnum(type)) {
			return new Accessor("String", types.pluginType(type));
		}
		throw new Rejection(where, "A setting is a boolean, an int or long, a double, a String or an enum of the"
				+ " plugin's; PersistentDB keeps those with typed accessors.");
	}

	/**
	 * A getter's body: the stored value, or the default where none is stored. A boolean defaulting to anything but
	 * {@code false} needs an {@code if}, because {@code or} would turn a stored {@code false} into the default.
	 */
	private Block getter(String key, Accessor accessor, ExpressionTree defaultTree, BodyLowering lowering) {
		Expression stored = Expression.methodCall(name("PersistentDB"), "Get" + accessor.suffix(),
				Expression.string(key));
		Expression fallback = lowering.expression(defaultTree);
		TypeElement enumType = accessor.enumType();
		if (enumType != null) {
			// Only a name that is one of the enum's constants reads back as a value
			Expression constant = new Expression.Index(unit.moduleReference(enumType), stored);
			return Block.of(new Statement.Return(List.of(binary(constant, BinaryOperator.OR, fallback))));
		}
		boolean falseDefault = fallback instanceof Expression.BooleanLiteral literal && !literal.value();
		if (!accessor.suffix().equals("Bool") || falseDefault) {
			return Block.of(new Statement.Return(List.of(binary(stored, BinaryOperator.OR, fallback))));
		}
		Expression unset = binary(name("stored"), BinaryOperator.EQUAL, new Expression.Nil());
		return Block.of(
				new Statement.Local(List.of("stored"), List.of(stored)),
				Statement.ifThen(unset, Block.of(new Statement.Return(List.of(fallback)))),
				new Statement.Return(List.of(name("stored"))));
	}
}
