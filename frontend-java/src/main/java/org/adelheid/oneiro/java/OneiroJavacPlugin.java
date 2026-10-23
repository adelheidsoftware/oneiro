package org.adelheid.oneiro.java;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;
import org.adelheid.oneiro.lua.LuaPrinter;
import org.adelheid.oneiro.plugin.CompilerSettings;
import org.jspecify.annotations.Nullable;

/**
 * Oneiro as a javac plugin, {@code -Xplugin:"Oneiro <settings URI>"}, so that the plugin's own {@code compileJava}
 * compiles it to Lua: once javac has analysed every class, Oneiro lowers them, reports each rejection as a javac
 * error at its place, so that it fails the build at the same step as a type error and shows in the IDE like one, and,
 * when nothing is wrong, writes the Lua.
 *
 * <p>javac must run with {@code -XDcompilePolicy=simple}, as the Gradle plugin sets it: by default javac lowers and
 * writes each class before it analyses the next, which rewrites the trees Oneiro reads.
 */
public final class OneiroJavacPlugin implements Plugin {

	@Override
	public String getName() {
		return "Oneiro";
	}

	@Override
	public void init(JavacTask task, String... args) {
		if (args.length != 1) {
			throw new IllegalArgumentException("Oneiro's javac plugin takes one argument, the URI of its settings file,"
					+ " which the Gradle plugin writes: -Xplugin:\"Oneiro file:/...\".");
		}
		try {
			task.addTaskListener(new Listener(task, CompilerSettings.read(Path.of(URI.create(args[0])))));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Collects the compiled units and lowers them when the last of their classes has been analysed. */
	private static final class Listener implements TaskListener {

		private final JavacTask task;
		private final CompilerSettings settings;
		private final List<CompilationUnitTree> units = new ArrayList<>();
		private final Set<Element> pending = new HashSet<>();
		private boolean lowered;

		Listener(JavacTask task, CompilerSettings settings) {
			this.task = task;
			this.settings = settings;
		}

		@Override
		public void finished(TaskEvent event) {
			switch (event.getKind()) {
				case PARSE -> units.add(event.getCompilationUnit());
				case ENTER -> {
					CompilationUnitTree unit = event.getCompilationUnit();
					Trees trees = Trees.instance(task);
					for (Tree declaration : unit.getTypeDecls()) {
						if (declaration instanceof ClassTree) {
							Element element = trees.getElement(TreePath.getPath(unit, declaration));
							if (element != null) {
								pending.add(element);
							}
						}
					}
				}
				case ANALYZE -> {
					TypeElement analysed = event.getTypeElement();
					if (analysed != null) {
						pending.remove(analysed);
					}
					if (pending.isEmpty() && !lowered) {
						lowered = true;
						lower();
					}
				}
				default -> {
				}
			}
		}

		private void lower() {
			Trees trees = Trees.instance(task);
			String rootPackage = settings.rootPackage().isEmpty()
					? JavaFrontend.rootPackage(task, units)
					: settings.rootPackage();
			if (rootPackage == null) {
				if (!units.isEmpty()) {
					trees.printMessage(Kind.ERROR, "No class extends Plugin, so Oneiro cannot tell the plugin's root"
							+ " package; name it in the build: oneiro { rootPackage = \"com.example.herbs\" }.",
							units.getFirst(), units.getFirst());
				}
				return;
			}
			PluginFacts facts = new PluginFacts(settings.id(), settings.permissions(), settings.flags(), settings.dev());
			CompileResult result = JavaFrontend.lower(task, units,
					new JavaFrontend.Options(rootPackage, List.of(), facts));
			boolean failed = false;
			for (Diagnostic diagnostic : result.diagnostics()) {
				boolean error = diagnostic.severity() == Diagnostic.Severity.ERROR || settings.warningsAsErrors();
				failed |= error;
				report(trees, diagnostic, error ? Kind.ERROR : Kind.WARNING);
			}
			if (!failed) {
				write(result.modules());
			}
		}

		/** Reports a diagnostic at the tree that starts where it points, or the innermost one around that place. */
		private void report(Trees trees, Diagnostic diagnostic, Kind kind) {
			for (CompilationUnitTree unit : units) {
				if (unit.getSourceFile().getName().equals(diagnostic.file())) {
					trees.printMessage(kind, diagnostic.message(), treeAt(trees, unit, diagnostic.position()), unit);
					return;
				}
			}
		}

		private static Tree treeAt(Trees trees, CompilationUnitTree unit, long position) {
			SourcePositions positions = trees.getSourcePositions();
			Tree[] best = {unit};
			new TreeScanner<Void, Void>() {
				@Override
				public @Nullable Void scan(@Nullable Tree tree, @Nullable Void unused) {
					if (tree == null) {
						return null;
					}
					long start = positions.getStartPosition(unit, tree);
					long end = positions.getEndPosition(unit, tree);
					if (start <= position && position < end) {
						// Deeper trees around the place are closer to it; one that starts there is the place itself
						if (start == position || positions.getStartPosition(unit, best[0]) != position) {
							best[0] = tree;
						}
						return super.scan(tree, null);
					}
					return null;
				}
			}.scan(unit, null);
			return best[0];
		}

		/** Replaces the Lua of an earlier build with this one's; the folder holds only what Oneiro writes there. */
		private void write(List<LuaModule> modules) {
			Path output = settings.luaOutput();
			try {
				if (Files.isDirectory(output)) {
					try (Stream<Path> old = Files.walk(output)) {
						for (Path path : old.sorted(Comparator.reverseOrder()).toList()) {
							if (Files.isDirectory(path) ? !path.equals(output) : path.toString().endsWith(".lua")) {
								Files.delete(path);
							}
						}
					}
				}
				for (LuaModule module : modules) {
					Path file = output.resolve(module.path());
					Files.createDirectories(file.getParent());
					Files.writeString(file, LuaPrinter.print(module.chunk()).text(), StandardCharsets.UTF_8);
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}
}
