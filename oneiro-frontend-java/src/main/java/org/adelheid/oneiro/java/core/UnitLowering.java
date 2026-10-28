package org.adelheid.oneiro.java.core;

import static javax.tools.Diagnostic.NOPOS;
import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.field;
import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import org.adelheid.oneiro.java.JavaFrontend;
import org.adelheid.oneiro.java.language.CharConversions;
import org.adelheid.oneiro.java.language.Constructors;
import org.adelheid.oneiro.java.language.InheritedMembers;
import org.adelheid.oneiro.java.language.NonNegativeMembers;
import org.adelheid.oneiro.java.language.OneBasedLoops;
import org.adelheid.oneiro.java.language.Overloading;
import org.adelheid.oneiro.java.plugin.OptionalChecks;
import org.adelheid.oneiro.java.plugin.PluginEntry;
import org.adelheid.oneiro.java.plugin.ReviewWarnings;
import org.adelheid.oneiro.java.plugin.SettingsModule;
import org.adelheid.oneiro.lowering.Diagnostic;
import org.adelheid.oneiro.lowering.LocalNames;
import org.adelheid.oneiro.lowering.LuaModule;
import org.adelheid.oneiro.lowering.ModuleMember;
import org.adelheid.oneiro.lowering.ModulePaths;
import org.adelheid.oneiro.lowering.PluginFacts;
import org.adelheid.oneiro.lowering.translation.Helper;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Comment;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.FunctionBody;
import org.adelheid.oneiro.lua.LuaNames;
import org.adelheid.oneiro.lua.SourcePosition;
import org.adelheid.oneiro.lua.Statement;
import org.adelheid.oneiro.lua.Trivia;
import org.jspecify.annotations.Nullable;

/**
 * Lowers one Java source file into one Lua module. The module starts with a header from the file's leading comments
 * and the class Javadoc, a {@code require} line for each plugin class it uses, and the helpers its translations need.
 * Then come the module table, the nested classes and enums as local tables, and the members of every class, ordered
 * so that Lua defines each one before it runs.
 *
 * <p>This class lowers the file's classes and their members, and keeps what the whole file shares: its diagnostics,
 * helpers, requires and locals. The rest has classes of its own, which reach that shared state through this one:
 * {@link Constructors}, {@link Overloading}, {@link MemberComments}, {@link InheritedMembers}, {@link PluginEntry} for
 * the plugin class, {@link SettingsModule}, {@link DeclarationChecks}, {@link NonNegativeMembers},
 * {@link ReviewWarnings} and {@link ModuleAssembly}. The code inside methods is lowered by {@link BodyLowering}.
 */
public final class UnitLowering {

	public final CompilationUnitTree compilationUnit;
	public final DocTrees trees;
	public final Elements elements;
	public final JavaTypes types;
	public final LocalNames names;
	public final CommentIndex comments;
	private final String rootPackage;
	public final PluginFacts plugin;
	private final CharSequence source;
	private final SourcePositions positions;
	public final List<Diagnostic> diagnostics = new ArrayList<>();
	private final EnumSet<Helper> helpers = EnumSet.noneOf(Helper.class);
	// The Lua globals the module reads, such as Player or config, which a local of the same name would hide
	private final Set<String> globals = new HashSet<>();
	private final @Nullable Set<Helper> helpersInUse;
	// The Lua globals a first lowering found the module reads, which no made-up local takes; see freshLocal
	private final Set<String> globalsInUse;
	// The local name of each required module and its require path, in order of first use
	final Map<String, String> requires = new LinkedHashMap<>();
	// The require paths of modules that would share a local, by that local
	private final Map<String, Set<String>> requireClashes = new TreeMap<>();
	// Every class, enum, record and interface declared in this file
	public final Set<TypeElement> fileTypes = new HashSet<>();
	// The file's top-level class, the one the module stands for
	@Nullable TypeElement topLevel;
	// The plugin class, or the dev source set's DevPlugin class, when this file is one; it becomes a chunk of locals
	public @Nullable TypeElement entry;
	// The comments above startUp() and shutDown(), which go above their statements
	public final Map<String, List<CommentIndex.SourceComment>> lifecycleComments = new HashMap<>();
	// The @Subscribe methods whose event passes nothing, whose Lua functions take no parameter
	public final Set<Element> parameterless = new HashSet<>();
	// The local that holds the module table: the top-level class's simple name
	public String moduleName = "";
	// Worked out on first use
	private @Nullable Set<Element> oneBased;
	private @Nullable CharConversions charConversions;
	private @Nullable Set<String> fileLocals;
	// The Lua name of each local and parameter, decided once; see codeLocalName
	private final Map<Element, String> codeLocalNames = new HashMap<>();
	// The file-level locals each member's code uses, by the member
	private final Map<Tree, Set<String>> fileLocalsUsed = new HashMap<>();
	private final SettingsModule settings;
	private final PluginEntry pluginEntry;
	private final ModuleAssembly assembly;
	private final Overloading overloading;
	private final Constructors constructors;
	private final MemberComments memberComments;
	private final InheritedMembers inheritedMembers;
	private final DeclarationChecks declarationChecks;
	private final NonNegativeMembers nonNegativeMembers;
	private final ReviewWarnings reviewWarnings;
	private final OptionalChecks optionalChecks;

	/**
	 * @param names the Lua names for Java locals, which avoid the helpers and globals a first lowering found
	 * @param helpersInUse the helpers a first lowering of the file used, or null during that first lowering
	 * @param globalsInUse the Lua globals a first lowering of the file read, or none during that first lowering
	 */
	public UnitLowering(CompilationUnitTree compilationUnit, DocTrees trees, Elements elements, JavaTypes types,
			LocalNames names, JavaFrontend.Options options, @Nullable Set<Helper> helpersInUse,
			Set<String> globalsInUse) {
		this.helpersInUse = helpersInUse == null ? null : EnumSet.copyOf(helpersInUse);
		this.globalsInUse = Set.copyOf(globalsInUse);
		this.compilationUnit = compilationUnit;
		this.trees = trees;
		this.elements = elements;
		this.types = types;
		this.names = names;
		this.rootPackage = options.rootPackage();
		this.plugin = options.plugin();
		this.positions = trees.getSourcePositions();
		try {
			this.source = compilationUnit.getSourceFile().getCharContent(true);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		this.comments = CommentIndex.scan(source);
		this.settings = new SettingsModule(this);
		this.pluginEntry = new PluginEntry(this);
		this.assembly = new ModuleAssembly(this);
		this.overloading = new Overloading(this);
		this.constructors = new Constructors(this);
		this.memberComments = new MemberComments(this);
		this.inheritedMembers = new InheritedMembers(this);
		this.declarationChecks = new DeclarationChecks(this);
		this.nonNegativeMembers = new NonNegativeMembers(this);
		this.reviewWarnings = new ReviewWarnings(this);
		this.optionalChecks = new OptionalChecks(this);
	}

	/** The errors and warnings found while lowering, in source order. */
	public List<Diagnostic> diagnostics() {
		return diagnostics.stream()
				.sorted(Comparator.comparingLong(Diagnostic::line).thenComparingLong(Diagnostic::column))
				.toList();
	}

	public Set<Helper> helpers() {
		return helpers;
	}

	public ModuleAssembly assembly() {
		return assembly;
	}

	public Overloading overloading() {
		return overloading;
	}

	public MemberComments memberComments() {
		return memberComments;
	}

	NonNegativeMembers nonNegativeMembers() {
		return nonNegativeMembers;
	}

	public ReviewWarnings reviewWarnings() {
		return reviewWarnings;
	}

	public OptionalChecks optionalChecks() {
		return optionalChecks;
	}

	/** The Lua globals the module reads: the first name of each API path, such as {@code ui} for {@code ui.Text}. */
	public Set<String> globals() {
		return globals;
	}

	/** The module, or null when the file has no Lua of its own or could not be lowered; see {@link #diagnostics()}. */
	public @Nullable LuaModule lower() {
		List<ClassTree> classes = compilationUnit.getTypeDecls().stream()
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
		nonNegativeMembers.inferNonNegativeFields(type);
		String packageName = packageName(typeElement);
		if (!ModulePaths.isInside(rootPackage, packageName)) {
			error(start(type), typeElement.getQualifiedName() + " is outside the plugin's root package " + rootPackage
					+ "; move it below " + rootPackage + ".");
			return null;
		}
		if (JavaTypes.isGenerated(typeElement)) {
			// The plugin's generated Resources and Urls stand for tables the client provides
			return null;
		}
		if (!declarationChecks.checkBindingsOnly(type)) {
			return null;
		}
		moduleName = type.getSimpleName().toString();
		boolean isEntry = types.isPluginEntry(typeElement) || types.isDevEntry(typeElement);
		String modulePath = ModulePaths.requirePath(sourceFolder(), rootPackage, packageName, moduleName) + ".lua";
		if (!isEntry && plugin.luaFiles().contains(modulePath)) {
			error(start(type), "The Lua of " + moduleName + " would be " + modulePath + ", a file the plugin's own Lua"
					+ " has already; rename the class or that file.");
			return null;
		}
		if (types.configGroup(typeElement) != null) {
			return settings.lowerConfig(type, typeElement, modulePath);
		}
		switch (type.getKind()) {
			case RECORD -> {
				if (!types.isObjectRecord(typeElement)) {
					declarationChecks.checkRecord(type);
					return null;
				}
			}
			case INTERFACE -> {
				if (!declarationChecks.hasLua(type)) {
					declarationChecks.checkInterface(type);
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
		if (isEntry) {
			entry = typeElement;
			pluginEntry.checkEntry(type, typeElement);
		}
		// The dev source set's DevPlugin class is the development folder's dev/main.lua, which its main.lua requires
		String path = types.isPluginEntry(typeElement) ? "main.lua"
				: types.isDevEntry(typeElement) ? "dev/main.lua"
				: modulePath;

		List<Comment> header = header(type);
		@Nullable Expression table;
		boolean instanceClass = false;
		List<Member> members = new ArrayList<>();
		try {
			if (type.getKind() == Tree.Kind.ENUM && !types.isObjectEnum(typeElement.asType())) {
				table = enumTable(type);
			} else {
				declarationChecks.checkHeritage(type);
				// An object enum's constants are its instances; an object record's values are; an interface has none
				instanceClass = !isEntry && type.getKind() != Tree.Kind.INTERFACE
						&& (type.getKind() == Tree.Kind.ENUM || type.getKind() == Tree.Kind.RECORD
						|| types.hasObjects(typeElement));
				// The plugin class is a singleton whose members are locals of main.lua, so it has no table
				table = isEntry ? null : classTable(typeElement);
				List<PluginEntry.Subscription> subscriptions = isEntry ? pluginEntry.subscriptions(type) : List.of();
				List<Member> own = classMembers(type, typeElement, moduleName, instanceClass, members);
				members.addAll(own);
				if (isEntry) {
					members.addAll(pluginEntry.entryLifecycle(type, subscriptions));
				} else {
					pluginEntry.checkNoSubscribers(type);
				}
			}
		} catch (Rejection rejection) {
			reject(rejection);
			return null;
		}
		assembly.checkLocals(type);
		List<Block.Entry> body = assembly.assemble(table, instanceClass, members);
		requireClashes.forEach((local, paths) -> error(start(type), "In Lua, this file would require "
				+ String.join(" and ", paths) + " as one local, " + local + "; rename the class or file of one."));
		for (CommentIndex.SourceComment comment : comments.unclaimed()) {
			warning(comment.start(), "This comment could not be placed in the Lua and was left out; move it onto its"
					+ " own line above a statement.");
		}
		if (diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == Diagnostic.Severity.ERROR)) {
			return null;
		}
		return LuaModule.of(path, header, new Block(body, List.of()));
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
	public List<Comment> header(ClassTree type) {
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

	/** The class of the plugin's that a class extends, or null when it extends {@code Object}. */
	public @Nullable TypeElement pluginSuperclass(TypeElement type) {
		if (type.getSuperclass() instanceof DeclaredType declared
				&& declared.asElement() instanceof TypeElement superclass && types.isPluginType(superclass)) {
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
			String kind = type.getKind().toString().toLowerCase(Locale.ROOT);
			throw new Rejection(type, "Lua keeps one table per name in a function; declare this " + kind
					+ " beside the class, not inside a method.");
		}
		for (Tree member : type.getMembers()) {
			if ((member instanceof MethodTree method && method.getModifiers().getFlags().contains(Modifier.PRIVATE))
					|| member instanceof ClassTree) {
				throw new Rejection(member, "A class inside a method is a local table whose members are its fields;"
						+ " make this a member the class's methods call on self, or move the class beside its"
						+ " outer one.");
			}
		}
		declarationChecks.checkHeritage(type);
		boolean instanceClass = types.hasObjects(element);
		String table = tableName(element);
		List<Statement> lowered = new ArrayList<>();
		lowered.add(new Statement.Local(List.of(table), List.of(classTable(element))));
		if (instanceClass) {
			lowered.add(Statement.assignment(field(name(table), "__index"), name(table)));
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
		Expression metatable = new Expression.Table(
				List.of(new Expression.TableField.Named("__index", moduleReference(superclass))));
		return call(name("setmetatable"), new Expression.Table(List.of()), metatable);
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
			List<Comment> lines = memberComments.declarationComments(comments.take(cursor, start(constant)), constant);
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
	 * @param entries the member's statements, with their comments
	 * @param assignForm the member's statements when its local was declared ahead
	 * @param references the declarations of this file that the member's code uses
	 * @param after the declarations whose members must come first, such as the table of the member's own class
	 */
	public record Member(List<Element> elements, boolean function, @Nullable String localName,
			List<Block.Entry> entries, List<Block.Entry> assignForm, Set<Element> references, Set<Element> after,
			boolean type) implements ModuleMember<Element> {

		/** A member that is no type's table. */
		public Member(List<Element> elements, boolean function, @Nullable String localName, List<Block.Entry> entries,
				List<Block.Entry> assignForm, Set<Element> references, Set<Element> after) {
			this(elements, function, localName, entries, assignForm, references, after, false);
		}
	}

	/**
	 * One class's members as Lua, in source order, with {@code Class.new} where the constructor or the first instance
	 * field is. The members of nested classes and enums go to {@code nested}, because Lua must define those tables
	 * before the code that uses them.
	 */
	private List<Member> classMembers(ClassTree type, TypeElement owner, String table, boolean instanceClass,
			List<Member> nested) {
		Overloading.Overloads overloads = overloading.checkNames(type);
		List<Member> members = new ArrayList<>();
		// The comments above shorter overloads, which go above the longest one
		Map<MethodTree, List<Comment>> mergedComments = new HashMap<>();
		// Where each method's member is, for the comments of shorter overloads that come after it
		Map<MethodTree, Integer> memberAt = new HashMap<>();
		// The instance fields and initializer blocks, which the constructor runs in the order Java does
		List<Tree> instanceFields = new ArrayList<>();
		List<Trivia> instanceFieldTrivia = new ArrayList<>();
		MethodTree constructor = null;
		Trivia constructorTrivia = Trivia.NONE;
		// Where Class.new goes among the members: at the constructor, or else at the first instance field
		int constructorSlot = -1;
		// Without either, the implicit constructor goes where Java's would be: before the methods
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
						memberComments.dropComments(leading, "This comment is on a field of settings, which has no Lua"
								+ " of its own, so it was left out.");
					}
					case VariableTree field when constant -> members.addAll(enumConstant(field, owner, table,
							new Trivia(blankLine, memberComments.declarationComments(leading, field), trailing),
							ordinal++));
					case VariableTree field when owner.equals(entry) -> members.add(staticField(field, owner, table,
							new Trivia(blankLine, memberComments.declarationComments(leading, field), trailing)));
					case MethodTree method when owner.equals(entry) && isLifecycle(method) ->
							lifecycleComments.put(method.getName().toString(), leading);
					case MethodTree method when owner.equals(entry) && isConstructor(method) -> {
						if (!method.getParameters().isEmpty()
								|| !constructors.realStatements(method.getBody()).isEmpty()) {
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
						List<Comment> lines = memberComments.declarationComments(leading, field);
						instanceFieldTrivia.add(new Trivia(blankBefore, lines, trailing));
						if (constructorSlot < 0) {
							constructorSlot = members.size();
						}
					}
					case VariableTree field -> members.add(staticField(field, owner, table,
							new Trivia(blankLine, memberComments.declarationComments(leading, field), trailing)));
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
					case MethodTree method when overloads.longestOf().containsKey(method) ->
							mergedComments.computeIfAbsent(overloads.longestOf().get(method), _ -> new ArrayList<>())
									.addAll(BodyLowering.comments(leading));
					case MethodTree method when isConstructor(method) -> {
						if (!instanceClass) {
							continue;
						}
						constructor = method;
						Trivia own = memberComments.memberTrivia(true, leading, method, overloads.chain(method));
						constructorTrivia = MemberComments.withComments(own, mergedComments.remove(method));
						constructorSlot = members.size();
					}
					case MethodTree method when method.getBody() == null
							&& (method.getModifiers().getFlags().contains(Modifier.ABSTRACT)
									|| owner.getKind() == ElementKind.INTERFACE) ->
							memberComments.dropComments(leading, "This comment is on an abstract method, which has no"
									+ " Lua of its own, so it was left out.");
					case MethodTree method -> {
						Trivia own = memberComments.memberTrivia(true, leading, method, overloads.chain(method));
						Trivia trivia = MemberComments.withComments(own, mergedComments.remove(method));
						if (firstMethodSlot < 0) {
							firstMethodSlot = members.size();
						}
						memberAt.put(method, members.size());
						members.add(method(method, owner, table, trivia, overloads.chain(method)));
					}
					case ClassTree inner -> nestedType(inner, owner, table,
							new Trivia(blankLine, memberComments.declarationComments(leading, inner), null), nested);
					default -> reject(Rejection.notYet(member, "This kind of class member"));
				}
			} catch (Rejection rejection) {
				reject(rejection);
			}
		}
		// Shorter overloads that came after the longest one
		for (Map.Entry<MethodTree, List<Comment>> late : mergedComments.entrySet()) {
			if (late.getKey().equals(constructor)) {
				constructorTrivia = MemberComments.withComments(constructorTrivia, late.getValue());
			} else {
				Integer index = memberAt.get(late.getKey());
				if (index != null) {
					members.set(index, MemberComments.withComments(members.get(index), late.getValue()));
				}
			}
		}
		members.addAll(inheritedMembers.inheritedDefaults(owner, table));
		members.addAll(inheritedMembers.libraryMetamethods(owner, table));
		if (instanceClass) {
			try {
				Member lowered = constructors.constructor(type, owner, table, constructor, constructorTrivia,
						overloads.chain(constructor), instanceFields, instanceFieldTrivia);
				int slot = constructorSlot >= 0 ? constructorSlot
						: firstMethodSlot >= 0 ? firstMethodSlot
						: members.size();
				members.add(slot, lowered);
			} catch (Rejection rejection) {
				reject(rejection);
			}
		}
		return members;
	}

	/**
	 * A nested type. A static class or an enum becomes a local table in this file, also a field of the outer class's
	 * table unless it is private; records and interfaces are types only.
	 */
	private void nestedType(ClassTree nested, TypeElement outer, String outerTable, Trivia trivia, List<Member> out) {
		TypeElement element = (TypeElement) element(nested);
		switch (nested.getKind()) {
			case INTERFACE -> {
				if (!declarationChecks.hasLua(nested)) {
					declarationChecks.checkInterface(nested);
					return;
				}
				declarationChecks.checkHeritage(nested);
				out.add(tableMember(nested, element, outer, outerTable, classTable(element), false, trivia));
				List<Member> inner = new ArrayList<>();
				List<Member> own = classMembers(nested, element, tableName(element), false, inner);
				out.addAll(inner);
				out.addAll(own);
			}
			case RECORD, ENUM -> {
				if (nested.getKind() == Tree.Kind.RECORD && !types.isObjectRecord(element)) {
					declarationChecks.checkRecord(nested);
					return;
				}
				if (nested.getKind() == Tree.Kind.ENUM && !types.isObjectEnum(element.asType())) {
					out.add(tableMember(nested, element, outer, outerTable, enumTable(nested), false, trivia));
					return;
				}
				declarationChecks.checkHeritage(nested);
				out.add(tableMember(nested, element, outer, outerTable, classTable(element), true, trivia));
				List<Member> inner = new ArrayList<>();
				List<Member> own = classMembers(nested, element, tableName(element), true, inner);
				out.addAll(inner);
				out.addAll(own);
			}
			case CLASS -> {
				declarationChecks.checkHeritage(nested);
				// An inner class's objects keep their outer object, so it always has objects
				boolean instanceClass = JavaTypes.isInner(element) || types.hasObjects(element);
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
			Statement index = Statement.assignment(field(name(local), "__index"), name(local));
			rest.add(new Block.Entry(index, Trivia.NONE, origin));
		}
		if (!nested.getModifiers().getFlags().contains(Modifier.PRIVATE) && !outer.equals(entry)) {
			Statement exported = Statement.assignment(field(name(outerTable), local), name(local));
			rest.add(new Block.Entry(exported, Trivia.NONE, origin));
		}
		List<Block.Entry> entries = new ArrayList<>();
		entries.add(new Block.Entry(new Statement.Local(List.of(local), List.of(value)), trivia, origin));
		entries.addAll(rest);
		List<Block.Entry> assignForm = new ArrayList<>();
		assignForm.add(new Block.Entry(Statement.assignment(name(local), value), trivia, origin));
		assignForm.addAll(rest);
		Set<Element> after = new HashSet<>();
		if (!outer.equals(topLevel)) {
			after.add(outer);
		}
		TypeElement superclass = pluginSuperclass(element);
		if (superclass != null && fileTypes.contains(superclass)) {
			after.add(superclass);
		}
		return new Member(List.of(element), false, local, entries, assignForm, after, after, true);
	}

	private Member staticField(VariableTree field, TypeElement owner, String table, Trivia trivia) {
		BodyLowering lowering = new BodyLowering(this, false, false);
		Element element = element(field);
		ExpressionTree initializer = field.getInitializer();
		Expression value = initializer == null ? defaultValue(type(field)) : lowering.expression(initializer);
		Set<Modifier> flags = field.getModifiers().getFlags();
		Set<Element> references = assembly.references(field);
		Set<Element> after = ownTable(owner);
		if (flags.contains(Modifier.PRIVATE) || owner.equals(entry)) {
			String local = lowering.declare(field);
			List<Expression> values = value == null ? List.of() : List.of(value);
			Block.Entry declaration = new Block.Entry(new Statement.Local(List.of(local), values), trivia,
					origin(field));
			List<Block.Entry> assignment = value == null ? List.of() : List.of(new Block.Entry(
					Statement.assignment(name(local), value), trivia, origin(field)));
			return new Member(List.of(element), false, local, List.of(declaration), assignment, references, after);
		}
		if (value == null) {
			return new Member(List.of(element), false, null, List.of(), List.of(), references, after);
		}
		Statement assignment = Statement.assignment(field(name(table), luaMemberName(field)), value);
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
		Statement assignment = Statement.assignment(field(name(table), luaName),
				new Expression.Call(field(name(table), "new"), arguments));
		Element element = element(constant);
		Set<Element> references = new HashSet<>(assembly.references(creation));
		List<Member> members = new ArrayList<>();
		members.add(new Member(List.of(element), false, null,
				List.of(new Block.Entry(assignment, trivia, origin(constant))), List.of(), references,
				ownTable(owner)));
		ClassTree body = creation.getClassBody();
		if (body != null) {
			int cursor = start(body);
			for (Tree member : body.getMembers()) {
				if (isSynthetic(member)) {
					continue;
				}
				List<CommentIndex.SourceComment> leading = comments.take(cursor, start(member));
				cursor = end(member);
				if (member instanceof VariableTree field) {
					// A field of the constant's own: Herb.RANARR.bonus = 5, which its methods read as self.bonus
					ExpressionTree initializer = field.getInitializer();
					Expression value = initializer == null
							? defaultValue(type(field)) : lowering.expression(initializer);
					if (value != null) {
						List<Block.Entry> entries = new ArrayList<>(members.getFirst().entries());
						Expression target = field(field(name(table), luaName), luaMemberName(field));
						Statement fieldAssignment = Statement.assignment(target, value);
						List<Comment> above = memberComments.declarationComments(leading, field);
						entries.add(new Block.Entry(fieldAssignment, new Trivia(false, above, null), origin(field)));
						Member constantMember = members.getFirst();
						Set<Element> withField = new HashSet<>(constantMember.references());
						withField.addAll(assembly.references(field));
						members.set(0, new Member(constantMember.elements(), false, null, entries, List.of(), withField,
								constantMember.after()));
					}
					continue;
				}
				if (!(member instanceof MethodTree method)) {
					throw new Rejection(member, "An enum constant's body can hold only fields and methods in Lua;"
							+ " declare this in the enum itself.");
				}
				if (method.getModifiers().getFlags().contains(Modifier.PRIVATE)) {
					throw new Rejection(member, "A private method of an enum constant's body would be a local of the"
							+ " whole file in Lua, which another constant's could hide; make it a method of the enum.");
				}
				Member override = method(method, owner, List.of(table, luaName),
						memberComments.memberTrivia(true, leading, method, null), null);
				Set<Element> after = new HashSet<>(override.after());
				after.add(element);
				members.add(new Member(override.elements(), true, null, override.entries(), List.of(),
						override.references(), after));
			}
		}
		return members;
	}

	/** The table of a nested class, which its members are assigned into; the top-level class's table comes first. */
	public Set<Element> ownTable(TypeElement owner) {
		return owner.equals(topLevel) ? Set.of() : Set.of(owner);
	}

	/**
	 * Java's default for a field without an initializer: {@code 0}, {@code 0.0}, {@code false}, the char
	 * {@code "\0"}, or nothing.
	 */
	public @Nullable Expression defaultValue(TypeMirror type) {
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
		return new Member(List.of(), false, null, entries, List.of(), assembly.references(block), ownTable(owner));
	}

	private Member method(MethodTree method, TypeElement owner, String table, Trivia trivia,
			Overloading.@Nullable DefaultChain chain) {
		return method(method, owner, List.of(table), trivia, chain);
	}

	/** @param tablePath the table the method goes into: the class's, or an enum constant's, {@code Speed.FAST} */
	private Member method(MethodTree method, TypeElement owner, List<String> tablePath, Trivia trivia,
			Overloading.@Nullable DefaultChain chain) {
		ExecutableElement element = (ExecutableElement) element(method);
		Set<Modifier> flags = method.getModifiers().getFlags();
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
		statements.addAll(overloading.defaultFills(method, chain, lowering));
		Block lowered = lowering.block(methodBody);
		statements.addAll(lowered.entries());
		FunctionBody function = new FunctionBody(parameters, element.isVarArgs(),
				new Block(statements, lowered.closingComments()));
		List<Element> declared = new ArrayList<>(List.of(element));
		Set<Element> references = new HashSet<>(assembly.references(method));
		overloading.addChain(chain, declared, references);
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
		return new Member(declared, true, null, List.of(new Block.Entry(declaration, trivia, origin(method))),
				List.of(), references, after);
	}

	/**
	 * Declares the parameters, adding their Lua names; for a varargs method the last is Lua's {@code ...}, which the
	 * function's first statement keeps in a table, {@code local values = { ... }}, returned here.
	 */
	public List<Block.Entry> declareParameters(List<? extends VariableTree> declared, boolean varargs,
			BodyLowering lowering, List<String> parameters) {
		int fixed = varargs ? declared.size() - 1 : declared.size();
		for (int index = 0; index < fixed; index++) {
			parameters.add(lowering.declare(declared.get(index)));
		}
		if (!varargs) {
			return List.of();
		}
		String values = lowering.declare(declared.getLast());
		Expression packed = new Expression.Table(
				List.of(new Expression.TableField.Positional(new Expression.Vararg())));
		return List.of(Block.Entry.of(new Statement.Local(List.of(values), List.of(packed))));
	}

	// ---- The plugin class

	/** Whether a method of the plugin class is {@code startUp()} or {@code shutDown()}, the lifecycle it overrides. */
	public static boolean isLifecycle(MethodTree method) {
		return (method.getName().contentEquals("startUp") || method.getName().contentEquals("shutDown"))
				&& method.getParameters().isEmpty() && !isStatic(method.getModifiers().getFlags());
	}

	/** Whether a method is the plugin class's {@code startUp()} or {@code shutDown()}, which no code may call. */
	boolean isLifecycle(ExecutableElement method) {
		return entry != null && entry.equals(method.getEnclosingElement()) && method.getParameters().isEmpty()
				&& (method.getSimpleName().contentEquals("startUp")
						|| method.getSimpleName().contentEquals("shutDown"));
	}

	/** Whether code in the tree is the plugin class's own, where {@code this} has no Lua object. */
	boolean isInEntry(Tree tree) {
		return entry != null && enclosingClass(tree).equals(entry);
	}

	/** Whether a class is the plugin class this file lowers into main.lua or the dev source set's entry. */
	public boolean isEntry(TypeElement type) {
		return type.equals(entry);
	}

	/**
	 * Rejects code in another file that uses the plugin class: it becomes main.lua, which no file can require, or the
	 * dev entry, which only the development main.lua loads.
	 */
	public void checkReachable(TypeElement type, Tree where) {
		if (!fileTypes.contains(type) && (types.isPluginEntry(type) || types.isDevEntry(type))) {
			throw new Rejection(where, "The plugin class becomes main.lua, which no other file can require; move what"
					+ " this code needs from " + type.getSimpleName() + " into a class of its own.");
		}
	}

	// ---- Helpers, names and references

	/** Notes that the module uses a helper, so that the module defines it at the top. */
	public void use(Helper helper) {
		helpers.add(helper);
	}

	/** Notes that the module reads a Lua global, so that no local hides it. */
	public void useGlobal(String global) {
		globals.add(global);
	}

	/** The plugin folder this compilation's modules go to: {@code src}, or {@code dev} for the dev source set. */
	private String sourceFolder() {
		return plugin.dev() ? "dev" : "src";
	}

	/** The Lua local for a private member of a class in this file. */
	public String localName(Element member) {
		return names.luaName(member.getSimpleName().toString());
	}

	/**
	 * The Lua local holding a class's table: the module table for the file's class, the class's name for a nested one.
	 */
	private String tableName(TypeElement type) {
		return type.equals(topLevel) ? moduleName : type.getSimpleName().toString();
	}

	/**
	 * The table of a plugin class: a local of this file for its own classes, another file's module, required at the
	 * top, or a field of that module for a class nested in it.
	 */
	public Expression moduleReference(TypeElement type) {
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
		return required(local, ModulePaths.requirePath(types.isOwnType(type) ? sourceFolder() : "src", rootPackage,
				packageElement.getQualifiedName().toString(), local));
	}

	/**
	 * A module this file requires, {@code local Levels = require("src/core/levels")} at its top, as the local that
	 * holds it. Two modules that would be one local, classes of one name in two packages, are reported once the file is
	 * lowered.
	 */
	public Expression required(String local, String path) {
		String existing = requires.putIfAbsent(local, path);
		if (existing != null && !existing.equals(path)) {
			requireClashes.computeIfAbsent(local, name -> new TreeSet<>(List.of(existing))).add(path);
		}
		return name(local);
	}

	/**
	 * A static field or method: a local when private to a class of this file, otherwise a field of its class's table.
	 */
	public Expression staticMember(TypeElement owner, Element member) {
		if (fileTypes.contains(owner) && (member.getModifiers().contains(Modifier.PRIVATE) || owner.equals(entry))) {
			return name(localName(member));
		}
		return field(moduleReference(owner), types.memberName(member));
	}

	/**
	 * Whether a method is written in the plugin's source, as opposed to one javac gives a record: its accessors,
	 * canonical constructor, equals, hashCode and toString.
	 */
	public boolean declaredInSource(Element method) {
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
	public boolean countsFromOne(Element variable) {
		Set<Element> found = oneBased;
		if (found == null) {
			found = OneBasedLoops.find(this, new BodyLowering(this, false, false).loops()::countsFromZero);
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
	 * name, or that name with a number, from 2, when the surrounding method, the file's locals, a global the module
	 * reads or one of the others already use it.
	 */
	public String freshLocal(String preferred, Tree where, Set<String> others) {
		Set<String> taken = new HashSet<>(fileLocals());
		taken.addAll(globalsInUse);
		taken.addAll(others);
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
	 * The Lua name of a local variable or parameter. Java keeps a method's locals apart from the file's private
	 * members and classes, but in Lua they are all locals, so a local that shares a name with one its own member uses
	 * would hide it: {@code int step = n - 1; return step(step);} in a private method {@code step}. Such a local gets
	 * a trailing underscore. Every use of a local asks here, so that its declaration and its uses agree.
	 *
	 * @param where the local's declaration, or a tree of the code it belongs to
	 */
	public String codeLocalName(Element variable, Tree where) {
		String known = codeLocalNames.get(variable);
		if (known != null) {
			return known;
		}
		// The declaration's member decides, so that a use inside a lambda or an anonymous class gets the same name.
		// javac cannot find every local's declaration again, such as one in an anonymous class; the use's member then
		// decides, which is the same one.
		boolean isDeclaration = where instanceof VariableTree declared && variable.equals(element(declared));
		TreePath declaration = isDeclaration ? null : trees.getPath(variable);
		TreePath member = declaration != null ? enclosingMember(declaration) : memberPath(where);
		Set<String> used = fileLocalsUsed.computeIfAbsent(member.getLeaf(), _ -> fileLocalsUsedIn(member));
		String name = names.luaName(variable.getSimpleName().toString());
		while (used.contains(name)) {
			name = name + "_";
		}
		codeLocalNames.put(variable, name);
		return name;
	}

	/** The file-level locals that a member's code uses, by their Lua names. */
	private Set<String> fileLocalsUsedIn(TreePath member) {
		Set<String> used = new HashSet<>();
		new TreePathScanner<Void, Void>() {
			@Override
			public Void visitIdentifier(IdentifierTree node, Void unused) {
				add(fileLocalOf(trees.getElement(getCurrentPath()), true));
				return super.visitIdentifier(node, null);
			}

			@Override
			public Void visitMemberSelect(MemberSelectTree node, Void unused) {
				add(fileLocalOf(trees.getElement(getCurrentPath()), false));
				return super.visitMemberSelect(node, null);
			}

			@Override
			public Void visitMemberReference(MemberReferenceTree node, Void unused) {
				add(fileLocalOf(trees.getElement(getCurrentPath()), false));
				return super.visitMemberReference(node, null);
			}

			private void add(@Nullable String local) {
				if (local != null) {
					used.add(local);
				}
			}
		}.scan(member, null);
		return used;
	}

	/**
	 * The file-level local the Lua reaches a declaration through, or null when it reaches it otherwise. That is a
	 * class's table, a private method or static field, or a member of the plugin class, which are all locals of
	 * main.lua; and for a static member of one of the file's classes used without a qualifier, the class's table.
	 */
	public @Nullable String fileLocalOf(@Nullable Element element, boolean unqualified) {
		if (element == null) {
			return null;
		}
		if (element instanceof TypeElement) {
			return element.getSimpleName().toString();
		}
		boolean member = element.getKind() == ElementKind.METHOD || element.getKind() == ElementKind.FIELD;
		Element owner = element.getEnclosingElement();
		if (isFileLocal(element) || (member && entry != null && entry.equals(owner))) {
			return names.luaName(element.getSimpleName().toString());
		}
		if (unqualified && element.getModifiers().contains(Modifier.STATIC) && owner instanceof TypeElement type
				&& fileTypes.contains(type)) {
			return type.getSimpleName().toString();
		}
		return null;
	}

	/**
	 * Whether a lambda's parameter can become a local of the function around the lambda: no other variable of the
	 * member and no local of the file has its Lua name, so the local hides nothing.
	 */
	public boolean canHoist(VariableTree parameter) {
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
		return enclosingMember(path(where));
	}

	private static TreePath enclosingMember(TreePath path) {
		TreePath member = path;
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
		}.scan(compilationUnit, null);
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
	public String luaMemberName(Tree declaration) {
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

	/** Whether a lambda implements a method that returns nothing, so that its expression body is a statement. */
	public boolean returnsVoid(LambdaExpressionTree lambda) {
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

	public void reject(Rejection rejection) {
		error(start(rejection.tree()), rejection.message());
	}

	// ---- Source positions and javac's model

	public int start(Tree tree) {
		return (int) positions.getStartPosition(compilationUnit, tree);
	}

	public int end(Tree tree) {
		return (int) positions.getEndPosition(compilationUnit, tree);
	}

	public boolean isSynthetic(Tree tree) {
		return positions.getEndPosition(compilationUnit, tree) == NOPOS;
	}

	/** Where a tree is in the source: its file, by its path in the source folder as its package names it, and line. */
	public SourcePosition origin(Tree tree) {
		String file = compilationUnit.getSourceFile().getName().replace('\\', '/').replaceAll(".*/", "");
		String packageName = compilationUnit.getPackage() == null
				? "" : compilationUnit.getPackage().getPackageName().toString();
		String folder = packageName.isEmpty() ? "" : packageName.replace('.', '/') + "/";
		return new SourcePosition(folder + file, (int) compilationUnit.getLineMap().getLineNumber(start(tree)));
	}

	String sourceText(Tree tree) {
		return source.subSequence(start(tree), end(tree)).toString();
	}

	public TreePath path(Tree tree) {
		TreePath path = TreePath.getPath(compilationUnit, tree);
		if (path == null) {
			throw new IllegalStateException("tree not found in its compilation unit: " + tree);
		}
		return path;
	}

	public Element element(Tree tree) {
		Element element = trees.getElement(path(tree));
		if (element == null) {
			throw new IllegalStateException("javac resolved no element for " + tree);
		}
		return element;
	}

	public TypeMirror type(Tree tree) {
		TypeMirror type = trees.getTypeMirror(path(tree));
		if (type == null) {
			throw new IllegalStateException("javac resolved no type for " + tree);
		}
		return type;
	}

	/** The class or interface a type in the source names, such as the one after {@code extends}. */
	TypeElement typeElement(Tree typeTree) {
		if (type(typeTree) instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element) {
			return element;
		}
		throw new IllegalStateException("not a class or interface: " + typeTree);
	}

	private static String packageName(TypeElement type) {
		Element enclosing = type.getEnclosingElement();
		return enclosing instanceof PackageElement packageElement ? packageElement.getQualifiedName().toString() : "";
	}

	public static boolean isStatic(Set<Modifier> flags) {
		return flags.contains(Modifier.STATIC);
	}

	public static boolean isConstructor(MethodTree method) {
		return method.getName().contentEquals("<init>");
	}

	public void error(int position, String message) {
		diagnostics.add(diagnostic(Diagnostic.Severity.ERROR, position, message));
	}

	public void warning(int position, String message) {
		diagnostics.add(diagnostic(Diagnostic.Severity.WARNING, position, message));
	}

	private Diagnostic diagnostic(Diagnostic.Severity severity, int position, String message) {
		return diagnostic(compilationUnit, source, severity, position, message);
	}

	/** A diagnostic at a place in a file, with the line it is on, for the caret under it. */
	public static Diagnostic diagnostic(CompilationUnitTree unit, CharSequence source, Diagnostic.Severity severity,
			long position, String message) {
		long line = unit.getLineMap().getLineNumber(position);
		// A character count, not javac's getColumnNumber, which expands tabs to eight columns
		long column = position - unit.getLineMap().getStartPosition(line) + 1;
		String[] lines = source.toString().split("\\R", -1);
		String sourceLine = line >= 1 && line <= lines.length ? lines[(int) line - 1] : "";
		return new Diagnostic(severity, unit.getSourceFile().getName(), line, column, message, sourceLine, position);
	}
}
