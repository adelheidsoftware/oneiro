package org.adelheid.oneiro.java;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic.Kind;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.adelheid.oneiro.java.core.JavaTypes;
import org.adelheid.oneiro.java.core.UnitLowering;
import org.adelheid.oneiro.java.language.Constructors;
import org.adelheid.oneiro.lowering.CompileResult;
import org.adelheid.oneiro.lowering.DevSourceSet;
import org.adelheid.oneiro.lowering.Diagnostic;
import org.adelheid.oneiro.lowering.LocalNames;
import org.adelheid.oneiro.lowering.LuaModule;
import org.adelheid.oneiro.lowering.PluginFacts;
import org.jspecify.annotations.Nullable;

/**
 * Compiles a plugin's Java sources to Lua modules. javac first checks and types the sources as ordinary Java 25;
 * then each file is lowered as docs/java-subset.md describes. Every problem in every file is reported in one run, and
 * no module is produced while any error remains.
 */
public final class JavaFrontend {

	/**
	 * Where the plugin's code lives, what it compiles against, and what the build says about the plugin.
	 *
	 * @param rootPackage the plugin's root package; its subpackages become folders under {@code src/}
	 * @param classpath what the sources compile against besides the JDK, such as JSpecify and the RS3 bindings
	 * @param plugin the plugin's identifier, permissions and manifest flags
	 * @param mainSources for the dev source set, the main set's files, which are compiled with it and read for what
	 *     their classes are, as the main set's own Lua makes them, but not lowered; none otherwise
	 */
	public record Options(String rootPackage, List<Path> classpath, PluginFacts plugin, List<Path> mainSources) {

		public Options {
			classpath = List.copyOf(classpath);
			mainSources = List.copyOf(mainSources);
		}

		public Options(String rootPackage, List<Path> classpath, PluginFacts plugin) {
			this(rootPackage, classpath, plugin, List.of());
		}

		/** Options for code that is not part of a configured plugin, such as a conformance case. */
		public Options(String rootPackage, List<Path> classpath) {
			this(rootPackage, classpath, PluginFacts.DEFAULT);
		}
	}

	private JavaFrontend() {
	}

	/** Compiles the sources with javac, then lowers them; when javac reports errors, those are the only diagnostics. */
	public static CompileResult compile(List<Path> sources, Options options) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		DiagnosticCollector<JavaFileObject> javacDiagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager files =
				compiler.getStandardFileManager(javacDiagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
			String classpath = options.classpath().stream().map(Path::toString)
					.collect(Collectors.joining(File.pathSeparator));
			List<String> javacOptions =
					List.of("--release", "25", "-proc:none", "-Xlint:none", "-classpath", classpath);
			List<Path> compiled = new ArrayList<>(sources);
			compiled.addAll(options.mainSources());
			JavacTask task = (JavacTask) compiler.getTask(null, files, javacDiagnostics, javacOptions, null,
					files.getJavaFileObjectsFromPaths(compiled));
			List<CompilationUnitTree> units = new ArrayList<>();
			task.parse().forEach(units::add);
			task.analyze();

			List<Diagnostic> diagnostics = javacErrors(javacDiagnostics);
			if (!diagnostics.isEmpty()) {
				return new CompileResult(List.of(), diagnostics);
			}
			return lower(task, units, options);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * The plugin's root package, where the build does not name it: the package of the class that extends
	 * {@code Plugin}, or of the dev source set's {@code DevPlugin} class; null when there is neither.
	 */
	public static @Nullable String rootPackage(JavacTask task, List<CompilationUnitTree> units) {
		DocTrees trees = DocTrees.instance(task);
		JavaTypes types = new JavaTypes(task.getTypes(), task.getElements(), Set.of());
		for (CompilationUnitTree unit : units) {
			for (Tree declaration : unit.getTypeDecls()) {
				if (declaration instanceof ClassTree && trees.getElement(TreePath.getPath(unit, declaration))
						instanceof TypeElement type && (types.isPluginEntry(type) || types.isDevEntry(type))) {
					return task.getElements().getPackageOf(type).getQualifiedName().toString();
				}
			}
		}
		return null;
	}

	/**
	 * Lowers sources that javac has parsed and analysed without errors, as the javac plugin does inside the plugin's
	 * own compilation. The units of the main set's files, for the dev source set, are read for what their classes are,
	 * and only the others are lowered.
	 */
	public static CompileResult lower(JavacTask task, List<CompilationUnitTree> units, Options options) {
		DocTrees trees = DocTrees.instance(task);
		Set<Path> mainFiles = options.mainSources().stream().map(JavaFrontend::normalized).collect(Collectors.toSet());
		List<CompilationUnitTree> own = new ArrayList<>();
		List<CompilationUnitTree> main = new ArrayList<>();
		for (CompilationUnitTree unit : units) {
			(mainFiles.contains(normalized(Path.of(unit.getSourceFile().toUri()))) ? main : own).add(unit);
		}
		Set<TypeElement> ownTypes = pluginTypes(own, trees);
		Set<TypeElement> mainTypes = pluginTypes(main, trees);
		Set<TypeElement> pluginTypes = new HashSet<>(ownTypes);
		pluginTypes.addAll(mainTypes);
		// The main set's classes, records and enums are what its own compile makes them, from its units alone; the
		// units being lowered make only their own objects
		Set<TypeElement> objectTypes = new HashSet<>(objectTypes(main, trees, mainTypes, pluginTypes, null));
		List<MainSetUse> mainSetUses = new ArrayList<>();
		objectTypes.addAll(objectTypes(own, trees, ownTypes, pluginTypes, mainSetUses));
		List<Diagnostic> mainSetErrors = mainSetUses.stream()
				.filter(use -> !objectTypes.contains(use.type()))
				.map(use -> use.diagnostic(trees))
				.toList();
		if (!mainSetErrors.isEmpty()) {
			return new CompileResult(List.of(), mainSetErrors);
		}
		JavaTypes types = new JavaTypes(task.getTypes(), task.getElements(), pluginTypes, objectTypes, ownTypes);
		List<CompilationUnitTree> sorted = new ArrayList<>(own);
		sorted.sort(Comparator.comparing(unit -> unit.getSourceFile().getName()));
		List<Diagnostic> diagnostics = new ArrayList<>();
		List<LuaModule> modules = new ArrayList<>();
		for (CompilationUnitTree unit : sorted) {
			UnitLowering lowering = unitLowering(unit, trees, task, types, options);
			LuaModule module = lowering.lower();
			diagnostics.addAll(lowering.diagnostics());
			if (module != null) {
				modules.add(module);
			}
		}
		CompileResult result = new CompileResult(modules, diagnostics);
		return result.hasErrors() ? new CompileResult(List.of(), diagnostics) : result;
	}

	/**
	 * A lowering of one file, ready to run. A local whose name equals a helper the module uses, or a Lua global it
	 * reads such as {@code config}, would hide it, so when that happens the file is lowered a second time with those
	 * names taken.
	 */
	private static UnitLowering unitLowering(CompilationUnitTree unit, DocTrees trees, JavacTask task, JavaTypes types,
			Options options) {
		UnitLowering first =
				new UnitLowering(unit, trees, task.getElements(), types, new LocalNames(Set.of()), options, null);
		first.lower();
		Set<String> taken = new HashSet<>(first.globals());
		first.helpers().forEach(helper -> taken.add(helper.luaName()));
		Set<String> clashes = new HashSet<>(declaredNames(unit));
		clashes.retainAll(taken);
		return new UnitLowering(unit, trees, task.getElements(), types, new LocalNames(clashes), options,
				first.helpers());
	}

	/** The names of every local, parameter and private method declared in the file. */
	private static Set<String> declaredNames(CompilationUnitTree unit) {
		Set<String> found = new HashSet<>();
		new TreeScanner<Void, Void>() {
			@Override
			public Void visitVariable(VariableTree node, Void unused) {
				found.add(node.getName().toString());
				return super.visitVariable(node, null);
			}

			@Override
			public Void visitMethod(MethodTree node, Void unused) {
				found.add(node.getName().toString());
				return super.visitMethod(node, null);
			}
		}.scan(unit, null);
		return found;
	}

	/** Every class declared in the plugin's sources; calls into them are plugin calls, not library calls. */
	private static Set<TypeElement> pluginTypes(List<CompilationUnitTree> units, DocTrees trees) {
		Set<TypeElement> found = new HashSet<>();
		for (CompilationUnitTree unit : units) {
			new TreeScanner<Void, Void>() {
				@Override
				public Void visitClass(ClassTree node, Void unused) {
					if (trees.getElement(TreePath.getPath(unit, node)) instanceof TypeElement type) {
						found.add(type);
					}
					return super.visitClass(node, null);
				}
			}.scan(unit, null);
		}
		return found;
	}

	/**
	 * The plugin's classes, enums and records whose values are Lua objects. A class has objects when its members give
	 * it some (see {@link #hasObjects}), when code creates one, or when another class extends it; otherwise it is a
	 * module of its static members. An enum's constants become objects when the enum has anything but plain constants
	 * (fields, constructors, methods or constant bodies), or when code reads their order: through {@code ordinal()} or
	 * {@code compareTo()}, or by sorting in natural order. Plain constants are strings, which would sort by name. A
	 * record becomes a class when it declares members of its own or implements an interface; otherwise it is a plain
	 * table.
	 *
	 * @param owned the classes the units declare, which code that creates or extends them, or reads their order, can
	 *     give objects
	 * @param pluginTypes every class of the plugin's sources, the main set's among them
	 * @param elsewhere where the units would give objects to a class of the plugin's that they do not declare, which
	 *     only its own units decide about; null to leave those out
	 */
	private static Set<TypeElement> objectTypes(List<CompilationUnitTree> units, DocTrees trees,
			Set<TypeElement> owned, Set<TypeElement> pluginTypes, @Nullable List<MainSetUse> elsewhere) {
		Set<TypeElement> found = new HashSet<>();
		for (CompilationUnitTree unit : units) {
			new TreePathScanner<Void, Void>() {
				@Override
				public Void visitClass(ClassTree node, Void unused) {
					boolean object = (node.getKind() == Tree.Kind.ENUM && !isSimpleEnum(node, trees, getCurrentPath()))
							|| (node.getKind() == Tree.Kind.RECORD && hasOwnMembers(node, trees, getCurrentPath()))
							|| (node.getKind() == Tree.Kind.CLASS && hasObjects(node, trees, getCurrentPath()));
					if (object && trees.getElement(getCurrentPath()) instanceof TypeElement type) {
						found.add(type);
					}
					// A subclass's object is one of its superclass's too
					if (node.getExtendsClause() != null) {
						noteObjects(trees.getTypeMirror(new TreePath(getCurrentPath(), node.getExtendsClause())), node);
					}
					return super.visitClass(node, null);
				}

				// new Shape(), and new Shape() { ... }, whose object is one of Shape's too
				@Override
				public Void visitNewClass(NewClassTree node, Void unused) {
					noteObjects(trees.getTypeMirror(new TreePath(getCurrentPath(), node.getIdentifier())), node);
					TypeMirror created = trees.getTypeMirror(getCurrentPath());
					String name = created instanceof DeclaredType declared
							? ((TypeElement) declared.asElement()).getQualifiedName().toString()
							: "";
					// A TreeMap or TreeSet without a comparator orders its keys naturally
					if ((name.equals("java.util.TreeMap") || name.equals("java.util.TreeSet"))
							&& node.getArguments().isEmpty()) {
						note(firstTypeArgument(trees.getTypeMirror(getCurrentPath())), node);
					}
					return super.visitNewClass(node, null);
				}

				@Override
				public Void visitMemberReference(MemberReferenceTree node, Void unused) {
					if (node.getMode() == MemberReferenceTree.ReferenceMode.NEW) {
						noteObjects(trees.getTypeMirror(new TreePath(getCurrentPath(), node.getQualifierExpression())),
								node);
					}
					return super.visitMemberReference(node, null);
				}

				@Override
				public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
					if (node.getMethodSelect() instanceof MemberSelectTree select
							&& (select.getIdentifier().contentEquals("ordinal")
									|| select.getIdentifier().contentEquals("compareTo"))) {
						note(trees.getTypeMirror(new TreePath(getCurrentPath(), select.getExpression())), node);
					}
					// A sort in natural order: Collections.sort(list), list.sort(null), stream.sorted(),
					// Comparator.naturalOrder() and reverseOrder()
					if (node.getMethodSelect() instanceof MemberSelectTree select) {
						String method = select.getIdentifier().toString();
						TypeMirror receiver = trees.getTypeMirror(new TreePath(getCurrentPath(),
								select.getExpression()));
						boolean natural = (method.equals("sort") && node.getArguments().size() == 1
								&& node.getArguments().getFirst() instanceof LiteralTree literal
								&& literal.getValue() == null)
								|| (method.equals("sorted") && node.getArguments().isEmpty());
						if (natural) {
							note(firstTypeArgument(receiver), node);
						}
						if (method.equals("sort") && node.getArguments().size() == 1
								&& trees.getElement(getCurrentPath()) instanceof ExecutableElement sort
								&& sort.getEnclosingElement() instanceof TypeElement owner
								&& owner.getQualifiedName().contentEquals("java.util.Collections")) {
							note(firstTypeArgument(trees.getTypeMirror(
									new TreePath(getCurrentPath(), node.getArguments().getFirst()))), node);
						}
						if ((method.equals("naturalOrder") || method.equals("reverseOrder"))
								&& node.getArguments().isEmpty()) {
							note(firstTypeArgument(trees.getTypeMirror(getCurrentPath())), node);
						}
					}
					return super.visitMethodInvocation(node, null);
				}

				// Marks an enum of the plugin's as one whose order code reads, where these units declare it
				private void note(@Nullable TypeMirror type, Tree where) {
					if (type instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element
							&& element.getKind() == ElementKind.ENUM) {
						mark(element, where, DevSourceSet.enumOrder(element.getSimpleName().toString()));
					}
				}

				// Marks a class of the plugin's as one whose objects code makes, where these units declare it
				private void noteObjects(@Nullable TypeMirror type, Tree where) {
					if (type instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element
							&& element.getKind() == ElementKind.CLASS) {
						mark(element, where, DevSourceSet.classObjects(element.getSimpleName().toString()));
					}
				}

				private void mark(TypeElement element, Tree where, String elsewhereMessage) {
					if (owned.contains(element)) {
						found.add(element);
					} else if (elsewhere != null && pluginTypes.contains(element)) {
						elsewhere.add(new MainSetUse(unit, where, element, elsewhereMessage));
					}
				}

				private @Nullable TypeMirror firstTypeArgument(@Nullable TypeMirror type) {
					return type instanceof DeclaredType declared && !declared.getTypeArguments().isEmpty()
							? declared.getTypeArguments().getFirst()
							: null;
				}
			}.scan(unit, null);
		}
		return found;
	}

	/**
	 * Where dev code would give objects to a class of the main set's, or read an enum's order, which only the main set
	 * decides.
	 *
	 * @param message why it cannot, for when the main set's Lua does not
	 */
	private record MainSetUse(CompilationUnitTree unit, Tree where, TypeElement type, String message) {

		Diagnostic diagnostic(DocTrees trees) {
			try {
				return UnitLowering.diagnostic(unit, unit.getSourceFile().getCharContent(true),
						Diagnostic.Severity.ERROR, trees.getSourcePositions().getStartPosition(unit, where), message);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}

	/** A file's path as both javac and the build give it, so that the two compare. */
	private static Path normalized(Path file) {
		return file.toAbsolutePath().normalize();
	}

	/**
	 * Whether a class's members give it objects: it is abstract or extends another, or it has an instance field,
	 * block or method, or a constructor other than the private empty one that only stops a utility class from being
	 * instantiated.
	 */
	private static boolean hasObjects(ClassTree type, DocTrees trees, TreePath path) {
		if (type.getExtendsClause() != null || type.getModifiers().getFlags().contains(Modifier.ABSTRACT)) {
			return true;
		}
		CompilationUnitTree unit = path.getCompilationUnit();
		for (Tree member : type.getMembers()) {
			// What javac adds, such as the default constructor, has no source of its own
			if (trees.getSourcePositions().getEndPosition(unit, member) < 0) {
				continue;
			}
			boolean instance = switch (member) {
				case VariableTree field -> !field.getModifiers().getFlags().contains(Modifier.STATIC);
				case BlockTree block -> !block.isStatic();
				case MethodTree method when method.getName().contentEquals("<init>") ->
					!method.getModifiers().getFlags().contains(Modifier.PRIVATE) || !method.getParameters().isEmpty()
							|| method.getBody().getStatements().stream().anyMatch(statement ->
									trees.getSourcePositions().getEndPosition(unit, statement) >= 0
											&& !Constructors.isSuperCall(statement));
				case MethodTree method -> !method.getModifiers().getFlags().contains(Modifier.STATIC);
				default -> false;
			};
			if (instance) {
				return true;
			}
		}
		return false;
	}

	/** Whether a record declares methods, constructors, static fields or nested types, or implements an interface. */
	private static boolean hasOwnMembers(ClassTree record, DocTrees trees, TreePath path) {
		if (!record.getImplementsClause().isEmpty()) {
			return true;
		}
		for (Tree member : record.getMembers()) {
			// The accessors and the canonical constructor javac adds have no source of their own
			boolean own = trees.getSourcePositions().getEndPosition(path.getCompilationUnit(), member) >= 0
					&& switch (member) {
						case MethodTree _, ClassTree _ -> true;
						case VariableTree field -> field.getModifiers().getFlags().contains(Modifier.STATIC);
						default -> false;
					};
			if (own) {
				return true;
			}
		}
		return false;
	}

	/** Whether an enum declares only constants, each without arguments or a body, and implements nothing. */
	private static boolean isSimpleEnum(ClassTree type, DocTrees trees, TreePath path) {
		if (!type.getImplementsClause().isEmpty()) {
			return false;
		}
		for (Tree member : type.getMembers()) {
			if (trees.getSourcePositions().getEndPosition(path.getCompilationUnit(), member) < 0) {
				// The constructor javac adds, which has no source of its own
				continue;
			}
			if (!(member instanceof VariableTree constant)
					|| trees.getElement(new TreePath(path, member)).getKind() != ElementKind.ENUM_CONSTANT
					|| !(constant.getInitializer() instanceof NewClassTree creation)
					|| !creation.getArguments().isEmpty() || creation.getClassBody() != null) {
				return false;
			}
		}
		return true;
	}

	private static List<Diagnostic> javacErrors(DiagnosticCollector<JavaFileObject> collected) {
		List<Diagnostic> errors = new ArrayList<>();
		// javac's own diagnostics, whose class shares its simple name with this package's Diagnostic
		for (var diagnostic : collected.getDiagnostics()) {
			if (diagnostic.getKind() != Kind.ERROR) {
				continue;
			}
			JavaFileObject file = diagnostic.getSource();
			String name = file == null ? "javac" : file.getName();
			String source = content(file);
			long position = diagnostic.getPosition();
			// A character count, as in Oneiro's own diagnostics; javac's column expands tabs to eight columns
			long column = position < 0 ? 1 : position - source.lastIndexOf('\n', (int) position - 1);
			errors.add(new Diagnostic(Diagnostic.Severity.ERROR, name, diagnostic.getLineNumber(), column,
					diagnostic.getMessage(Locale.ROOT), line(source, diagnostic.getLineNumber()), position));
		}
		return errors;
	}

	private static String content(@Nullable JavaFileObject file) {
		if (file == null) {
			return "";
		}
		try {
			return file.getCharContent(true).toString();
		} catch (IOException e) {
			return "";
		}
	}

	private static String line(String source, long line) {
		List<String> lines = source.lines().toList();
		return line >= 1 && line <= lines.size() ? lines.get((int) line - 1) : "";
	}
}
