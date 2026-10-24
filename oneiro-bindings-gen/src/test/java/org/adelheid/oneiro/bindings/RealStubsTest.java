package org.adelheid.oneiro.bindings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Generates the bindings from the real runescape-api-stubs, when they are checked out next to Oneiro, in
 * {@code ../runescape-api-stubs}, and checks that they generate without a warning: every stub line is understood and
 * every override still matches. The bindings must also compile.
 */
class RealStubsTest {

	@Test
	void realStubsGenerateBindingsThatCompile() throws IOException {
		String location = System.getProperty("oneiro.stubs", "");
		Path stubs = Path.of(location);
		Assumptions.assumeTrue(!location.isEmpty() && Files.isRegularFile(stubs.resolve("types.lua")),
				"runescape-api-stubs is not checked out next to Oneiro.");

		BindingsGenerator.Result result = BindingsGenerator.generate(stubs, Path.of("build", "real-bindings"));
		// Lines of the overrides file the stubs have caught up with: they change nothing, and can be deleted
		result.notes().forEach(System.out::println);
		assertTrue(Files.size(result.classesJar()) > 0);
		assertTrue(Files.size(result.sourcesJar()) > 0);
		assertEquals(List.of(), result.warnings());
	}
}
