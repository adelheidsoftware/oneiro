package org.adelheid.oneiro.java;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LiteralTree;
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
	 */
	public record Options(String rootPackage, List<Path> classpath, PluginFacts plugin) {

		public Options {
			classpath = List.copyOf(classpath);
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
			JavacTask task = (JavacTask) compiler.getTask(null, files, javacDiagnostics, javacOptions, null,
					files.getJavaFileObjectsFromPaths(sources));
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
	 * own compilation.
	 */
	public static CompileResult lower(JavacTask task, List<CompilationUnitTree> units, Options options) {
		DocTrees trees = DocTrees.instance(task);
		Set<TypeElement> pluginTypes = pluginTypes(units, trees);
		JavaTypes types = new JavaTypes(task.getTypes(), task.getElements(), pluginTypes,
				objectTypes(units, trees, pluginTypes), options.plugin().dev() ? options.rootPackage() : null);
		List<CompilationUnitTree> sorted = new ArrayList<>(units);
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
	 * The plugin's enums and records that become Lua classes. An enum's constants become objects when the enum has
	 * anything but plain constants (fields, constructors, methods or constant bodies), or when code reads their order:
	 * through {@code ordinal()} or {@code compareTo()}, or by sorting in natural order. Plain constants are strings,
	 * which would sort by name. A record becomes a class when it declares members of its own or implements an
	 * interface; otherwise it is a plain table.
	 */
	private static Set<TypeElement> objectTypes(List<CompilationUnitTree> units, DocTrees trees,
			Set<TypeElement> pluginTypes) {
		Set<TypeElement> found = new HashSet<>();
		for (CompilationUnitTree unit : units) {
			new TreePathScanner<Void, Void>() {
				@Override
				public Void visitClass(ClassTree node, Void unused) {
					boolean object = (node.getKind() == Tree.Kind.ENUM && !isSimpleEnum(node, trees, getCurrentPath()))
							|| (node.getKind() == Tree.Kind.RECORD && hasOwnMembers(node, trees, getCurrentPath()));
					if (object && trees.getElement(getCurrentPath()) instanceof TypeElement type) {
						found.add(type);
					}
					return super.visitClass(node, null);
				}

				@Override
				public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
					if (node.getMethodSelect() instanceof MemberSelectTree select
							&& (select.getIdentifier().contentEquals("ordinal")
									|| select.getIdentifier().contentEquals("compareTo"))) {
						note(trees.getTypeMirror(new TreePath(getCurrentPath(), select.getExpression())));
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
							note(firstTypeArgument(receiver));
						}
						if (method.equals("sort") && node.getArguments().size() == 1
								&& trees.getElement(getCurrentPath()) instanceof ExecutableElement sort
								&& sort.getEnclosingElement() instanceof TypeElement owner
								&& owner.getQualifiedName().contentEquals("java.util.Collections")) {
							note(firstTypeArgument(trees.getTypeMirror(
									new TreePath(getCurrentPath(), node.getArguments().getFirst()))));
						}
						if ((method.equals("naturalOrder") || method.equals("reverseOrder"))
								&& node.getArguments().isEmpty()) {
							note(firstTypeArgument(trees.getTypeMirror(getCurrentPath())));
						}
					}
					return super.visitMethodInvocation(node, null);
				}

				// A TreeMap or TreeSet without a comparator orders its keys naturally
				@Override
				public Void visitNewClass(NewClassTree node, Void unused) {
					TypeMirror created = trees.getTypeMirror(getCurrentPath());
					String name = created instanceof DeclaredType declared
							? ((TypeElement) declared.asElement()).getQualifiedName().toString()
							: "";
					if ((name.equals("java.util.TreeMap") || name.equals("java.util.TreeSet"))
							&& node.getArguments().isEmpty()) {
						note(firstTypeArgument(trees.getTypeMirror(getCurrentPath())));
					}
					return super.visitNewClass(node, null);
				}

				// Marks an enum of the plugin's as one whose order code reads
				private void note(@Nullable TypeMirror type) {
					if (type instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element
							&& element.getKind() == ElementKind.ENUM && pluginTypes.contains(element)) {
						found.add(element);
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
