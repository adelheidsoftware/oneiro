package org.adelheid.oneiro.bindings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.adelheid.rspc.plugin.LuaLibraries;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Generates the bindings from the real stubs of rs3-api-types, when it is checked out next to Oneiro, in
 * {@code ../rs3-api-types}, and checks that they generate without a warning: every stub line is understood and every
 * override still matches. The bindings must also compile.
 */
class RealStubsTest {

	/** The folder of the stubs' definitions in a checkout of rs3-api-types. */
	private static final String LIBRARY = "generated/library";

	@Test
	void realStubsGenerateBindingsThatCompile() throws IOException {
		String location = System.getProperty("oneiro.stubs", "");
		Path stubs = Path.of(location);
		Assumptions.assumeTrue(!location.isEmpty() && Files.isDirectory(stubs.resolve(LIBRARY)),
				"rs3-api-types is not checked out next to Oneiro.");

		for (BindingsGenerator.Language language : BindingsGenerator.Language.values()) {
			BindingsGenerator.Result result = BindingsGenerator.generate(stubs,
					Path.of("build", "real-bindings-" + language.name().toLowerCase(Locale.ROOT)), language);
			// Lines of the overrides file the stubs have caught up with: they change nothing, and can be deleted
			if (language == BindingsGenerator.Language.JAVA) {
				result.notes().forEach(System.out::println);
			}
			assertTrue(Files.size(result.classesJar()) > 0);
			assertTrue(Files.size(result.sourcesJar()) > 0);
			assertEquals(List.of(), result.warnings());
		}
	}

	/**
	 * The bindings of real Lua against the real stubs, when both are checked out next to Oneiro: Zyklib, whose types
	 * name the RS3 API's, and Blueprint-Helper's modules, annotated in part, which must generate without a warning and
	 * compile.
	 */
	@Test
	void realLibrariesGenerateBindingsThatCompile() throws IOException {
		Path stubs = Path.of(System.getProperty("oneiro.stubs", ""));
		Path zyklib = Path.of(System.getProperty("oneiro.zyklib", "")).resolve("src");
		Path blueprintHelper = Path.of(System.getProperty("oneiro.blueprintHelper", "")).resolve("src");
		Assumptions.assumeTrue(Files.isDirectory(stubs.resolve(LIBRARY)) && Files.isDirectory(zyklib)
				&& Files.isDirectory(blueprintHelper),
				"rs3-api-types, Zyklib and Blueprint-Helper are not all checked out next to Oneiro.");

		BindingsGenerator.Result result = BindingsGenerator.generate(stubs, Path.of("build", "real-library-bindings"),
				Overrides.bundled(), BindingsGenerator.Language.JAVA, List.of(
						new BindingsGenerator.Library("zyklib", "org.adelheid.oneiro.lib.zyklib", zyklib, "",
								Path.of("src", "test", "libraries", "zyklib-overrides.txt"),
								LuaLibraries.Use.VENDORED),
						new BindingsGenerator.Library("blueprint", "org.adelheid.oneiro.lib.blueprint", blueprintHelper,
								"src/", null, LuaLibraries.Use.VENDORED)));
		for (BindingsGenerator.LibraryResult library : result.libraries()) {
			assertTrue(Files.size(library.classesJar()) > 0);
			assertEquals(List.of(), library.warnings(), library.name());
		}
	}
}
