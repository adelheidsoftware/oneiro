package org.adelheid.oneiro.java;

import java.util.List;

/**
 * What compiling a plugin's Java sources produced. When any diagnostic is an error, no modules are produced, so that
 * no Lua is written while a problem remains.
 *
 * @param modules one Lua module per source file, in source path order
 * @param diagnostics every error and warning, javac's own included, in source order
 */
public record CompileResult(List<LuaModule> modules, List<Diagnostic> diagnostics) {

	public CompileResult {
		modules = List.copyOf(modules);
		diagnostics = List.copyOf(diagnostics);
	}

	public boolean hasErrors() {
		return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == Diagnostic.Severity.ERROR);
	}
}
