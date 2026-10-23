package org.adelheid.oneiro.java;

import static javax.tools.Diagnostic.NOPOS;
import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.field;
import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Chunk;
import org.adelheid.oneiro.lua.Comment;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.FunctionBody;
import org.adelheid.oneiro.lua.LuaNames;
import org.adelheid.oneiro.lua.SourcePosition;
import org.adelheid.oneiro.lua.Statement;
import org.adelheid.oneiro.lua.Trivia;
import org.jspecify.annotations.Nullable;

/**
 * Lowers one Java source file into one Lua module: a header from the file's leading comments and the class Javadoc,
 * a {@code require} line for each plugin class it uses, the helpers its translations need, the module table, the
 * nested classes and enums as local tables, and the members of every class, ordered so that Lua defines each one
 * before it runs.
 */
final class UnitLowering {

	private static final Pattern NAMED_RETURN = Pattern.compile("([A-Za-z_]\\w*):\\s+(.+)", Pattern.DOTALL);

	final CompilationUnitTree unit;
	final DocTrees trees;
	final Elements elements;
	final JavaTypes types;
	final LocalNames names;
	final CommentIndex comments;
	private final String rootPackage;
	final PluginFacts plugin;
	private final CharSequence source;
	private final SourcePositions positions;
	private final List<Diagnostic> diagnostics = new ArrayList<>();
	private final EnumSet<Helper> helpers = EnumSet.noneOf(Helper.class);
	// The Lua globals the module reads, such as Player or config, which a local of the same name would hide
	private final Set<String> globals = new HashSet<>();
	private final @Nullable Set<Helper> helpersInUse;
	// The local name of each required module and its require path, in order of first use
	private final Map<String, String> requires = new LinkedHashMap<>();
	/** Fields known never to hold a negative integer: constants, and fields that start so and are only raised. */
	private final Set<Element> nonNegativeFields = new HashSet<>();
	/** Methods of this file known to return only non-negative integers, or known not to. */
	private final Map<Element, Boolean> nonNegativeMethods = new HashMap<>();
	// Every class, enum, record and interface declared in this file
	private final Set<TypeElement> fileTypes = new HashSet<>();
	private @Nullable TypeElement topLevel;
	// The plugin class, or the dev source set's DevPlugin class, when this file is one; it becomes a chunk of locals
	private @Nullable TypeElement entry;
	// The comments above startUp() and shutDown(), which go above their statements
	private final Map<String, List<CommentIndex.SourceComment>> lifecycleComments = new HashMap<>();
	// The @Subscribe methods whose event passes nothing, whose Lua functions take no parameter
	private final Set<Element> parameterless = new HashSet<>();
	private String moduleName = "";
	private @Nullable Set<Element> oneBased;
	private @Nullable CharConversions charConversions;
	private @Nullable Set<String> fileLocals;

	/**
	 * @param names the Lua names for Java locals, which avoid the helpers and globals a first lowering found
	 * @param helpersInUse the helpers a first lowering of the file used, or null during that first lowering
	 */
	UnitLowering(CompilationUnitTree unit, DocTrees trees, Elements elements, JavaTypes types, LocalNames names,
			JavaFrontend.Options options, @Nullable Set<Helper> helpersInUse) {
		this.helpersInUse = helpersInUse == null ? null : EnumSet.copyOf(helpersInUse);
		this.unit = unit;
		this.trees = trees;
		this.elements = elements;
		this.types = types;
		this.names = names;
		this.rootPackage = options.rootPackage();
		this.plugin = options.plugin();
		this.positions = trees.getSourcePositions();
		try {
			this.source = unit.getSourceFile().getCharContent(true);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		this.comments = CommentIndex.scan(source);
	}

	/** The errors and warnings found while lowering, in source order. */
	List<Diagnostic> diagnostics() {
		return diagnostics.stream()
				.sorted(Comparator.comparingLong(Diagnostic::line).thenComparingLong(Diagnostic::column))
				.toList();
	}

	Set<Helper> helpers() {
		return helpers;
	}

	/** The Lua globals the module reads: the first name of each API path, such as {@code ui} for {@code ui.Text}. */
	Set<String> globals() {
		return globals;
	}

	/** The module, or null when the file has no Lua of its own or could not be lowered; see {@link #diagnostics()}. */
	@Nullable LuaModule lower() {
		List<ClassTree> classes = unit.getTypeDecls().stream()
				.filter(ClassTree.class::isInstance)
				.map(ClassTree.class::cast)
				.toList();
		if (classes.isEmpty()) {
			return null;
		}
		if (classes.size() > 1) {
			error(start(classes.get(1)), "Lua has one module per file; move " + classes.get(1).getSimpleName()
					+ " into a file of its own.");
			return null;
		}
		ClassTree type = classes.getFirst();
		TypeElement typeElement = (TypeElement) element(type);
		topLevel = typeElement;
		collectFileTypes(type);
		inferNonNegativeFields(type);
		String packageName = packageName(typeElement);
		if (!ModulePaths.isInside(rootPackage, packageName)) {
			error(start(type), typeElement.getQualifiedName() + " is outside the plugin's root package " + rootPackage
					+ "; move it below " + rootPackage + ".");
			return null;
		}
		if (types.declaredModule(typeElement) != null) {
			checkDeclaration(type);
			return null;
		}
		if (types.isApi(typeElement)) {
			// The plugin's generated Resources and Urls stand for tables the client provides
			return null;
		}
		moduleName = type.getSimpleName().toString();
		if (types.configGroup(typeElement) != null) {
			return lowerConfig(type, typeElement,
					ModulePaths.requirePath(sourceFolder(), rootPackage, packageName, moduleName) + ".lua");
		}
		switch (type.getKind()) {
			case RECORD -> {
				if (!types.isObjectRecord(typeElement)) {
					checkRecord(type);
					return null;
				}
			}
			case INTERFACE -> {
				if (!hasLua(type)) {
					checkInterface(type);
					return null;
				}
			}
			case CLASS, ENUM -> {
			}
			default -> {
				reject(Rejection.notYet(type, "This kind of type"));
				return null;
			}
		}
		boolean isEntry = types.isPluginEntry(typeElement) || types.isDevEntry(typeElement);
		if (isEntry) {
			entry = typeElement;
			checkEntry(type, typeElement);
		}
		// The dev source set's DevPlugin class is the development folder's dev/main.lua, which its main.lua requires
		String path = types.isPluginEntry(typeElement) ? "main.lua"
				: types.isDevEntry(typeElement) ? "dev/main.lua"
				: ModulePaths.requirePath(sourceFolder(), rootPackage, packageName, moduleName) + ".lua";

		List<Comment> header = header(type);
		@Nullable Expression table;
		boolean instanceClass = false;
		List<Member> members = new ArrayList<>();
		try {
			if (type.getKind() == Tree.Kind.ENUM && !types.isObjectEnum(typeElement.asType())) {
				table = enumTable(type);
			} else {
				checkHeritage(type);
				// An object enum's constants are its instances; an object record's values are; an interface has none
				instanceClass = !isEntry && type.getKind() != Tree.Kind.INTERFACE
						&& (type.getKind() == Tree.Kind.ENUM || type.getKind() == Tree.Kind.RECORD || isInstanceClass(type));
				// The plugin class is a singleton whose members are locals of main.lua, so it has no table
				table = isEntry ? null : classTable(typeElement);
				List<Subscription> subscriptions = isEntry ? subscriptions(type) : List.of();
				List<Member> own = classMembers(type, typeElement, moduleName, instanceClass, members);
				members.addAll(own);
				if (isEntry) {
					members.addAll(entryLifecycle(type, subscriptions));
				} else {
					checkNoSubscribers(type);
				}
			}
		} catch (Rejection rejection) {
			reject(rejection);
			return null;
		}
		checkLocals(type);
		List<Block.Entry> body = assemble(table, instanceClass, members);
		for (CommentIndex.SourceComment comment : comments.unclaimed()) {
			warning(comment.start(), "This comment could not be placed in the Lua and was left out; move it onto its"
					+ " own line above a statement.");
		}
		if (diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == Diagnostic.Severity.ERROR)) {
			return null;
		}
		return new LuaModule(path, new Chunk(header, new Block(body, List.of())));
	}

	private void collectFileTypes(ClassTree type) {
		new TreeScanner<Void, Void>() {
			@Override
			public Void visitClass(ClassTree node, Void unused) {
				if (trees.getElement(path(node)) instanceof TypeElement element) {
					fileTypes.add(element);
				}
				return super.visitClass(node, null);
			}
		}.scan(type, null);
	}

	// ---- Header and shape

	/** The comments before the class and its Javadoc description: the header every Lua file starts with. */
	private List<Comment> header(ClassTree type) {
		List<Comment> header = new ArrayList<>(BodyLowering.comments(comments.take(0, start(type))));
		DocCommentTree doc = trees.getDocCommentTree(path(type));
		if (doc != null) {
			if (!header.isEmpty()) {
				header.add(Comment.line(""));
			}
			DocText.lines(doc.getFullBody()).forEach(line -> header.add(Comment.line(line)));
		}
		if (header.isEmpty()) {
			warning(start(type), type.getSimpleName() + " has no Javadoc, but every Lua file starts with a comment"
					+ " that says what it does; add a Javadoc comment to the class.");
		}
		return header;
	}

	/**
	 * Whether the class has instances, and so becomes the {@code setmetatable} class pattern rather than a plain
	 * module: a class that is abstract or extends another, any instance field or method, or a constructor other than
	 * the private empty one that only stops a utility class from being instantiated.
	 */
	private boolean isInstanceClass(ClassTree type) {
		if (type.getExtendsClause() != null || type.getModifiers().getFlags().contains(Modifier.ABSTRACT)) {
			return true;
		}
		for (Tree member : type.getMembers()) {
			if (isSynthetic(member)) {
				continue;
			}
			switch (member) {
				case VariableTree field when !isStatic(field.getModifiers().getFlags()) -> {
					return true;
				}
				case BlockTree block when !block.isStatic() -> {
					return true;
				}
				case MethodTree method when isConstructor(method) -> {
					boolean utilityGuard = method.getModifiers().getFlags().contains(Modifier.PRIVATE)
							&& method.getParameters().isEmpty()
							&& realStatements(method.getBody()).isEmpty();
					if (!utilityGuard) {
						return true;
					}
				}
				case MethodTree method when !isStatic(method.getModifiers().getFlags()) -> {
					return true;
				}
				default -> {
				}
			}
		}
		return false;
	}

	/** A class may extend a class of the plugin's and implement the plugin's interfaces; library types have no Lua. */
	private void checkHeritage(ClassTree type) {
		Tree superclass = type.getExtendsClause();
		TypeElement own = (TypeElement) element(type);
		boolean isEntry = types.isPluginEntry(own) || types.isDevEntry(own);
		if (isEntry && !own.equals(topLevel)) {
			throw new Rejection(type, "The plugin class becomes main.lua; declare it as a class of its own file.");
		}
		if (superclass != null && !isEntry) {
			TypeElement element = typeElement(superclass);
			if (types.isApi(element)) {
				throw new Rejection(superclass, element.getSimpleName() + " stands for Lua the client provides, which a"
						+ " class of the plugin's cannot extend.");
			}
			if (!types.isPluginType(element)) {
				throw new Rejection(superclass, "Lua has none of " + element.getSimpleName() + "'s Java code to inherit;"
						+ " keep one in a field instead of extending it.");
			}
			if (types.declaredModule(element) != null) {
				throw new Rejection(superclass, "A @LuaModule declaration stands for a hand-written module, which Java"
						+ " code cannot extend.");
			}
		}
		for (Tree implemented : type.getImplementsClause()) {
			String name = typeElement(implemented).getQualifiedName().toString();
			// Any other library interface is a type only, as the plugin's own abstract ones are
			if (name.equals("java.lang.Iterable") || name.equals("java.util.Iterator")) {
				throw new Rejection(implemented, "Lua's for loop walks tables, not iterator objects; give the class a"
						+ " method that returns its list, and loop over that.");
			}
		}
	}

	/** The class of the plugin's that a class extends, or null when it extends {@code Object}. */
	@Nullable TypeElement pluginSuperclass(TypeElement type) {
		if (type.getSuperclass() instanceof DeclaredType declared && declared.asElement() instanceof TypeElement superclass
				&& types.isPluginType(superclass)) {
			return superclass;
		}
		return null;
	}

	/**
	 * A class declared inside a method, as statements of that function: its table, then its members, which read the
	 * method's locals as upvalues. Its methods are functions of the table, so a private one, which Lua would need
	 * declared before the methods that call it, is rejected.
	 */
	List<Statement> localClass(ClassTree type) {
		TypeElement element = (TypeElement) element(type);
		if (type.getKind() != Tree.Kind.CLASS) {
			throw new Rejection(type, "Lua keeps one table per name in a function; declare this " + type.getKind()
					.toString().toLowerCase(Locale.ROOT) + " beside the class, not inside a method.");
		}
		for (Tree member : type.getMembers()) {
			if ((member instanceof MethodTree method && method.getModifiers().getFlags().contains(Modifier.PRIVATE))
					|| member instanceof ClassTree) {
				throw new Rejection(member, "A class inside a method is a local table whose members are its fields; make"
						+ " this a member the class's methods call on self, or move the class beside its outer one.");
			}
		}
		checkHeritage(type);
		boolean instanceClass = isInstanceClass(type);
		String table = tableName(element);
		List<Statement> lowered = new ArrayList<>();
		lowered.add(new Statement.Local(List.of(table), List.of(classTable(element))));
		if (instanceClass) {
			lowered.add(new Statement.Assignment(List.of(field(name(table), "__index")), List.of(name(table))));
		}
		List<Member> nested = new ArrayList<>();
		List<Member> own = classMembers(type, element, table, instanceClass, nested);
		for (Member member : own) {
			member.entries().forEach(entry -> lowered.add(entry.statement()));
		}
		return lowered;
	}

	/** A class's table: {@code {}}, or for a subclass a table that looks up what it lacks in its superclass. */
	private Expression classTable(TypeElement type) {
		TypeElement superclass = pluginSuperclass(type);
		if (superclass == null) {
			return new Expression.Table(List.of());
		}
		return call(name("setmetatable"), new Expression.Table(List.of()), new Expression.Table(
				List.of(new Expression.TableField.Named("__index", moduleReference(superclass)))));
	}

	/**
	 * A simple enum as a table of its constants, each the string of its own name, {@code { FAST = "FAST" }}, with each
	 * constant's comments above it. An enum with anything more is an object enum, lowered as a class.
	 */
	private Expression enumTable(ClassTree type) {
		List<Expression.TableField> fields = new ArrayList<>();
		int cursor = start(type);
		for (Tree member : type.getMembers()) {
			if (isSynthetic(member)) {
				continue;
			}
			// A simple enum declares only constants
			VariableTree constant = (VariableTree) member;
			List<Comment> lines = new ArrayList<>(BodyLowering.comments(comments.take(cursor, start(constant))));
			DocCommentTree doc = trees.getDocCommentTree(path(constant));
			if (doc != null) {
				DocText.lines(doc.getFullBody()).forEach(line -> lines.add(Comment.line(line)));
			}
			cursor = end(constant);
			// A comment after the constant on its line moves above it, because Lua's comma would come before it
			CommentIndex.SourceComment trailing = comments.takeTrailing(cursor, ",");
			if (trailing != null) {
				cursor = trailing.end();
				trailing.lines().forEach(line -> lines.add(Comment.line(line)));
			}
			String luaName = luaMemberName(constant);
			Expression.TableField field = new Expression.TableField.Named(luaName, Expression.string(luaName));
			fields.add(lines.isEmpty() ? field : new Expression.TableField.Commented(lines, field));
		}
		return new Expression.Table(fields);
	}

	// ---- Members

	/**
	 * One piece of the module: its statements, with the comments and blank line before them; for a local, the form
	 * that assigns it when it was declared ahead; and what decides its place.
	 *
	 * @param elements the Java declarations it stands for, by which other members refer to it
	 * @param function whether the member defines a function, whose body only runs when called
	 * @param localName the Lua local the member defines, or null for a table field or loose statements
	 * @param assignForm the member's statements when its local was declared ahead
	 * @param references the declarations of this file that the member's code uses
	 * @param after the declarations whose members must come first, such as the table of the member's own class
	 */
	private record Member(List<Element> elements, boolean function, @Nullable String localName,
			List<Block.Entry> entries, List<Block.Entry> assignForm, Set<Element> references, Set<Element> after) {
	}

	/**
	 * A default chain: overloads where each shorter one only calls the next longer one, passing its own parameters
	 * first and constants for the rest. Lua keeps the longest, which fills in what the shorter ones would have passed.
	 *
	 * @param defaults the value each filled-in parameter of the longest gets, by position; a {@code null} literal
	 *     leaves the parameter nil
	 */
	private record DefaultChain(MethodTree longest, List<MethodTree> shorter, Map<Integer, ExpressionTree> defaults) {
	}

	/**
	 * The default chains among a class's methods and constructors.
	 *
	 * @param chains each chain by its longest overload
	 * @param longestOf the longest overload of each shorter one, which Lua merges into it
	 */
	private record Overloads(Map<MethodTree, DefaultChain> chains, Map<MethodTree, MethodTree> longestOf) {

		@Nullable DefaultChain chain(@Nullable MethodTree longest) {
			return longest == null ? null : chains.get(longest);
		}
	}

	/**
	 * One class's members as Lua, in source order, with {@code Class.new} where the constructor or the first instance
	 * field is. The members of nested classes and enums go to {@code nested}, because Lua must define those tables
	 * before the code that uses them.
	 */
	private List<Member> classMembers(ClassTree type, TypeElement owner, String table, boolean instanceClass,
			List<Member> nested) {
		Overloads overloads = checkNames(type);
		List<Member> members = new ArrayList<>();
		// The comments above shorter overloads, which go above the longest one, and where each method's member is
		Map<MethodTree, List<Comment>> mergedComments = new HashMap<>();
		Map<MethodTree, Integer> memberAt = new HashMap<>();
		// The instance fields and initializer blocks, which the constructor runs in the order Java does
		List<Tree> instanceFields = new ArrayList<>();
		List<Trivia> instanceFieldTrivia = new ArrayList<>();
		MethodTree constructor = null;
		Trivia constructorTrivia = Trivia.NONE;
		// Where Class.new goes among the members: at the constructor, or else at the first instance field
		int constructorSlot = -1;
		// Without a constructor or an instance field, the implicit constructor goes where Java's would: before the methods
		int firstMethodSlot = -1;
		int cursor = start(type);
		// The blank line above a member that has no Lua, such as a field of settings, goes to the next one
		boolean carriedBlankLine = false;
		int ordinal = 0;
		for (Tree member : type.getMembers()) {
			if (isSynthetic(member)) {
				continue;
			}
			List<CommentIndex.SourceComment> leading = comments.take(cursor, start(member));
			int firstStart = leading.isEmpty() ? start(member) : leading.getFirst().start();
			boolean blankLine = comments.hasBlankLine(cursor, firstStart) || carriedBlankLine;
			carriedBlankLine = false;
			cursor = end(member);
			String trailing = null;
			boolean constant = member instanceof VariableTree variable
					&& element(variable).getKind() == ElementKind.ENUM_CONSTANT;
			if (member instanceof VariableTree) {
				// A comment after an enum constant follows its comma
				CommentIndex.SourceComment trailingComment = constant
						? comments.takeTrailing(cursor, ",")
						: comments.takeTrailing(cursor);
				if (trailingComment != null) {
					cursor = trailingComment.end();
					trailing = String.join(" ", trailingComment.lines());
				}
			}
			try {
				switch (member) {
					case VariableTree field when types.configType(type(field)) != null -> {
						if (field.getInitializer() != null) {
							throw new Rejection(field.getInitializer(), "A field of settings needs no value: every call"
									+ " through it calls the settings module, so remove the initializer.");
						}
						carriedBlankLine = blankLine;
						dropComments(leading, "This comment is on a field of settings, which has no Lua of its own, so"
								+ " it was left out.");
					}
					case VariableTree field when constant -> members.addAll(enumConstant(field, owner, table,
							new Trivia(blankLine, fieldComments(leading, field), trailing), ordinal++));
					case VariableTree field when owner.equals(entry) ->
							members.add(staticField(field, owner, table, new Trivia(blankLine, fieldComments(leading, field),
									trailing)));
					case MethodTree method when owner.equals(entry) && isLifecycle(method) ->
							lifecycleComments.put(method.getName().toString(), leading);
					case MethodTree method when owner.equals(entry) && isConstructor(method) -> {
						if (!method.getParameters().isEmpty() || !realStatements(method.getBody()).isEmpty()) {
							throw new Rejection(method, "The client creates the plugin class, and main.lua runs once;"
									+ " put start-up code in startUp() instead of a constructor.");
						}
					}
					// An interface's fields are constants, static without saying so
					case VariableTree field when !isStatic(field.getModifiers().getFlags())
							&& owner.getKind() != ElementKind.INTERFACE -> {
						// The first field follows local self = setmetatable(...) directly, without a blank line
						boolean blankBefore = blankLine && !instanceFields.isEmpty();
						instanceFields.add(field);
						instanceFieldTrivia.add(new Trivia(blankBefore, fieldComments(leading, field), trailing));
						if (constructorSlot < 0) {
							constructorSlot = members.size();
						}
					}
					case VariableTree field -> members.add(staticField(field, owner, table,
							new Trivia(blankLine, fieldComments(leading, field), trailing)));
					case BlockTree block when block.isStatic() ->
							members.add(initializer(block, owner, blankLine, leading));
					case BlockTree block -> {
						instanceFields.add(block);
						instanceFieldTrivia.add(new Trivia(blankLine && instanceFields.size() > 1,
								BodyLowering.comments(leading), null));
						if (constructorSlot < 0) {
							constructorSlot = members.size();
						}
					}
					case MethodTree method when overloads.longestOf().containsKey(method) -> mergedComments
							.computeIfAbsent(overloads.longestOf().get(method), _ -> new ArrayList<>())
							.addAll(BodyLowering.comments(leading));
					case MethodTree method when isConstructor(method) -> {
						if (!instanceClass) {
							continue;
						}
						constructor = method;
						constructorTrivia = withComments(memberTrivia(true, leading, method, overloads.chain(method)),
								mergedComments.remove(method));
						constructorSlot = members.size();
					}
					case MethodTree method when method.getBody() == null
							&& (method.getModifiers().getFlags().contains(Modifier.ABSTRACT)
									|| owner.getKind() == ElementKind.INTERFACE) -> dropComments(leading,
							"This comment is on an abstract method, which has no Lua of its own, so it was left out.");
					case MethodTree method -> {
						Trivia trivia = withComments(memberTrivia(true, leading, method, overloads.chain(method)),
								mergedComments.remove(method));
						if (firstMethodSlot < 0) {
							firstMethodSlot = members.size();
						}
						memberAt.put(method, members.size());
						members.add(method(method, owner, table, trivia, overloads.chain(method)));
					}
					case ClassTree inner -> nestedType(inner, owner, table, typeTrivia(blankLine, leading, inner), nested);
					default -> reject(Rejection.notYet(member, "This kind of class member"));
				}
			} catch (Rejection rejection) {
				reject(rejection);
			}
		}
		// Shorter overloads that came after the longest one
		for (Map.Entry<MethodTree, List<Comment>> late : mergedComments.entrySet()) {
			if (late.getKey().equals(constructor)) {
				constructorTrivia = withComments(constructorTrivia, late.getValue());
			} else {
				Integer index = memberAt.get(late.getKey());
				if (index != null) {
					members.set(index, withComments(members.get(index), late.getValue()));
				}
			}
		}
		members.addAll(inheritedDefaults(owner, table));
		members.addAll(libraryMetamethods(owner, table));
		if (instanceClass) {
			try {
				Member lowered = constructor(type, owner, table, constructor, constructorTrivia, overloads.chain(constructor),
						instanceFields, instanceFieldTrivia);
				int slot = constructorSlot >= 0 ? constructorSlot : firstMethodSlot >= 0 ? firstMethodSlot : members.size();
				members.add(slot, lowered);
			} catch (Rejection rejection) {
				reject(rejection);
			}
		}
		return members;
	}

	/**
	 * The default methods a class takes from the plugin's interfaces without overriding them, which Lua copies into its
	 * table, {@code Herbal.describe = Describable.describe}; one its superclass took already comes through that.
	 */
	private List<Member> inheritedDefaults(TypeElement owner, String table) {
		if (owner.getKind() == ElementKind.INTERFACE) {
			return List.of();
		}
		TypeElement superclass = pluginSuperclass(owner);
		Set<Element> fromSuperclass = superclass == null ? Set.of() : Set.copyOf(elements.getAllMembers(superclass));
		Set<Element> members = Set.copyOf(elements.getAllMembers(owner));
		List<Member> copies = new ArrayList<>();
		for (TypeElement implemented : pluginInterfaces(owner)) {
			for (ExecutableElement method : ElementFilter.methodsIn(implemented.getEnclosedElements())) {
				if (!method.getModifiers().contains(Modifier.DEFAULT) || !members.contains(method)
						|| fromSuperclass.contains(method)) {
					continue;
				}
				String luaName = types.memberName(method);
				Statement copy = new Statement.Assignment(List.of(field(name(table), luaName)),
						List.of(field(moduleReference(implemented), luaName)));
				Set<Element> references = fileTypes.contains(implemented) ? Set.of(implemented, method) : Set.of();
				copies.add(new Member(List.of(), false, null, List.of(Block.Entry.of(copy)), List.of(), references,
						ownTable(owner)));
			}
		}
		return copies;
	}

	/** The plugin's interfaces a class implements, directly or through others, each once, nearest first. */
	private List<TypeElement> pluginInterfaces(TypeElement owner) {
		List<TypeElement> found = new ArrayList<>();
		List<TypeMirror> pending = new ArrayList<>(owner.getInterfaces());
		while (!pending.isEmpty()) {
			if (pending.removeFirst() instanceof DeclaredType declared && declared.asElement() instanceof TypeElement type
					&& types.isPluginType(type) && !found.contains(type)) {
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
	private List<Member> libraryMetamethods(TypeElement owner, String table) {
		if (owner.getKind() == ElementKind.INTERFACE) {
			return List.of();
		}
		List<Member> metamethods = new ArrayList<>();
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
			metamethods.add(new Member(List.of(), true, null, List.of(new Block.Entry(function,
					new Trivia(true, List.of(Comment.line(comment)), null), null)), List.of(), Set.of(), ownTable(owner)));
		}
		String functional = libraryFunctionalMethod(owner);
		if (functional != null) {
			Expression called = Expression.methodCall(name("self"), functional, new Expression.Vararg());
			Statement function = new Statement.FunctionDeclaration(List.of(table), "__call",
					new FunctionBody(List.of(), true, Block.of(new Statement.Return(List.of(called)))));
			metamethods.add(new Member(List.of(), true, null, List.of(new Block.Entry(function,
					new Trivia(true, List.of(Comment.line("Called as a function, as library code calls " + functional)),
							null), null)), List.of(), Set.of(), ownTable(owner)));
		}
		return metamethods;
	}

	/**
	 * The method of the functional library interface a class implements, such as accept for a Consumer; null when it
	 * has none.
	 */
	private @Nullable String libraryFunctionalMethod(TypeElement owner) {
		List<TypeMirror> pending = new ArrayList<>(owner.getInterfaces());
		TypeElement superclass = pluginSuperclass(owner);
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
				for (ExecutableElement method : ElementFilter.methodsIn(elements.getAllMembers(type))) {
					if (method.getModifiers().contains(Modifier.ABSTRACT) && !method.getSimpleName().contentEquals("equals")
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

	/** The trivia with more comment lines above the ones it has. */
	private static Trivia withComments(Trivia trivia, @Nullable List<Comment> lines) {
		if (lines == null || lines.isEmpty()) {
			return trivia;
		}
		List<Comment> leading = new ArrayList<>(lines);
		leading.addAll(trivia.leading());
		return new Trivia(trivia.blankLineBefore(), leading, trivia.trailing());
	}

	/** The member with more comment lines above its first statement. */
	private static Member withComments(Member member, List<Comment> lines) {
		return new Member(member.elements(), member.function(), member.localName(),
				withFirstComments(member.entries(), lines), withFirstComments(member.assignForm(), lines),
				member.references(), member.after());
	}

	private static List<Block.Entry> withFirstComments(List<Block.Entry> entries, List<Comment> lines) {
		if (entries.isEmpty()) {
			return entries;
		}
		List<Block.Entry> changed = new ArrayList<>(entries);
		Block.Entry first = changed.getFirst();
		changed.set(0, new Block.Entry(first.statement(), withComments(first.trivia(), lines), first.origin()));
		return changed;
	}

	/** Comments on a member without Lua of its own cannot be placed; each gets a warning saying so. */
	private void dropComments(List<CommentIndex.SourceComment> leading, String message) {
		leading.forEach(comment -> warning(comment.start(), message));
	}

	/** A field's comments: the source comments before it and its Javadoc description. */
	private List<Comment> fieldComments(List<CommentIndex.SourceComment> leading, VariableTree field) {
		List<Comment> lines = new ArrayList<>(BodyLowering.comments(leading));
		DocCommentTree doc = trees.getDocCommentTree(path(field));
		if (doc != null) {
			DocText.lines(doc.getFullBody()).forEach(line -> lines.add(Comment.line(line)));
		}
		return lines;
	}

	/** A nested type's comments: the source comments before it and its Javadoc description. */
	private Trivia typeTrivia(boolean blankLine, List<CommentIndex.SourceComment> leading, ClassTree type) {
		List<Comment> lines = new ArrayList<>(BodyLowering.comments(leading));
		DocCommentTree doc = trees.getDocCommentTree(path(type));
		if (doc != null) {
			DocText.lines(doc.getFullBody()).forEach(line -> lines.add(Comment.line(line)));
		}
		return new Trivia(blankLine, lines, null);
	}

	/**
	 * A nested type. A static class or an enum becomes a local table in this file, also a field of the outer class's
	 * table unless it is private; records and interfaces are types only.
	 */
	private void nestedType(ClassTree nested, TypeElement outer, String outerTable, Trivia trivia, List<Member> out) {
		TypeElement element = (TypeElement) element(nested);
		if (types.declaredModule(element) != null) {
			throw new Rejection(nested, "A @LuaModule declaration stands for a module file of its own; move it into a"
					+ " file of its own.");
		}
		switch (nested.getKind()) {
			case INTERFACE -> {
				if (!hasLua(nested)) {
					checkInterface(nested);
					return;
				}
				checkHeritage(nested);
				out.add(tableMember(nested, element, outer, outerTable, classTable(element), false, trivia));
				List<Member> inner = new ArrayList<>();
				List<Member> own = classMembers(nested, element, tableName(element), false, inner);
				out.addAll(inner);
				out.addAll(own);
			}
			case RECORD, ENUM -> {
				if (nested.getKind() == Tree.Kind.RECORD && !types.isObjectRecord(element)) {
					checkRecord(nested);
					return;
				}
				if (nested.getKind() == Tree.Kind.ENUM && !types.isObjectEnum(element.asType())) {
					out.add(tableMember(nested, element, outer, outerTable, enumTable(nested), false, trivia));
					return;
				}
				checkHeritage(nested);
				out.add(tableMember(nested, element, outer, outerTable, classTable(element), true, trivia));
				List<Member> inner = new ArrayList<>();
				List<Member> own = classMembers(nested, element, tableName(element), true, inner);
				out.addAll(inner);
				out.addAll(own);
			}
			case CLASS -> {
				checkHeritage(nested);
				// An inner class's objects keep their outer object, so it always has objects
				boolean instanceClass = JavaTypes.isInner(element) || isInstanceClass(nested);
				out.add(tableMember(nested, element, outer, outerTable, classTable(element), instanceClass, trivia));
				List<Member> inner = new ArrayList<>();
				List<Member> own = classMembers(nested, element, tableName(element), instanceClass, inner);
				out.addAll(inner);
				out.addAll(own);
			}
			default -> throw Rejection.notYet(nested, "This kind of nested type");
		}
	}

	/**
	 * A nested class's or enum's table as a local, {@code local Cell = {}}, with {@code Cell.__index = Cell} for a
	 * class with instances, and {@code Outer.Cell = Cell} unless it is private.
	 */
	private Member tableMember(ClassTree nested, TypeElement element, TypeElement outer, String outerTable,
			Expression value, boolean instanceClass, Trivia trivia) {
		String local = tableName(element);
		if (!LocalNames.isAscii(local) || LuaNames.isKeyword(local)) {
			throw new Rejection(nested, "Lua names are ASCII letters, digits and underscores, and not Lua keywords;"
					+ " rename " + local + ".");
		}
		SourcePosition origin = origin(nested);
		List<Block.Entry> rest = new ArrayList<>();
		if (instanceClass) {
			rest.add(new Block.Entry(new Statement.Assignment(List.of(field(name(local), "__index")),
					List.of(name(local))), Trivia.NONE, origin));
		}
		if (!nested.getModifiers().getFlags().contains(Modifier.PRIVATE) && !outer.equals(entry)) {
			rest.add(new Block.Entry(new Statement.Assignment(List.of(field(name(outerTable), local)),
					List.of(name(local))), Trivia.NONE, origin));
		}
		List<Block.Entry> entries = new ArrayList<>();
		entries.add(new Block.Entry(new Statement.Local(List.of(local), List.of(value)), trivia, origin));
		entries.addAll(rest);
		List<Block.Entry> assignForm = new ArrayList<>();
		assignForm.add(new Block.Entry(new Statement.Assignment(List.of(name(local)), List.of(value)), trivia, origin));
		assignForm.addAll(rest);
		Set<Element> after = new HashSet<>();
		if (!outer.equals(topLevel)) {
			after.add(outer);
		}
		TypeElement superclass = pluginSuperclass(element);
		if (superclass != null && fileTypes.contains(superclass)) {
			after.add(superclass);
		}
		return new Member(List.of(element), false, local, entries, assignForm, after, after);
	}

	/**
	 * Names a Lua table cannot hold apart: two methods with one name (Lua functions have no parameter types), unless
	 * they form a default chain, and a field and a method with one name (a Lua object keeps both in the same table).
	 * Private methods are locals, so only clash with each other.
	 */
	private Overloads checkNames(ClassTree type) {
		Set<String> fields = new HashSet<>();
		Map<String, List<MethodTree>> groups = new LinkedHashMap<>();
		for (Tree member : type.getMembers()) {
			if (member instanceof VariableTree field) {
				fields.add(types.memberName(element(field)));
			} else if (member instanceof MethodTree method && !isSynthetic(method)) {
				String key = isConstructor(method) ? "<init>" : types.memberName(element(method));
				groups.computeIfAbsent(key, _ -> new ArrayList<>()).add(method);
			}
		}
		if (type.getKind() == Tree.Kind.ENUM) {
			for (Tree member : type.getMembers()) {
				if (member instanceof VariableTree field && element(field).getKind() == ElementKind.FIELD
						&& (fields.contains("name") || fields.contains("ordinal"))
						&& (field.getName().contentEquals("name") || field.getName().contentEquals("ordinal"))) {
					error(start(field), "In Lua an enum constant keeps its name and ordinal in the fields name and"
							+ " ordinal; rename the field " + field.getName() + ", for example to label.");
				}
			}
		}
		Map<MethodTree, DefaultChain> chains = new HashMap<>();
		Map<MethodTree, MethodTree> longestOf = new HashMap<>();
		groups.forEach((name, methods) -> {
			MethodTree first = methods.getFirst();
			boolean constructors = isConstructor(first);
			if (methods.size() > 1) {
				DefaultChain chain = defaultChain(methods);
				if (chain == null) {
					for (MethodTree method : methods.subList(1, methods.size())) {
						error(start(method), constructors
								? "Lua functions have no parameter types, so a class has one constructor; turn the"
										+ " others into static methods with names of their own, or make the shorter ones"
										+ " a default chain (see docs/java-subset.md)."
								: "Lua functions have no parameter types, so two methods cannot share a name; rename one"
										+ " (for example " + name + "All, " + name + "One), or make the shorter ones a"
										+ " default chain (see docs/java-subset.md).");
					}
					return;
				}
				chains.put(chain.longest(), chain);
				chain.shorter().forEach(shorter -> longestOf.put(shorter, chain.longest()));
			}
			if (!constructors && fields.contains(name) && !first.getModifiers().getFlags().contains(Modifier.PRIVATE)) {
				error(start(first), "The field " + name + " and the method " + name + "() would share one slot in the"
						+ " Lua table; rename one.");
			}
		});
		return new Overloads(chains, longestOf);
	}

	/** The overloads as a default chain, or null when they are not one. */
	private @Nullable DefaultChain defaultChain(List<MethodTree> methods) {
		List<MethodTree> sorted = methods.stream()
				.sorted(Comparator.comparingInt(method -> method.getParameters().size()))
				.toList();
		MethodTree longest = sorted.getLast();
		Set<Modifier> longestFlags = longest.getModifiers().getFlags();
		Map<Integer, ExpressionTree> defaults = new HashMap<>();
		for (int index = 0; index + 1 < sorted.size(); index++) {
			MethodTree shorter = sorted.get(index);
			MethodTree longer = sorted.get(index + 1);
			Set<Modifier> flags = shorter.getModifiers().getFlags();
			if (shorter.getParameters().size() == longer.getParameters().size()
					|| isStatic(flags) != isStatic(longestFlags)
					|| flags.contains(Modifier.PRIVATE) != longestFlags.contains(Modifier.PRIVATE)) {
				return null;
			}
			List<? extends ExpressionTree> arguments = delegation(shorter, longer);
			if (arguments == null) {
				return null;
			}
			for (int position = 0; position < arguments.size(); position++) {
				ExpressionTree argument = arguments.get(position);
				if (position < shorter.getParameters().size()) {
					if (!(argument instanceof IdentifierTree passed)
							|| !element(passed).equals(element(shorter.getParameters().get(position)))) {
						return null;
					}
					continue;
				}
				boolean isNull = argument instanceof LiteralTree literal
						&& literal.getKind() == Tree.Kind.NULL_LITERAL;
				// A filled-in parameter must not accept null, because a nil passed for it would get the default
				if (!isConstant(argument) || (!isNull && types.isNullable(type(longest.getParameters().get(position))))) {
					return null;
				}
				defaults.put(position, argument);
			}
		}
		return new DefaultChain(longest, sorted.subList(0, sorted.size() - 1), defaults);
	}

	/** The arguments of the one call a shorter overload makes to the next longer one, or null when it does more. */
	private @Nullable List<? extends ExpressionTree> delegation(MethodTree shorter, MethodTree longer) {
		BlockTree body = shorter.getBody();
		if (body == null || body.getStatements().size() != 1) {
			return null;
		}
		ExpressionTree value = switch (body.getStatements().getFirst()) {
			case ExpressionStatementTree statement -> statement.getExpression();
			case ReturnTree returned -> returned.getExpression();
			default -> null;
		};
		if (!(value instanceof MethodInvocationTree call) || !element(longer).equals(trees.getElement(path(call)))) {
			return null;
		}
		boolean ownReceiver = switch (call.getMethodSelect()) {
			case IdentifierTree _ -> true;
			case MemberSelectTree select -> (select.getExpression() instanceof IdentifierTree receiver
					&& (receiver.getName().contentEquals("this")
							|| JavaTypes.owner(element(longer)).equals(trees.getElement(path(receiver)))));
			default -> false;
		};
		return ownReceiver ? call.getArguments() : null;
	}

	/** Whether a default argument is a constant: a literal, a negated number, an enum constant or a static final field. */
	private boolean isConstant(ExpressionTree argument) {
		return switch (argument) {
			case LiteralTree _ -> true;
			case UnaryTree negation when negation.getKind() == Tree.Kind.UNARY_MINUS ->
					negation.getExpression() instanceof LiteralTree;
			case ParenthesizedTree parenthesized -> isConstant(parenthesized.getExpression());
			case IdentifierTree _, MemberSelectTree _ -> trees.getElement(path(argument)) instanceof VariableElement variable
					&& (variable.getKind() == ElementKind.ENUM_CONSTANT
							|| variable.getModifiers().containsAll(Set.of(Modifier.STATIC, Modifier.FINAL)));
			default -> false;
		};
	}

	/**
	 * What a default chain's shorter overloads would have passed, filled in at the start of the longest:
	 * {@code n = n or 1}, or for a boolean, which {@code or} would get wrong, {@code if flag == nil then ... end}.
	 */
	private List<Block.Entry> defaultFills(@Nullable MethodTree method, @Nullable DefaultChain chain,
			BodyLowering lowering) {
		if (method == null || chain == null) {
			return List.of();
		}
		List<Block.Entry> fills = new ArrayList<>();
		for (Map.Entry<Integer, ExpressionTree> filled : new TreeMap<>(chain.defaults()).entrySet()) {
			ExpressionTree value = filled.getValue();
			if (value instanceof LiteralTree literal && literal.getKind() == Tree.Kind.NULL_LITERAL) {
				continue;
			}
			VariableTree parameter = method.getParameters().get(filled.getKey());
			Expression local = name(names.luaName(parameter.getName().toString()));
			Expression lowered = lowering.expression(value);
			Statement fill = types.isBoolean(type(parameter))
					? new Statement.If(List.of(new Statement.If.Branch(
							binary(local, BinaryOperator.EQUAL, new Expression.Nil()),
							Block.of(new Statement.Assignment(List.of(local), List.of(lowered))))), null)
					: new Statement.Assignment(List.of(local), List.of(binary(local, BinaryOperator.OR, lowered)));
			fills.add(new Block.Entry(fill, Trivia.NONE, origin(value)));
		}
		return fills;
	}

	/** The declarations a default chain's filled-in values refer to, and the overloads Lua merges into the longest. */
	private void addChain(@Nullable DefaultChain chain, List<Element> declared, Set<Element> references) {
		if (chain == null) {
			return;
		}
		chain.shorter().forEach(method -> declared.add(element(method)));
		chain.defaults().values().forEach(value -> references.addAll(references(value)));
	}

	private Member staticField(VariableTree field, TypeElement owner, String table, Trivia trivia) {
		BodyLowering lowering = new BodyLowering(this, false, false);
		Element element = element(field);
		ExpressionTree initializer = field.getInitializer();
		Expression value = initializer == null ? defaultValue(type(field)) : lowering.expression(initializer);
		Set<Modifier> flags = field.getModifiers().getFlags();
		Set<Element> references = references(field);
		Set<Element> after = ownTable(owner);
		if (flags.contains(Modifier.PRIVATE) || owner.equals(entry)) {
			String local = lowering.declare(field);
			List<Expression> values = value == null ? List.of() : List.of(value);
			Block.Entry declaration = new Block.Entry(new Statement.Local(List.of(local), values), trivia, origin(field));
			List<Block.Entry> assignment = value == null ? List.of() : List.of(new Block.Entry(
					new Statement.Assignment(List.of(name(local)), List.of(value)), trivia, origin(field)));
			return new Member(List.of(element), false, local, List.of(declaration), assignment, references, after);
		}
		if (value == null) {
			return new Member(List.of(element), false, null, List.of(), List.of(), references, after);
		}
		Statement assignment = new Statement.Assignment(
				List.of(field(name(table), luaMemberName(field))), List.of(value));
		return new Member(List.of(element), false, null, List.of(new Block.Entry(assignment, trivia, origin(field))),
				List.of(), references, after);
	}

	/**
	 * An object enum's constant, {@code Speed.FAST = Speed.new("FAST", 1, 3)}: an instance that knows its name and
	 * ordinal. The methods of a constant's body replace the enum's on that instance only,
	 * {@code function Speed.FAST:describe() ... end}.
	 */
	private List<Member> enumConstant(VariableTree constant, TypeElement owner, String table, Trivia trivia,
			int ordinal) {
		NewClassTree creation = (NewClassTree) constant.getInitializer();
		if (creation == null) {
			throw new IllegalStateException("an enum constant without its creation: " + constant.getName());
		}
		String luaName = luaMemberName(constant);
		BodyLowering lowering = new BodyLowering(this, false, false);
		List<Expression> arguments = new ArrayList<>(List.of(Expression.string(constant.getName().toString()),
				Expression.integer(ordinal)));
		arguments.addAll(lowering.arguments(creation.getArguments()));
		Statement assignment = new Statement.Assignment(List.of(field(name(table), luaName)),
				List.of(new Expression.Call(field(name(table), "new"), arguments)));
		Element element = element(constant);
		Set<Element> references = new HashSet<>(references(creation));
		List<Member> members = new ArrayList<>();
		members.add(new Member(List.of(element), false, null,
				List.of(new Block.Entry(assignment, trivia, origin(constant))), List.of(), references, ownTable(owner)));
		ClassTree body = creation.getClassBody();
		if (body != null) {
			for (Tree member : body.getMembers()) {
				if (isSynthetic(member)) {
					continue;
				}
				if (member instanceof VariableTree field) {
					// A field of the constant's own: Herb.RANARR.bonus = 5, which its methods read as self.bonus
					ExpressionTree initializer = field.getInitializer();
					Expression value = initializer == null ? defaultValue(type(field)) : lowering.expression(initializer);
					if (value != null) {
						List<Block.Entry> entries = new ArrayList<>(members.getFirst().entries());
						entries.add(new Block.Entry(new Statement.Assignment(List.of(field(field(name(table), luaName),
								luaMemberName(field))), List.of(value)), Trivia.NONE, origin(field)));
						Member constantMember = members.getFirst();
						Set<Element> withField = new HashSet<>(constantMember.references());
						withField.addAll(references(field));
						members.set(0, new Member(constantMember.elements(), false, null, entries, List.of(), withField,
								constantMember.after()));
					}
					continue;
				}
				if (!(member instanceof MethodTree method) || method.getModifiers().getFlags().contains(Modifier.PRIVATE)) {
					throw new Rejection(member, "A private method of an enum constant's body would be a local of the whole"
							+ " file in Lua, which another constant's could hide; make it a method of the enum.");
				}
				Member override = method(method, owner, List.of(table, luaName), memberTrivia(true,
						comments.take(start(member), start(member)), method, null), null);
				Set<Element> after = new HashSet<>(override.after());
				after.add(element);
				members.add(new Member(override.elements(), true, null, override.entries(), List.of(),
						override.references(), after));
			}
		}
		return members;
	}

	/** The table of a nested class, which its members are assigned into; the top-level class's table comes first. */
	private Set<Element> ownTable(TypeElement owner) {
		return owner.equals(topLevel) ? Set.of() : Set.of(owner);
	}

	/**
	 * Java's default for a field without an initializer: {@code 0}, {@code 0.0}, {@code false}, the char
	 * {@code "\0"}, or nothing.
	 */
	@Nullable Expression defaultValue(TypeMirror type) {
		if (types.isChar(type) && type.getKind().isPrimitive()) {
			return Expression.string("\0");
		}
		if (type.getKind() == TypeKind.INT || type.getKind() == TypeKind.LONG || type.getKind() == TypeKind.SHORT
				|| type.getKind() == TypeKind.BYTE) {
			return Expression.integer(0);
		}
		if (type.getKind() == TypeKind.DOUBLE || type.getKind() == TypeKind.FLOAT) {
			return new Expression.NumberLiteral("0.0");
		}
		if (type.getKind() == TypeKind.BOOLEAN) {
			return new Expression.BooleanLiteral(false);
		}
		return null;
	}

	private Member initializer(BlockTree block, TypeElement owner, boolean blankLine,
			List<CommentIndex.SourceComment> leading) {
		Block lowered = new BodyLowering(this, false, false).block(block);
		List<Block.Entry> entries = new ArrayList<>(lowered.entries());
		if (!entries.isEmpty()) {
			Block.Entry first = entries.getFirst();
			List<Comment> lines = new ArrayList<>(BodyLowering.comments(leading));
			lines.addAll(first.trivia().leading());
			entries.set(0, new Block.Entry(first.statement(),
					new Trivia(blankLine, lines, first.trivia().trailing()), first.origin()));
		}
		return new Member(List.of(), false, null, entries, List.of(), references(block), ownTable(owner));
	}

	private Member method(MethodTree method, TypeElement owner, String table, Trivia trivia,
			@Nullable DefaultChain chain) {
		return method(method, owner, List.of(table), trivia, chain);
	}

	/** @param tablePath the table the method goes into: the class's, or an enum constant's, {@code Speed.FAST} */
	private Member method(MethodTree method, TypeElement owner, List<String> tablePath, Trivia trivia,
			@Nullable DefaultChain chain) {
		ExecutableElement element = (ExecutableElement) element(method);
		Set<Modifier> flags = method.getModifiers().getFlags();
		if (flags.contains(Modifier.NATIVE)) {
			throw new Rejection(method, "Only a @LuaModule declaration has native methods; they stand for Lua functions"
					+ " that exist already.");
		}
		BlockTree methodBody = method.getBody();
		if (methodBody == null) {
			throw new IllegalStateException("a method without a body is native or abstract: " + method.getName());
		}
		// The plugin class is a singleton: its methods are local functions without self
		boolean isStatic = isStatic(flags) || owner.equals(entry);
		boolean isPrivate = flags.contains(Modifier.PRIVATE) || owner.equals(entry);
		BodyLowering lowering = new BodyLowering(this, !isStatic, false);
		List<String> parameters = new ArrayList<>();
		if (!isStatic && isPrivate) {
			parameters.add("self");
		}
		List<Block.Entry> statements = new ArrayList<>();
		if (!parameterless.contains(element)) {
			statements.addAll(declareParameters(method.getParameters(), element.isVarArgs(), lowering, parameters));
		}
		statements.addAll(defaultFills(method, chain, lowering));
		Block lowered = lowering.block(methodBody);
		statements.addAll(lowered.entries());
		FunctionBody function = new FunctionBody(parameters, element.isVarArgs(),
				new Block(statements, lowered.closingComments()));
		List<Element> declared = new ArrayList<>(List.of(element));
		Set<Element> references = new HashSet<>(references(method));
		addChain(chain, declared, references);
		Set<Element> after = ownTable(owner);
		if (isPrivate) {
			String local = names.luaName(method.getName().toString());
			return new Member(declared, true, local,
					List.of(new Block.Entry(new Statement.LocalFunction(local, function), trivia, origin(method))),
					List.of(new Block.Entry(new Statement.FunctionDeclaration(List.of(local), null, function), trivia,
							origin(method))),
					references, after);
		}
		String luaName = luaMemberName(method);
		List<String> staticPath = new ArrayList<>(tablePath);
		staticPath.add(luaName);
		Statement declaration = isStatic
				? new Statement.FunctionDeclaration(staticPath, null, function)
				: new Statement.FunctionDeclaration(tablePath, luaName, function);
		return new Member(declared, true, null, List.of(new Block.Entry(declaration, trivia, origin(method))), List.of(),
				references, after);
	}

	/**
	 * Declares the parameters, adding their Lua names; for a varargs method the last is Lua's {@code ...}, which the
	 * function's first statement keeps in a table, {@code local values = { ... }}, returned here.
	 */
	private List<Block.Entry> declareParameters(List<? extends VariableTree> declared, boolean varargs,
			BodyLowering lowering, List<String> parameters) {
		int fixed = varargs ? declared.size() - 1 : declared.size();
		for (int index = 0; index < fixed; index++) {
			parameters.add(lowering.declare(declared.get(index)));
		}
		if (!varargs) {
			return List.of();
		}
		String values = lowering.declare(declared.getLast());
		return List.of(Block.Entry.of(new Statement.Local(List.of(values),
				List.of(new Expression.Table(List.of(new Expression.TableField.Positional(new Expression.Vararg())))))));
	}

	/**
	 * {@code Class.new}: a table with the class as its metatable (for a subclass, the superclass's object), the
	 * instance fields with their initializers or Java's defaults, then the constructor's body, and {@code self}
	 * returned.
	 */
	private Member constructor(ClassTree type, TypeElement owner, String table, @Nullable MethodTree constructor,
			Trivia trivia, @Nullable DefaultChain chain, List<Tree> fields, List<Trivia> fieldTrivia) {
		BodyLowering lowering = new BodyLowering(this, true, true);
		List<String> parameters = new ArrayList<>();
		// An object enum's constant gets its name and ordinal first, which Java's Enum constructor takes
		boolean objectEnum = owner.getKind() == ElementKind.ENUM;
		String nameParameter = objectEnum ? lowering.invent("name", type) : "";
		String ordinalParameter = objectEnum ? lowering.invent("ordinal", type) : "";
		if (objectEnum) {
			parameters.add(nameParameter);
			parameters.add(ordinalParameter);
		}
		// An inner class's object gets its outer object first, and keeps it as self.outer
		boolean inner = JavaTypes.isInner(owner);
		String outerParameter = inner ? lowering.invent("outer", type) : "";
		if (inner) {
			parameters.add(outerParameter);
		}
		// A record's implicit or compact canonical constructor takes the components and assigns them after its body
		List<String> components = new ArrayList<>();
		boolean record = owner.getKind() == ElementKind.RECORD;
		if (record && (constructor == null
				|| elements.isCompactConstructor((ExecutableElement) element(constructor)))) {
			owner.getRecordComponents().forEach(component ->
					components.add(names.luaName(component.getSimpleName().toString())));
			parameters.addAll(components);
		}
		boolean varargs = constructor != null && ((ExecutableElement) element(constructor)).isVarArgs();
		List<Block.Entry> entries = new ArrayList<>();
		if (components.isEmpty() && constructor != null) {
			entries.addAll(declareParameters(constructor.getParameters(), varargs, lowering, parameters));
		}
		entries.addAll(defaultFills(constructor, chain, lowering));
		List<StatementTree> statements = constructor == null ? List.of() : ownStatements(constructor.getBody());
		List<Expression> superArguments = List.of();
		// The statements before super(...), which run before the object exists, as Java's do
		List<StatementTree> prologue = List.of();
		int prologueEnd = 0;
		for (int index = 0; index < statements.size(); index++) {
			MethodInvocationTree superCall = superCall(statements.get(index));
			if (superCall == null) {
				continue;
			}
			prologue = statements.subList(0, index);
			prologueEnd = start(statements.get(index));
			superArguments = lowering.arguments(superCall.getArguments());
			statements = statements.subList(index + 1, statements.size());
			break;
		}
		if (constructor != null && !prologue.isEmpty()) {
			entries.addAll(lowering.statements(prologue, start(constructor.getBody()) + 1, prologueEnd).entries());
		}
		TypeElement superclass = pluginSuperclass(owner);
		Expression object = superclass == null
				? new Expression.Table(List.of())
				: new Expression.Call(field(moduleReference(superclass), "new"), superArguments);
		entries.add(Block.Entry.of(new Statement.Local(List.of("self"),
				List.of(call(name("setmetatable"), object, name(table))))));
		if (inner) {
			entries.add(Block.Entry.of(new Statement.Assignment(List.of(field(name("self"), "outer")),
					List.of(name(outerParameter)))));
		}
		if (objectEnum) {
			entries.add(Block.Entry.of(new Statement.Assignment(List.of(field(name("self"), "name")),
					List.of(name(nameParameter)))));
			entries.add(Block.Entry.of(new Statement.Assignment(List.of(field(name("self"), "ordinal")),
					List.of(name(ordinalParameter)))));
		}
		// A record's components are assigned from the constructor's parameters, so they need no defaults
		for (int index = 0; index < fields.size() && !record; index++) {
			if (fields.get(index) instanceof BlockTree block) {
				// An instance initializer block runs here, among the field initializers
				List<Block.Entry> lowered = new ArrayList<>(lowering.block(block).entries());
				if (!lowered.isEmpty()) {
					lowered.set(0, new Block.Entry(lowered.getFirst().statement(), withComments(fieldTrivia.get(index),
							lowered.getFirst().trivia().leading()), lowered.getFirst().origin()));
				}
				entries.addAll(lowered);
				continue;
			}
			VariableTree field = (VariableTree) fields.get(index);
			lowering.declare(field);
			ExpressionTree initializer = field.getInitializer();
			Expression value = initializer == null ? defaultValue(type(field)) : lowering.expression(initializer);
			if (value != null) {
				Statement assignment = new Statement.Assignment(
						List.of(field(name("self"), luaMemberName(field))), List.of(value));
				entries.add(new Block.Entry(assignment, fieldTrivia.get(index), origin(field)));
			}
		}
		List<Element> declared = new ArrayList<>(ElementFilter.constructorsIn(owner.getEnclosedElements()));
		Set<Element> references = new HashSet<>();
		SourcePosition origin = origin(type);
		if (constructor != null) {
			BlockTree body = constructor.getBody();
			Block lowered = lowering.statements(statements, prologue.isEmpty() ? start(body) + 1 : prologueEnd,
					end(body) - 1);
			entries.addAll(lowered.entries());
			references.addAll(references(constructor));
			origin = origin(constructor);
		}
		for (String component : components) {
			entries.add(Block.Entry.of(new Statement.Assignment(List.of(field(name("self"), component)),
					List.of(name(component)))));
		}
		if (!(entries.getLast().statement() instanceof Statement.Return)) {
			entries.add(Block.Entry.of(new Statement.Return(List.of(name("self")))));
		}
		fields.forEach(field -> references.addAll(references(field)));
		addChain(chain, declared, references);
		Statement declaration = new Statement.FunctionDeclaration(List.of(table, "new"), null,
				new FunctionBody(parameters, varargs, new Block(entries, List.of())));
		return new Member(declared, true, null, List.of(new Block.Entry(declaration, trivia, origin)), List.of(),
				references, ownTable(owner));
	}

	/** A constructor's statements as written, with an explicit {@code super(...)} but without the one javac adds. */
	private List<StatementTree> ownStatements(BlockTree body) {
		return body.getStatements().stream()
				.filter(statement -> !isSynthetic(statement))
				.map(StatementTree.class::cast)
				.toList();
	}

	/** A constructor's statements without the {@code super()} call javac adds or that only calls Object's. */
	private List<StatementTree> realStatements(BlockTree body) {
		return body.getStatements().stream()
				.filter(statement -> !(isSynthetic(statement) || isSuperCall(statement)))
				.map(StatementTree.class::cast)
				.toList();
	}

	private static boolean isSuperCall(StatementTree statement) {
		MethodInvocationTree call = superCall(statement);
		return call != null && call.getArguments().isEmpty();
	}

	/** The {@code super(...)} call a statement makes, or null. */
	private static @Nullable MethodInvocationTree superCall(StatementTree statement) {
		return statement instanceof ExpressionStatementTree expression
				&& expression.getExpression() instanceof MethodInvocationTree call
				&& call.getMethodSelect() instanceof IdentifierTree name
				&& name.getName().contentEquals("super") ? call : null;
	}

	/**
	 * A member's comments: the source comments before it, its Javadoc description, and its annotations. Parameters a
	 * default chain fills in are optional: a question mark follows their names.
	 */
	private Trivia memberTrivia(boolean blankLine, List<CommentIndex.SourceComment> leading, MethodTree method,
			@Nullable DefaultChain chain) {
		List<Comment> lines = new ArrayList<>(BodyLowering.comments(leading));
		DocCommentTree doc = trees.getDocCommentTree(path(method));
		if (doc != null) {
			DocText.lines(doc.getFullBody()).forEach(line -> lines.add(Comment.line(line)));
			ExecutableElement element = (ExecutableElement) element(method);
			List<? extends VariableTree> parameters = method.getParameters();
			for (int index = 0; index < parameters.size(); index++) {
				VariableTree parameter = parameters.get(index);
				TypeMirror type = type(parameter);
				String text = DocText.param(doc, parameter.getName().toString());
				String optional = chain != null && chain.defaults().containsKey(index) ? "?" : "";
				// A varargs parameter is Lua's ..., each value of the array's type
				boolean varargs = element.isVarArgs() && index == parameters.size() - 1;
				String luaName = varargs ? "..." : names.luaName(parameter.getName().toString());
				TypeMirror annotated = varargs ? ((ArrayType) type).getComponentType() : type;
				lines.add(Comment.annotation(("@param " + luaName + optional + " "
						+ types.luaType(annotated, types.isNullable(annotated)) + " " + text).stripTrailing()));
			}
			TypeMirror returnType = element.getReturnType();
			if (types.isReturnValues(returnType)) {
				// Several values: one annotation per record component, named after it
				TypeElement record = (TypeElement) ((DeclaredType) returnType).asElement();
				for (var component : record.getRecordComponents()) {
					TypeMirror componentType = component.asType();
					lines.add(Comment.annotation("@return " + types.luaType(componentType, types.isNullable(componentType))
							+ " " + component.getSimpleName()));
				}
			} else if (returnType.getKind() != TypeKind.VOID && !isConstructor(method)) {
				String luaType = types.luaType(returnType, types.isNullable(returnType));
				lines.add(Comment.annotation(returnAnnotation(luaType, DocText.returned(doc))));
			}
		}
		return new Trivia(blankLine, lines, null);
	}

	/**
	 * {@code @return boolean # text}, or with the doc-comment convention {@code @return complete: text} a named return,
	 * {@code @return boolean complete text}.
	 */
	private static String returnAnnotation(String luaType, String text) {
		Matcher named = NAMED_RETURN.matcher(text);
		if (named.matches()) {
			return "@return " + luaType + " " + named.group(1) + " " + named.group(2);
		}
		return "@return " + luaType + (text.isEmpty() ? "" : " # " + text);
	}

	// ---- The plugin class

	/** A {@code @Subscribe} method of the plugin class: the event it subscribes to and its local function. */
	private record Subscription(MethodTree method, String event, String local) {
	}

	/** Whether a method of the plugin class is {@code startUp()} or {@code shutDown()}, the lifecycle it overrides. */
	private static boolean isLifecycle(MethodTree method) {
		return (method.getName().contentEquals("startUp") || method.getName().contentEquals("shutDown"))
				&& method.getParameters().isEmpty() && !isStatic(method.getModifiers().getFlags());
	}

	/** Whether a method is the plugin class's {@code startUp()} or {@code shutDown()}, which no code may call. */
	boolean isLifecycle(ExecutableElement method) {
		return entry != null && entry.equals(method.getEnclosingElement()) && method.getParameters().isEmpty()
				&& (method.getSimpleName().contentEquals("startUp") || method.getSimpleName().contentEquals("shutDown"));
	}

	/**
	 * The plugin class's {@code @Subscribe} methods, each checked: one parameter, an event's payload, which names the
	 * event; the return type its callbacks have; and the permission or manifest flag the event needs. A method whose
	 * event passes nothing gets a Lua function without parameters.
	 */
	private List<Subscription> subscriptions(ClassTree type) {
		List<Subscription> found = new ArrayList<>();
		for (Tree member : type.getMembers()) {
			if (!(member instanceof MethodTree method) || !JavaTypes.isSubscriber(element(method))) {
				continue;
			}
			ExecutableElement element = (ExecutableElement) element(method);
			try {
				if (method.getParameters().size() != 1) {
					throw new Rejection(method, "A @Subscribe method takes one parameter, the payload of the event it"
							+ " subscribes to, such as InventoryUpdatedEventPayload.");
				}
				VariableTree parameter = method.getParameters().getFirst();
				TypeMirror payloadType = type(parameter);
				TypeElement payload = payloadType instanceof DeclaredType declared
						&& declared.asElement() instanceof TypeElement named ? named : null;
				JavaTypes.Event event = payload == null ? null : types.event(payload);
				if (payload == null || event == null) {
					throw new Rejection(parameter, (payload == null ? payloadType : payload.getSimpleName()) + " is not an"
							+ " event's payload; the parameter of a @Subscribe method names the event, such as"
							+ " InventoryUpdatedEventPayload for InventoryUpdated.");
				}
				TypeKind returned = element.getReturnType().getKind();
				if (event.returnsBoolean() && returned != TypeKind.BOOLEAN) {
					throw new Rejection(method, event.name() + "'s callbacks return a boolean the client acts on; declare "
							+ method.getName() + " to return boolean.");
				}
				if (!event.returnsBoolean() && returned != TypeKind.VOID) {
					throw new Rejection(method, event.name() + "'s callbacks return nothing; declare " + method.getName()
							+ " void.");
				}
				checkEventAllowed(payload, event, method);
				if (ElementFilter.fieldsIn(payload.getEnclosedElements()).isEmpty()) {
					if (usesParameter(method, element(parameter))) {
						throw new Rejection(parameter, "The " + event.name() + " event passes nothing, so its payload has"
								+ " nothing to read; leave " + parameter.getName() + " unused.");
					}
					parameterless.add(element);
				}
				found.add(new Subscription(method, event.name(), names.luaName(method.getName().toString())));
			} catch (Rejection rejection) {
				reject(rejection);
			}
		}
		return found;
	}

	/** An event that needs a permission or a manifest flag fails to compile unless the build declares it. */
	private void checkEventAllowed(TypeElement payload, JavaTypes.Event event, Tree where) {
		String permission = types.inherited(payload, JavaTypes.LUA_PERMISSION);
		if (permission != null && !plugin.permissions().contains(permission)) {
			throw new Rejection(where, "The " + event.name() + " event needs the " + permission + " permission, which"
					+ " the manifest does not declare; add it to the build, oneiro { plugin { permissions { optional("
					+ permission + ") } } }.");
		}
		String flag = types.inherited(payload, JavaTypes.LUA_MANIFEST_FLAG);
		if (flag != null && !plugin.manifestFlags().contains(flag)) {
			String setting = flag.startsWith("has") ? Character.toLowerCase(flag.charAt(3)) + flag.substring(4) : flag;
			throw new Rejection(where, "The " + event.name() + " event fires only when the manifest sets " + flag
					+ "; set " + setting + " = true in the build's oneiro { plugin { } } block.");
		}
	}

	private boolean usesParameter(MethodTree method, Element parameter) {
		Boolean found = new TreePathScanner<Boolean, Void>() {
			@Override
			public Boolean visitIdentifier(IdentifierTree node, Void unused) {
				return parameter.equals(trees.getElement(getCurrentPath()));
			}

			@Override
			public Boolean reduce(Boolean first, Boolean second) {
				return Boolean.TRUE.equals(first) || Boolean.TRUE.equals(second);
			}
		}.scan(new TreePath(path(method), method.getBody()), null);
		return Boolean.TRUE.equals(found);
	}

	/** Only the plugin class has {@code @Subscribe} methods, because only main.lua runs when the plugin starts. */
	private void checkNoSubscribers(ClassTree type) {
		new TreeScanner<Void, Void>() {
			@Override
			public Void visitMethod(MethodTree node, Void unused) {
				if (JavaTypes.isSubscriber(element(node))) {
					error(start(node), "Only the plugin class's methods are subscribed with @Subscribe; subscribe this one"
							+ " with Event.<Name>.Subscribe(name, callback), and unsubscribe it when it is done.");
				}
				return null;
			}
		}.scan(type, null);
	}

	/**
	 * What the plugin class adds to main.lua after its members: the body of {@code startUp()}, which runs when the file
	 * loads, then a subscription for each {@code @Subscribe} method, then the global {@code PluginShutdown}, which the
	 * client calls when the plugin is disabled: it unsubscribes each method, then runs {@code shutDown()}. The dev
	 * source set's {@code DevPlugin} class does the same in its module, and its {@code PluginShutdown} runs the
	 * plugin's first.
	 */
	private List<Member> entryLifecycle(ClassTree type, List<Subscription> subscriptions) {
		List<Member> members = new ArrayList<>();
		MethodTree startUp = lifecycleMethod(type, "startUp");
		MethodTree shutDown = lifecycleMethod(type, "shutDown");
		boolean dev = entry != null && types.isDevEntry(entry);
		String prefix = plugin.id() + (dev ? ".dev." : ".");
		if (startUp != null) {
			// Start-up code stands apart from the functions above it
			List<Block.Entry> entries = new ArrayList<>(lifecycleBody(startUp).entries());
			if (!entries.isEmpty()) {
				Block.Entry first = entries.getFirst();
				entries.set(0, new Block.Entry(first.statement(),
						new Trivia(true, first.trivia().leading(), first.trivia().trailing()), first.origin()));
			}
			members.add(new Member(List.of(element(startUp)), false, null, entries, List.of(), references(startUp),
					Set.of()));
		}
		if (!subscriptions.isEmpty()) {
			List<Block.Entry> entries = new ArrayList<>();
			Set<Element> referenced = new HashSet<>();
			for (Subscription subscription : subscriptions) {
				entries.add(new Block.Entry(new Statement.CallStatement(call(
						eventMember(subscription.event(), "Subscribe"),
						Expression.string(prefix + subscription.method().getName()), name(subscription.local()))),
						new Trivia(entries.isEmpty(), List.of(), null), origin(subscription.method())));
				referenced.add(element(subscription.method()));
			}
			members.add(new Member(List.of(), false, null, entries, List.of(), referenced, Set.of()));
		}
		if (!subscriptions.isEmpty() || shutDown != null || dev) {
			List<Block.Entry> body = new ArrayList<>();
			if (dev) {
				body.add(Block.Entry.of(new Statement.If(List.of(new Statement.If.Branch(
						binary(name("pluginShutdown"), BinaryOperator.NOT_EQUAL, new Expression.Nil()),
						Block.of(new Statement.CallStatement(call(name("pluginShutdown")))))), null)));
			}
			for (Subscription subscription : subscriptions) {
				body.add(Block.Entry.of(new Statement.CallStatement(call(eventMember(subscription.event(), "Unsubscribe"),
						Expression.string(prefix + subscription.method().getName())))));
			}
			List<Comment> closing = List.of();
			Set<Element> referenced = new HashSet<>();
			if (shutDown != null) {
				Block lowered = lifecycleBody(shutDown);
				body.addAll(lowered.entries());
				closing = lowered.closingComments();
				referenced.addAll(references(shutDown));
			}
			Statement function = new Statement.FunctionDeclaration(List.of("PluginShutdown"), null,
					FunctionBody.of(List.of(), new Block(body, closing)));
			List<Block.Entry> entries = new ArrayList<>();
			if (dev) {
				entries.add(new Block.Entry(new Statement.Local(List.of("pluginShutdown"), List.of(name("PluginShutdown"))),
						new Trivia(true, List.of(Comment.line("The plugin's own shutdown, which runs first.")), null),
						null));
			}
			entries.add(new Block.Entry(function, new Trivia(!dev, List.of(Comment.line(
					"The client calls PluginShutdown when the plugin is disabled, and requires it to be global.")), null),
					shutDown == null ? null : origin(shutDown)));
			members.add(new Member(shutDown == null ? List.of() : List.of(element(shutDown)), true, null, entries,
					List.of(), referenced, Set.of()));
		}
		return members;
	}

	/** {@code Event.Name.Subscribe}, a member of an event's subscription. */
	private Expression eventMember(String event, String member) {
		useGlobal("Event");
		return field(field(name("Event"), event), member);
	}

	private static @Nullable MethodTree lifecycleMethod(ClassTree type, String name) {
		for (Tree member : type.getMembers()) {
			if (member instanceof MethodTree method && isLifecycle(method) && method.getName().contentEquals(name)) {
				return method;
			}
		}
		return null;
	}

	/** A lifecycle method's statements, with the comments above the method above them. */
	private Block lifecycleBody(MethodTree method) {
		BlockTree body = method.getBody();
		if (body == null) {
			throw new IllegalStateException("a lifecycle method without a body: " + method.getName());
		}
		Block lowered = new BodyLowering(this, false, false).block(body);
		List<Comment> above = new ArrayList<>(BodyLowering.comments(
				lifecycleComments.getOrDefault(method.getName().toString(), List.of())));
		DocCommentTree doc = trees.getDocCommentTree(path(method));
		if (doc != null) {
			DocText.lines(doc.getFullBody()).forEach(line -> above.add(Comment.line(line)));
		}
		if (above.isEmpty() || lowered.entries().isEmpty()) {
			if (!above.isEmpty()) {
				dropComments(lifecycleComments.getOrDefault(method.getName().toString(), List.of()),
						"This comment is on an empty " + method.getName() + "(), which has no Lua, so it was left out.");
			}
			return lowered;
		}
		return new Block(withFirstComments(lowered.entries(), above), lowered.closingComments());
	}

	/** Whether code in the tree is the plugin class's own, where {@code this} has no Lua object. */
	boolean isInEntry(Tree tree) {
		return entry != null && enclosingClass(tree).equals(entry);
	}

	/**
	 * A plugin has one class that extends {@code Plugin}, in the main source set, and at most one that extends
	 * {@code DevPlugin}, in the dev source set: each becomes a file of its own, {@code main.lua} or {@code dev/main.lua}.
	 */
	private void checkEntry(ClassTree type, TypeElement entryType) {
		boolean dev = types.isDevEntry(entryType);
		if (dev && !plugin.dev()) {
			error(start(type), entryType.getSimpleName() + " extends DevPlugin, whose code must never ship; move it into"
					+ " the dev source set, src/dev/java.");
		} else if (!dev && plugin.dev()) {
			error(start(type), entryType.getSimpleName() + " extends Plugin, so it becomes the main.lua that ships; keep"
					+ " it in src/main/java, and extend DevPlugin in the dev source set instead.");
		}
		List<String> others = types.otherEntries(entryType);
		if (!others.isEmpty()) {
			error(start(type), "Only one class extends " + (dev ? "DevPlugin" : "Plugin") + ", because it becomes "
					+ (dev ? "dev/main.lua" : "main.lua") + "; " + String.join(" and ", others)
					+ (others.size() == 1 ? " does too." : " do too."));
		}
	}

	/** Whether a class is the plugin class this file lowers into main.lua or the dev source set's entry. */
	boolean isEntry(TypeElement type) {
		return type.equals(entry);
	}

	/**
	 * Rejects code in another file that uses the plugin class: it becomes main.lua, which no file can require, or the
	 * dev entry, which only the development main.lua loads.
	 */
	void checkReachable(TypeElement type, Tree where) {
		if (!fileTypes.contains(type) && (types.isPluginEntry(type) || types.isDevEntry(type))) {
			throw new Rejection(where, "The plugin class becomes main.lua, which no other file can require; move what"
					+ " this code needs from " + type.getSimpleName() + " into a class of its own.");
		}
	}

	// ---- Settings

	/**
	 * A {@code @ConfigGroup} interface as a module of functions over the client's {@code PersistentDB}: a getter, a
	 * default method returning the default, reads the setting with the typed accessor of its type and gives the default
	 * where nothing is stored; a setter, an abstract method with one parameter, stores it. Each setting is kept under
	 * {@code "<group>.<keyName>"}.
	 */
	private @Nullable LuaModule lowerConfig(ClassTree type, TypeElement element, String path) {
		String group = types.configGroup(element);
		if (group == null) {
			return null;
		}
		if (type.getKind() != Tree.Kind.INTERFACE) {
			error(start(type), "@ConfigGroup marks an interface of settings; make " + type.getSimpleName()
					+ " an interface whose methods carry @ConfigItem.");
			return null;
		}
		List<Comment> header = new ArrayList<>(header(type));
		if (!header.isEmpty()) {
			header.add(Comment.line(""));
		}
		header.add(Comment.line("Each setting is kept in PersistentDB under \"" + group + ".\" and its key."));
		useGlobal("PersistentDB");
		List<Member> members = new ArrayList<>();
		int cursor = start(type);
		for (Tree member : type.getMembers()) {
			if (isSynthetic(member)) {
				continue;
			}
			List<CommentIndex.SourceComment> leading = comments.take(cursor, start(member));
			cursor = end(member);
			try {
				if (!(member instanceof MethodTree method)) {
					throw new Rejection(member, "An interface of settings holds only its settings' getters and setters,"
							+ " each with @ConfigItem.");
				}
				members.add(setting(method, group, leading));
			} catch (Rejection rejection) {
				reject(rejection);
			}
		}
		List<Block.Entry> body = assemble(new Expression.Table(List.of()), false, members);
		if (diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == Diagnostic.Severity.ERROR)) {
			return null;
		}
		return new LuaModule(path, new Chunk(header, new Block(body, List.of())));
	}

	/** One getter or setter of a setting, as a function of the settings module. */
	private Member setting(MethodTree method, String group, List<CommentIndex.SourceComment> leading) {
		ExecutableElement element = (ExecutableElement) element(method);
		JavaTypes.ConfigItem item = types.configItem(element);
		if (item == null) {
			throw new Rejection(method, "Every method of an interface of settings is a setting's getter or setter, with"
					+ " @ConfigItem(keyName = ..., name = ...).");
		}
		String key = group + "." + item.keyName();
		List<Comment> lines = new ArrayList<>(BodyLowering.comments(leading));
		lines.add(Comment.line(item.description().isEmpty() ? item.name() : item.name() + ": " + item.description()));
		DocCommentTree doc = trees.getDocCommentTree(path(method));
		if (doc != null) {
			DocText.lines(doc.getFullBody()).forEach(line -> lines.add(Comment.line(line)));
		}
		BlockTree body = method.getBody();
		BodyLowering lowering = new BodyLowering(this, false, false);
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
						+ " value, such as void setCount(int count); a getter is a default method that returns the default"
						+ " value.");
			}
			VariableTree parameter = method.getParameters().getFirst();
			Accessor accessor = accessor(type(parameter), parameter);
			String local = lowering.declare(parameter);
			lines.add(Comment.annotation("@param " + local + " " + types.luaType(type(parameter), false)));
			// An object enum's constant is kept by its name
			Expression stored = accessor.enumType() != null && types.isObjectEnum(accessor.enumType().asType())
					? field(name(local), "name")
					: name(local);
			function = FunctionBody.of(List.of(local), Block.of(new Statement.CallStatement(
					Expression.methodCall(name("PersistentDB"), "Set" + accessor.suffix(), Expression.string(key),
							stored))));
		}
		Statement declaration = new Statement.FunctionDeclaration(List.of(moduleName, types.memberName(element)), null,
				function);
		return new Member(List.of(element), true, null,
				List.of(new Block.Entry(declaration, new Trivia(true, lines, null), origin(method))), List.of(),
				references(method), Set.of());
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
			return Block.of(new Statement.Return(List.of(binary(new Expression.Index(moduleReference(enumType), stored),
					BinaryOperator.OR, fallback))));
		}
		boolean falseDefault = fallback instanceof Expression.BooleanLiteral literal && !literal.value();
		if (!accessor.suffix().equals("Bool") || falseDefault) {
			return Block.of(new Statement.Return(List.of(binary(stored, BinaryOperator.OR, fallback))));
		}
		return Block.of(
				new Statement.Local(List.of("stored"), List.of(stored)),
				new Statement.If(List.of(new Statement.If.Branch(binary(name("stored"), BinaryOperator.EQUAL,
						new Expression.Nil()), Block.of(new Statement.Return(List.of(fallback))))), null),
				new Statement.Return(List.of(name("stored"))));
	}

	// ---- Order

	/**
	 * The module's statements: the {@code require} lines, the helpers, the module table, any locals declared ahead,
	 * the members, and {@code return Module}. The plugin class's {@code main.lua} has no table and returns nothing.
	 * <p>
	 * Java defines every method before any code runs; Lua defines a function when its statement runs. So code that
	 * runs while the module loads comes after what it uses, and a local that a function refers to before the local's
	 * own definition is declared ahead, {@code local name}, and later assigned.
	 */
	private List<Block.Entry> assemble(@Nullable Expression table, boolean instanceClass, List<Member> members) {
		List<Member> ordered = order(members);
		Map<Member, Integer> position = new HashMap<>();
		for (int index = 0; index < ordered.size(); index++) {
			position.put(ordered.get(index), index);
		}
		Map<Element, Member> byElement = byElement(members);
		Set<Member> declaredAhead = new LinkedHashSet<>();
		for (Member user : ordered) {
			for (Element reference : user.references()) {
				Member used = byElement.get(reference);
				if (used != null && used.localName() != null
						&& position.getOrDefault(used, -1) > position.getOrDefault(user, -1)) {
					declaredAhead.add(used);
				}
			}
		}

		List<Block.Entry> entries = new ArrayList<>();
		requires.forEach((local, path) -> entries.add(Block.Entry.of(new Statement.Local(List.of(local),
				List.of(call(name("require"), Expression.string(path)))))));
		for (Helper helper : helpers) {
			entries.add(new Block.Entry(helper.definition(), new Trivia(!entries.isEmpty(), helper.comment(), null),
					null));
		}
		if (table != null) {
			entries.add(new Block.Entry(new Statement.Local(List.of(moduleName), List.of(table)),
					new Trivia(!entries.isEmpty(), List.of(), null), null));
		}
		if (instanceClass) {
			entries.add(Block.Entry.of(new Statement.Assignment(List.of(field(name(moduleName), "__index")),
					List.of(name(moduleName)))));
		}
		if (!declaredAhead.isEmpty()) {
			List<String> locals = declaredAhead.stream().map(Member::localName).toList();
			entries.add(new Block.Entry(new Statement.Local(locals, List.of()),
					new Trivia(!entries.isEmpty(),
							List.of(Comment.line("Declared ahead: used above their definitions below.")), null),
					null));
		}
		boolean first = true;
		// A function ends with end, which a blank line separates from whatever follows
		boolean afterFunction = false;
		for (Member member : ordered) {
			// Declared ahead: assigned here, or, for a field without a value, the declaration stands alone
			List<Block.Entry> memberEntries = declaredAhead.contains(member) ? member.assignForm() : member.entries();
			for (int index = 0; index < memberEntries.size(); index++) {
				Block.Entry entry = memberEntries.get(index);
				boolean blankLine = index == 0 && !entries.isEmpty()
						&& (first || member.function() || afterFunction || entry.trivia().blankLineBefore());
				entries.add(new Block.Entry(entry.statement(),
						new Trivia(blankLine, entry.trivia().leading(), entry.trivia().trailing()), entry.origin()));
			}
			if (!memberEntries.isEmpty()) {
				first = false;
				afterFunction = member.function();
			}
		}
		if (table != null) {
			entries.add(new Block.Entry(new Statement.Return(List.of(name(moduleName))), new Trivia(true, List.of(), null),
					null));
		}
		return entries;
	}

	private static Map<Element, Member> byElement(List<Member> members) {
		Map<Element, Member> byElement = new HashMap<>();
		members.forEach(member -> member.elements().forEach(element -> byElement.put(element, member)));
		return byElement;
	}

	/**
	 * Source order, except that a member comes after the class table its definition assigns into, and code that runs
	 * while the module loads (a field's value, an initializer) comes after everything it uses, including what the
	 * functions it calls use.
	 */
	private static List<Member> order(List<Member> members) {
		Placement placement = new Placement(byElement(members));
		members.forEach(placement::place);
		return placement.ordered;
	}

	/** The members in the order {@link #order} gives them, built one placement at a time. */
	private static final class Placement {

		private final Map<Element, Member> byElement;
		private final List<Member> ordered = new ArrayList<>();
		private final Set<Member> started = new HashSet<>();
		private final Set<Member> usesStarted = new HashSet<>();

		Placement(Map<Element, Member> byElement) {
			this.byElement = byElement;
		}

		void place(Member member) {
			// A member already placed, or one being placed further up a cycle, stays where it is
			if (!started.add(member)) {
				return;
			}
			for (Element element : member.after()) {
				Member needed = byElement.get(element);
				if (needed != null) {
					place(needed);
				}
			}
			if (!member.function()) {
				placeUses(member);
			}
			ordered.add(member);
		}

		/** Places what a member's code uses before that code runs; for a function, what it uses first, then itself. */
		private void placeUses(Member member) {
			if (!usesStarted.add(member)) {
				return;
			}
			for (Element element : member.references()) {
				Member used = byElement.get(element);
				if (used == null || used.equals(member)) {
					continue;
				}
				if (used.function()) {
					placeUses(used);
				}
				place(used);
			}
		}
	}

	/**
	 * The declarations of this file that a member's code uses: methods, fields, constructors and nested classes. Using
	 * a member of a nested class also uses that class's table.
	 */
	private Set<Element> references(Tree member) {
		Set<Element> found = new HashSet<>();
		new TreePathScanner<Void, Void>() {
			@Override
			public Void visitIdentifier(IdentifierTree node, Void unused) {
				note(trees.getElement(getCurrentPath()));
				return super.visitIdentifier(node, null);
			}

			@Override
			public Void visitMemberSelect(MemberSelectTree node, Void unused) {
				note(trees.getElement(getCurrentPath()));
				return super.visitMemberSelect(node, null);
			}

			@Override
			public Void visitNewClass(NewClassTree node, Void unused) {
				note(trees.getElement(getCurrentPath()));
				return super.visitNewClass(node, null);
			}

			private void note(@Nullable Element element) {
				if (element == null) {
					return;
				}
				if (element instanceof TypeElement type && fileTypes.contains(type)) {
					found.add(type);
				}
				if (element.getEnclosingElement() instanceof TypeElement owner && fileTypes.contains(owner)) {
					found.add(element);
					if (!owner.equals(topLevel)) {
						found.add(owner);
					}
				}
			}
		}.scan(path(member), null);
		return found;
	}

	/**
	 * The private members and nested types of every class in the file become locals of the one Lua chunk, so their
	 * names must differ; a private static field and a private method with one name clash too.
	 */
	private void checkLocals(ClassTree top) {
		Map<String, Tree> taken = new HashMap<>();
		taken.put(moduleName, top);
		new TreeScanner<Void, Void>() {
			@Override
			public Void visitClass(ClassTree node, Void unused) {
				if (node.getKind() == Tree.Kind.RECORD || node.getKind() == Tree.Kind.INTERFACE) {
					return null;
				}
				Set<String> methods = new HashSet<>();
				// Every member of the plugin class is a local of main.lua, whatever its visibility
				boolean allLocal = trees.getElement(path(node)) instanceof TypeElement type && type.equals(entry);
				for (Tree member : node.getMembers()) {
					switch (member) {
						case ClassTree nested when nested.getKind() == Tree.Kind.CLASS || nested.getKind() == Tree.Kind.ENUM ->
								claim(taken, nested.getSimpleName().toString(), nested);
						case VariableTree field when types.configType(type(field)) != null -> {
						}
						case VariableTree field when allLocal || (field.getModifiers().getFlags().contains(Modifier.PRIVATE)
								&& isStatic(field.getModifiers().getFlags())) ->
								claim(taken, names.luaName(field.getName().toString()), field);
						case MethodTree method when !isConstructor(method) && !isSynthetic(method)
								&& (allLocal ? !isLifecycle(method)
										: method.getModifiers().getFlags().contains(Modifier.PRIVATE)) -> {
							// The overloads of a default chain are one function
							if (methods.add(method.getName().toString())) {
								claim(taken, names.luaName(method.getName().toString()), method);
							}
						}
						default -> {
						}
					}
				}
				return super.visitClass(node, null);
			}

			// Only class members matter; classes inside code are rejected where they appear
			@Override
			public Void visitMethod(MethodTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitVariable(VariableTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitBlock(BlockTree node, Void unused) {
				return null;
			}
		}.scan(top, null);
	}

	private void claim(Map<String, Tree> taken, String name, Tree declaration) {
		if (taken.putIfAbsent(name, declaration) != null) {
			error(start(declaration), "In Lua, the private members and nested classes of the classes in a file are"
					+ " locals of one scope, so two of them cannot both be named " + name + "; rename one.");
		}
	}

	// ---- Services for BodyLowering

	void use(Helper helper) {
		helpers.add(helper);
	}

	/** Notes that the module reads a Lua global, so that no local hides it. */
	void useGlobal(String global) {
		globals.add(global);
	}

	/** The plugin folder this compilation's modules go to: {@code src}, or {@code dev} for the dev source set. */
	private String sourceFolder() {
		return plugin.dev() ? "dev" : "src";
	}

	/** The Lua local for a private member of a class in this file. */
	String localName(Element member) {
		return names.luaName(member.getSimpleName().toString());
	}

	/** The Lua local holding a class's table: the module table for the file's class, the class's name for a nested one. */
	String tableName(TypeElement type) {
		return type.equals(topLevel) ? moduleName : type.getSimpleName().toString();
	}

	/**
	 * The table of a plugin class: a local of this file for its own classes, another file's module, required at the
	 * top, or a field of that module for a class nested in it.
	 */
	Expression moduleReference(TypeElement type) {
		if (fileTypes.contains(type)) {
			return name(tableName(type));
		}
		Element enclosing = type.getEnclosingElement();
		if (enclosing instanceof TypeElement outer) {
			return field(moduleReference(outer), type.getSimpleName().toString());
		}
		if (!(enclosing instanceof PackageElement packageElement)) {
			throw new IllegalStateException("a plugin class outside a package: " + type);
		}
		String local = type.getSimpleName().toString();
		String declared = types.declaredModule(type);
		requires.putIfAbsent(local, declared != null
				? declared
				: ModulePaths.requirePath(types.isSourceType(type) ? sourceFolder() : "src", rootPackage,
						packageElement.getQualifiedName().toString(), local));
		return name(local);
	}

	/** A static field or method: a local when private to a class of this file, otherwise a field of its class's table. */
	Expression staticMember(TypeElement owner, Element member) {
		if (fileTypes.contains(owner) && (member.getModifiers().contains(Modifier.PRIVATE) || owner.equals(entry))) {
			return name(localName(member));
		}
		return field(moduleReference(owner), types.memberName(member));
	}

	/**
	 * Whether a method is written in the plugin's source, as opposed to one javac gives a record: its accessors,
	 * canonical constructor, equals, hashCode and toString.
	 */
	boolean declaredInSource(Element method) {
		TreePath declaration = trees.getPath(method);
		return declaration != null
				&& positions.getEndPosition(declaration.getCompilationUnit(), declaration.getLeaf()) >= 0;
	}

	/** The class an anonymous class is written in. */
	TypeElement enclosingClassOf(TypeElement anonymous) {
		TreePath declaration = trees.getPath(anonymous);
		if (declaration == null || declaration.getParentPath() == null) {
			throw new IllegalStateException("an anonymous class without its source: " + anonymous);
		}
		return enclosingClass(declaration.getParentPath().getLeaf());
	}

	/** The class whose code contains the tree. */
	TypeElement enclosingClass(Tree tree) {
		for (TreePath current = path(tree); current != null; current = current.getParentPath()) {
			if (current.getLeaf() instanceof ClassTree type) {
				return (TypeElement) element(type);
			}
		}
		throw new IllegalStateException("code outside a class: " + tree);
	}

	/** Whether a loop variable counts from 1 in Lua; see {@link OneBasedLoops}. */
	boolean countsFromOne(Element variable) {
		Set<Element> found = oneBased;
		if (found == null) {
			found = OneBasedLoops.find(this, new BodyLowering(this, false, false)::countsFromZero);
			oneBased = found;
		}
		return found.contains(variable);
	}

	/** How Java turns the expression between char and number where it stands; see {@link CharConversions}. */
	CharConversions.@Nullable Conversion charConversion(ExpressionTree expression) {
		CharConversions found = charConversions;
		if (found == null) {
			found = CharConversions.find(this);
			charConversions = found;
		}
		return found.of(expression);
	}

	/**
	 * A name for a local that the Lua needs and the Java does not have, such as a switch's selector: the preferred
	 * name, or that name with a number when the surrounding method or the file's locals already use it.
	 */
	String freshLocal(String preferred, Tree where) {
		Set<String> taken = new HashSet<>(fileLocals());
		new TreePathScanner<Void, Void>() {
			@Override
			public Void visitIdentifier(IdentifierTree node, Void unused) {
				Element element = trees.getElement(getCurrentPath());
				if (element != null && (isFileLocal(element) || isCodeLocal(element))) {
					taken.add(names.luaName(node.getName().toString()));
				}
				return super.visitIdentifier(node, null);
			}

			@Override
			public Void visitVariable(VariableTree node, Void unused) {
				taken.add(names.luaName(node.getName().toString()));
				return super.visitVariable(node, null);
			}
		}.scan(memberPath(where), null);
		String base = names.luaName(preferred);
		String candidate = base;
		for (int suffix = 2; taken.contains(candidate); suffix++) {
			candidate = base + suffix;
		}
		return candidate;
	}

	/**
	 * Whether a lambda's parameter can become a local of the function around the lambda: no other variable of the
	 * member and no local of the file has its Lua name, so the local hides nothing.
	 */
	boolean canHoist(VariableTree parameter) {
		String luaName = names.luaName(parameter.getName().toString());
		if (fileLocals().contains(luaName)) {
			return false;
		}
		int[] declarations = {0};
		new TreePathScanner<Void, Void>() {
			@Override
			public Void visitVariable(VariableTree node, Void unused) {
				if (names.luaName(node.getName().toString()).equals(luaName)) {
					declarations[0]++;
				}
				return super.visitVariable(node, null);
			}
		}.scan(memberPath(parameter), null);
		return declarations[0] == 1;
	}

	/** The path of the member, a method, field or initializer, that a tree is part of. */
	private TreePath memberPath(Tree where) {
		TreePath member = path(where);
		while (member.getParentPath() != null && !(member.getParentPath().getLeaf() instanceof ClassTree)) {
			member = member.getParentPath();
		}
		return member;
	}

	/** The names of the file's own locals: its classes, the modules it requires, private members and helpers. */
	private Set<String> fileLocals() {
		Set<String> found = fileLocals;
		if (found != null) {
			return found;
		}
		Set<String> locals = new HashSet<>();
		// The helpers a first lowering found this file uses, or, during that lowering, any helper
		Set<Helper> possibleHelpers = helpersInUse != null ? helpersInUse : EnumSet.allOf(Helper.class);
		for (Helper helper : possibleHelpers) {
			locals.add(helper.luaName());
		}
		new TreePathScanner<Void, Void>() {
			@Override
			public Void visitClass(ClassTree node, Void unused) {
				locals.add(node.getSimpleName().toString());
				return super.visitClass(node, null);
			}

			@Override
			public Void visitIdentifier(IdentifierTree node, Void unused) {
				Element element = trees.getElement(getCurrentPath());
				if (element != null && isFileLocal(element)) {
					locals.add(names.luaName(node.getName().toString()));
				}
				return super.visitIdentifier(node, null);
			}

			@Override
			public Void visitMethod(MethodTree node, Void unused) {
				if (node.getModifiers().getFlags().contains(Modifier.PRIVATE)) {
					locals.add(names.luaName(node.getName().toString()));
				}
				return super.visitMethod(node, null);
			}
		}.scan(unit, null);
		fileLocals = locals;
		return locals;
	}

	/** Whether a declaration is a local of the whole Lua file: a class's table, a private method or static field. */
	private static boolean isFileLocal(Element element) {
		return element instanceof TypeElement || (element.getModifiers().contains(Modifier.PRIVATE)
				&& (element.getKind() == ElementKind.METHOD || element.getModifiers().contains(Modifier.STATIC)));
	}

	/** Whether a declaration is a local of the code it appears in: a local variable or a parameter. */
	private static boolean isCodeLocal(Element element) {
		return switch (element.getKind()) {
			case LOCAL_VARIABLE, PARAMETER, RESOURCE_VARIABLE, EXCEPTION_PARAMETER, BINDING_VARIABLE -> true;
			default -> false;
		};
	}

	/**
	 * The Lua name of a declared method or field, its {@code @LuaName} or Java name, after checking that Lua can use
	 * it as a field name.
	 */
	private String luaMemberName(Tree declaration) {
		String luaName = types.memberName(element(declaration));
		if (!LocalNames.isAscii(luaName)) {
			throw new Rejection(declaration, "Lua names are ASCII letters, digits and underscores only; rename "
					+ luaName + ".");
		}
		if (!LuaNames.isName(luaName)) {
			throw new Rejection(declaration, luaName + " is a Lua keyword; rename it, or give it another Lua name with"
					+ " @LuaName.");
		}
		return luaName;
	}

	/**
	 * A {@code @LuaModule} declaration produces no Lua; its members only stand for what the Lua module defines: native
	 * methods, fields without values, and records, which describe the tables and the several values it returns.
	 */
	private void checkDeclaration(ClassTree type) {
		for (Tree member : type.getMembers()) {
			if (isSynthetic(member)) {
				continue;
			}
			try {
				switch (member) {
					case MethodTree method when isConstructor(method) -> {
						if (!method.getModifiers().getFlags().contains(Modifier.PRIVATE) || !method.getParameters().isEmpty()
								|| !realStatements(method.getBody()).isEmpty()) {
							error(start(method), "A @LuaModule declaration has no constructor of its own; declare the"
									+ " module's constructor as a static native method with @LuaName(\"new\").");
						}
					}
					case MethodTree method -> {
						if (!method.getModifiers().getFlags().contains(Modifier.NATIVE)) {
							error(start(method), "Every method of a @LuaModule declaration is native: it stands for a"
									+ " function the Lua module defines.");
						}
						luaMemberName(method);
					}
					case VariableTree field -> {
						if (field.getInitializer() != null) {
							error(start(field), "A @LuaModule declaration's fields take their values from the Lua"
									+ " module; remove the initializer.");
						}
						luaMemberName(field);
					}
					case ClassTree nested when nested.getKind() == Tree.Kind.RECORD -> checkRecord(nested);
					default -> reject(Rejection.notYet(member, "This member in a @LuaModule declaration"));
				}
			} catch (Rejection rejection) {
				reject(rejection);
			}
		}
	}

	/**
	 * A record becomes a plain Lua table, built where the record is constructed, so it produces no Lua of its own.
	 * Only its components carry over; methods, constructors and static members are not supported yet.
	 */
	private void checkRecord(ClassTree record) {
		for (Tree member : record.getMembers()) {
			if (isSynthetic(member)) {
				continue;
			}
			boolean component = member instanceof VariableTree field && !isStatic(field.getModifiers().getFlags());
			if (!component) {
				reject(Rejection.notYet(member, "A record with methods, constructors or static members"));
			} else {
				try {
					luaMemberName(member);
				} catch (Rejection rejection) {
					reject(rejection);
				}
			}
		}
	}

	/** An interface of abstract methods only is a type only and produces no Lua; it checks its methods' names. */
	private void checkInterface(ClassTree type) {
		for (Tree member : type.getMembers()) {
			if (isSynthetic(member)) {
				continue;
			}
			try {
				luaMemberName(member);
			} catch (Rejection rejection) {
				reject(rejection);
			}
		}
	}

	/** Whether an interface has Lua of its own: constants, methods with bodies, or nested types. */
	private boolean hasLua(ClassTree type) {
		return type.getMembers().stream().anyMatch(member -> !isSynthetic(member) && (member instanceof VariableTree
				|| member instanceof ClassTree || (member instanceof MethodTree method && method.getBody() != null)));
	}

	boolean isNonNegativeField(@Nullable Element element) {
		return element != null && nonNegativeFields.contains(element);
	}

	/**
	 * Finds the fields of the file whose value is never negative, so that integer {@code /} and {@code %} on them can
	 * be Lua's {@code //} and {@code %}: integer fields that are private or final, start non-negative (Java's default
	 * is 0), and that the file only ever raises, as {@code ++}, a constant or a counter does. A field may rest on
	 * another, a constant declared further down for one, so the search repeats until it finds nothing new.
	 */
	private void inferNonNegativeFields(ClassTree top) {
		List<VariableTree> candidates = new ArrayList<>();
		new TreeScanner<Void, Void>() {
			@Override
			public Void visitClass(ClassTree node, Void unused) {
				for (Tree member : node.getMembers()) {
					if (member instanceof VariableTree field && types.isIntegral(type(field))) {
						Set<Modifier> flags = field.getModifiers().getFlags();
						if (flags.contains(Modifier.PRIVATE) || flags.contains(Modifier.FINAL)) {
							candidates.add(field);
						}
					}
				}
				return super.visitClass(node, null);
			}
		}.scan(top, null);
		BodyLowering lowering = new BodyLowering(this, false, false);
		boolean found = true;
		while (found) {
			found = false;
			for (VariableTree field : candidates) {
				Element element = element(field);
				ExpressionTree initializer = field.getInitializer();
				if (!nonNegativeFields.contains(element)
						&& (initializer == null || lowering.knownNonNegative(initializer))
						&& lowering.onlyRaises(unit, element)) {
					nonNegativeFields.add(element);
					found = true;
				}
			}
		}
	}

	/**
	 * Whether every call of the method returns a non-negative integer: a method of this file that nothing in the
	 * plugin overrides, each of whose {@code return}s gives a value known never to be negative.
	 */
	boolean returnsNonNegative(@Nullable Element element) {
		if (!(element instanceof ExecutableElement method) || !types.isIntegral(method.getReturnType())
				|| !fileTypes.contains(JavaTypes.owner(method)) || types.isOverridden(method)) {
			return false;
		}
		Boolean known = nonNegativeMethods.get(method);
		if (known != null) {
			return known;
		}
		// A method that calls itself is not known while it is being worked out
		nonNegativeMethods.put(method, false);
		MethodTree tree = trees.getTree(method);
		boolean result = tree != null && tree.getBody() != null && returnsOnlyNonNegative(tree.getBody());
		nonNegativeMethods.put(method, result);
		return result;
	}

	/** Whether every {@code return} of the body, outside the lambdas and classes in it, gives a non-negative value. */
	private boolean returnsOnlyNonNegative(BlockTree body) {
		BodyLowering lowering = new BodyLowering(this, false, false);
		List<ExpressionTree> returned = new ArrayList<>();
		new TreeScanner<Void, Void>() {
			@Override
			public Void visitReturn(ReturnTree node, Void unused) {
				if (node.getExpression() != null) {
					returned.add(node.getExpression());
				}
				return null;
			}

			@Override
			public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitClass(ClassTree node, Void unused) {
				return null;
			}
		}.scan(body, null);
		return !returned.isEmpty() && returned.stream().allMatch(lowering::knownNonNegative);
	}

	/** Whether a lambda implements a method that returns nothing, so that its expression body is a statement. */
	boolean returnsVoid(LambdaExpressionTree lambda) {
		if (!(type(lambda) instanceof DeclaredType declared)) {
			return false;
		}
		TypeElement functional = (TypeElement) declared.asElement();
		return elements.getAllMembers(functional).stream()
				.filter(member -> member.getKind() == ElementKind.METHOD)
				.filter(member -> member.getModifiers().contains(Modifier.ABSTRACT))
				.map(ExecutableElement.class::cast)
				.filter(method -> !Set.of("equals", "hashCode", "toString").contains(method.getSimpleName().toString()))
				.anyMatch(method -> method.getReturnType().getKind() == TypeKind.VOID);
	}

	void reject(Rejection rejection) {
		error(start(rejection.tree()), rejection.message());
	}

	// ---- Source positions and javac's model

	int start(Tree tree) {
		return (int) positions.getStartPosition(unit, tree);
	}

	int end(Tree tree) {
		return (int) positions.getEndPosition(unit, tree);
	}

	private boolean isSynthetic(Tree tree) {
		return positions.getEndPosition(unit, tree) == NOPOS;
	}

	SourcePosition origin(Tree tree) {
		String file = unit.getSourceFile().getName().replace('\\', '/').replaceAll(".*/", "");
		String packageName = unit.getPackage() == null ? "" : unit.getPackage().getPackageName().toString();
		String folder = packageName.equals(rootPackage) || packageName.isEmpty()
				? ""
				: packageName.substring(Math.min(packageName.length(), rootPackage.length() + 1)).replace('.', '/') + "/";
		return new SourcePosition(folder + file, (int) unit.getLineMap().getLineNumber(start(tree)));
	}

	String sourceText(Tree tree) {
		return source.subSequence(start(tree), end(tree)).toString();
	}

	TreePath path(Tree tree) {
		TreePath path = TreePath.getPath(unit, tree);
		if (path == null) {
			throw new IllegalStateException("tree not found in its compilation unit: " + tree);
		}
		return path;
	}

	Element element(Tree tree) {
		Element element = trees.getElement(path(tree));
		if (element == null) {
			throw new IllegalStateException("javac resolved no element for " + tree);
		}
		return element;
	}

	TypeMirror type(Tree tree) {
		TypeMirror type = trees.getTypeMirror(path(tree));
		if (type == null) {
			throw new IllegalStateException("javac resolved no type for " + tree);
		}
		return type;
	}

	/** The class or interface a type in the source names, such as the one after {@code extends}. */
	private TypeElement typeElement(Tree typeTree) {
		if (type(typeTree) instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element) {
			return element;
		}
		throw new IllegalStateException("not a class or interface: " + typeTree);
	}

	private static String packageName(TypeElement type) {
		Element enclosing = type.getEnclosingElement();
		return enclosing instanceof PackageElement packageElement ? packageElement.getQualifiedName().toString() : "";
	}

	private static boolean isStatic(Set<Modifier> flags) {
		return flags.contains(Modifier.STATIC);
	}

	private static boolean isConstructor(MethodTree method) {
		return method.getName().contentEquals("<init>");
	}

	private void error(int position, String message) {
		diagnostics.add(diagnostic(Diagnostic.Severity.ERROR, position, message));
	}

	private void warning(int position, String message) {
		diagnostics.add(diagnostic(Diagnostic.Severity.WARNING, position, message));
	}

	private Diagnostic diagnostic(Diagnostic.Severity severity, int position, String message) {
		long line = unit.getLineMap().getLineNumber(position);
		// A character count, not javac's getColumnNumber, which expands tabs to eight columns
		long column = position - unit.getLineMap().getStartPosition(line) + 1;
		return new Diagnostic(severity, unit.getSourceFile().getName(), line, column, message, sourceLine(line),
				position);
	}

	private String sourceLine(long line) {
		String[] lines = source.toString().split("\\R", -1);
		return line >= 1 && line <= lines.length ? lines[(int) line - 1] : "";
	}
}
