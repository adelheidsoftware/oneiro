package org.adelheid.oneiro.bindings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Generates the bindings from the small stubs fixture in {@code src/test/stubs}, which mimics runescape-api-stubs, and
 * checks their shape: a file that uses every kind of member compiles against them, the jars are reproducible, and the
 * annotations that tell Oneiro the Lua shape are where they belong.
 */
class FixtureStubsTest {

	private static final Path STUBS = Path.of("src", "test", "stubs");
	private static BindingsGenerator.Result result;
	private static BindingsGenerator.Result kotlinResult;

	@BeforeAll
	static void generate() throws IOException {
		result = BindingsGenerator.generate(STUBS, Path.of("build", "fixture-bindings"),
				BindingsGenerator.Language.JAVA);
		kotlinResult = BindingsGenerator.generate(STUBS, Path.of("build", "fixture-bindings-kotlin"),
				BindingsGenerator.Language.KOTLIN);
	}

	/** Both shapes compile, and Java code compiles against either, so the overloads Kotlin needs clash nowhere. */
	@ParameterizedTest
	@EnumSource(BindingsGenerator.Language.class)
	void everyShapeCompiles(BindingsGenerator.Language language) throws IOException, URISyntaxException {
		BindingsGenerator.Result bindings = language == BindingsGenerator.Language.JAVA ? result : kotlinResult;
		Path usage = Path.of(Objects.requireNonNull(getClass().getResource("/usage/Usage.java")).toURI());
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		String shape = language.name().toLowerCase(Locale.ROOT);
		Path output = Files.createDirectories(Path.of("build", "fixture-usage-" + shape));
		String classpath = Stream.concat(Stream.of(bindings.classesJar().toString()),
				Stream.of(System.getProperty("java.class.path").split(File.pathSeparator)))
				.collect(Collectors.joining(File.pathSeparator));
		try (StandardJavaFileManager files =
				compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
			boolean compiled = compiler.getTask(null, files, diagnostics,
					List.of("--release", "25", "-proc:none", "-d", output.toString(), "-classpath", classpath), null,
					files.getJavaFileObjectsFromPaths(List.of(usage))).call();
			assertTrue(compiled, () -> diagnostics.getDiagnostics().stream()
					.map(diagnostic -> diagnostic.getLineNumber() + ": " + diagnostic.getMessage(Locale.ROOT))
					.collect(Collectors.joining("\n")));
		}
	}

	/**
	 * A member that takes a hook types the callback by the hook: for Java through one generic method, and for Kotlin,
	 * which converts a lambda only to an interface the parameter names, through one method for each callback.
	 */
	@Test
	void hooksTypeTheirCallbacks() throws IOException {
		String java = source("ui.java");
		assertTrue(java.contains("public native <C> void Subscribe(ui.Hook<C, ?> hook, C callback);"));
		String kotlin = source(kotlinResult, "ui.java");
		assertFalse(kotlin.contains("<C> void Subscribe"));
		assertTrue(kotlin.contains("public native void Subscribe(ui.Hook<ui.Hook.Pointer, ?> hook,"
				+ " ui.Hook.Pointer callback);"));
		assertTrue(kotlin.contains("public native void Subscribe(ui.Hook<ui.Hook.Pointer.Stopping, ?> hook,"
				+ " ui.Hook.Pointer.Stopping callback);"));
		assertTrue(kotlin.contains("public native void Subscribe(ui.Hook<ui.Hook.Notify, ?> hook, String id,"
				+ " ui.Hook.Notify callback);"));
		// A hook without a callback is any hook in both
		assertTrue(java.contains("public native void Unsubscribe(ui.Hook<?, ?> hook);"));
		assertTrue(kotlin.contains("public native void Unsubscribe(ui.Hook<?, ?> hook);"));
	}

	/** A table's names Java cannot use are left out with one warning for the table, not one for each name. */
	@Test
	void namesJavaCannotUseAreCountedPerTable() {
		List<String> npcs = result.warnings().stream()
				.filter(warning -> warning.startsWith("id.NPC") || warning.startsWith("config.NPC"))
				.toList();
		// 2ND_GUARD is a Java name after the prefix, RS3_2ND_GUARD; a hyphen never is
		String reasons = " leaves out 3 names Java cannot use: 1 not a Java name (such as GUARD-CAPTAIN), 1 repeated"
				+ " (such as MAN), 1 with an ID that does not fit an int (such as HUGE)";
		assertEquals(List.of("id.NPC" + reasons + "; use their numeric IDs instead.",
				"config.NPC" + reasons + "; look them up with FromID and their numeric IDs instead."), npcs);
	}

	@Test
	void jarsAreReproducible() throws IOException {
		BindingsGenerator.Result again = BindingsGenerator.generate(STUBS, Path.of("build", "fixture-bindings-again"),
				BindingsGenerator.Language.JAVA);
		assertEquals(-1, Files.mismatch(result.classesJar(), again.classesJar()));
		assertEquals(-1, Files.mismatch(result.sourcesJar(), again.sourcesJar()));
	}

	/**
	 * A fix upstream needs no change to the overrides: a line the stubs now agree with, or a skip of a member they no
	 * longer have, becomes a note for Oneiro's tests instead of a warning, while a line still needed stays silent.
	 */
	@Test
	void overridesTheStubsCaughtUpWithAreNotes() throws IOException {
		String caughtUp = """
				nullable Player.GetTrackingID
				read-only PluginResource
				type PluginResource.path String
				skip Player.Retired
				element-table CoordGrid GridPart level x z
				element-table HTTPRequest RequestPart data notAField
				field PluginResource.loaded Bool
				field CoordGrid.plane Int
				nullable Player.GetEntity
				""";
		Overrides overrides;
		try (InputStream bundled = Objects.requireNonNull(Overrides.class.getResourceAsStream("overrides.txt"))) {
			String text = new String(bundled.readAllBytes(), StandardCharsets.UTF_8) + caughtUp;
			overrides = Overrides.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
		}
		BindingsGenerator.Result run = BindingsGenerator.generate(STUBS, Path.of("build", "fixture-bindings-caught-up"),
				overrides, BindingsGenerator.Language.JAVA, List.of());
		List<String> notes = run.notes().stream()
				.map(note -> note.replaceFirst("^overrides\\.txt:\\d+: ", ""))
				.toList();
		assertTrue(notes.containsAll(List.of(
				"nullable Player.GetTrackingID is no longer needed: the stubs now say the same.",
				"read-only PluginResource is no longer needed: the stubs now say the same.",
				"type PluginResource.path is no longer needed: the stubs now say the same.",
				"skip Player.Retired is no longer needed: the stubs no longer have it.",
				"element-table CoordGrid is no longer needed: the stubs now say the same.",
				"field PluginResource.loaded is no longer needed: the stubs now say the same.")), notes::toString);
		assertTrue(notes.stream().noneMatch(note -> note.contains("Player.GetEntity") || note.contains("HTTPRequest")
				|| note.contains("CoordGrid.plane")), notes::toString);
		// A field the stubs lack is bound
		assertTrue(source(run, "CoordGrid.java").contains("public int plane;"));
		List<String> warnings = run.warnings();
		assertTrue(warnings.stream().noneMatch(warning -> warning.contains("Player.Retired")
				|| warning.contains("PluginResource") || warning.contains("Player.GetTrackingID")
				|| warning.contains("CoordGrid.plane")), warnings::toString);
		// Stubs that name only some of the elements changed in a way the line cannot follow, which needs a look
		String partial = "element-table HTTPRequest is left as the stubs give it, which name some of its elements as"
				+ " fields (data) but not all";
		assertTrue(warnings.stream().anyMatch(warning -> warning.contains(partial)), warnings::toString);
	}

	/**
	 * A class whose {@code new} the overrides skip is a type the client hands out, with no constructor, and a note adds
	 * its sentence to the Javadoc of the class or member it names; one that names nothing is a warning.
	 */
	@Test
	void skippedConstructorsAndNotes() throws IOException {
		String lines = """
				skip ui.Text.new
				note ui.Text Made by the client only.
				note ui.Component.SetPos Moves it the other way.
				note ui.Component.SetPos Twice as far.
				note ui.Text.Missing Matches nothing.
				""";
		Overrides overrides;
		try (InputStream bundled = Objects.requireNonNull(Overrides.class.getResourceAsStream("overrides.txt"))) {
			String text = new String(bundled.readAllBytes(), StandardCharsets.UTF_8) + lines;
			overrides = Overrides.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
		}
		BindingsGenerator.Result run = BindingsGenerator.generate(STUBS, Path.of("build", "fixture-bindings-notes"),
				overrides, BindingsGenerator.Language.JAVA, List.of());
		String ui = source(run, "ui.java");
		assertTrue(ui.contains("\t * <p>In the client: Made by the client only.\n\t *\n\t * <p>Lua: values of the type"
				+ " {@code ui.Text}, which the client hands out.\n\t */\n\t@LuaType(\"ui.Text\")"), ui);
		assertFalse(ui.contains("public Text(ui.Component parent)"), "the skipped new is no constructor");
		assertTrue(ui.contains("\t\t * <p>In the client: Moves it the other way.\n\t\t *\n\t\t * <p>In the client:"
				+ " Twice as far.\n\t\t *\n\t\t * <p>Lua: {@code component:SetPos(x, y)}"), ui);
		assertTrue(run.warnings().stream().anyMatch(warning -> warning.matches(
				"overrides\\.txt:\\d+: note ui\\.Text\\.Missing matches nothing in these stubs\\.")),
				run.warnings()::toString);
	}

	@Test
	void membersCarryTheirLuaShape() throws IOException {
		String persistent = source("PersistentDB.java");
		assertTrue(persistent.contains("@LuaGlobal(\"PersistentDB\")\npublic final class PersistentDB {"));
		assertTrue(persistent.contains("\t@LuaColon\n\tpublic static native @Nullable Boolean GetBool(String key);"));
		String event = source("Event.java");
		assertTrue(event.contains("public static final EventSubscription<ServerLogicEventPayload> ServerLogic"));
		assertTrue(event.contains("public static final EventPredicateSubscription<EntityDrawEventPayload> EntityDraw"));
		assertTrue(source("ServerLogicEventPayload.java").contains("@LuaEvent(\"ServerLogic\")"));
		assertTrue(source("EntityDrawEventPayload.java")
				.contains("@LuaEvent(value = \"EntityDraw\", returns = boolean.class)"));
		String chat = source("ChatMessageEventPayload.java");
		assertTrue(chat.contains("@LuaPermission(\"chatAccess\")"));
		assertTrue(chat.contains("\tpublic String chatMessage;"), "the overrides make chatMessage writable");
		assertTrue(chat.contains("\tpublic final String senderNameSimple;"), "payload fields are read-only");
		assertTrue(source("SettingsLayerReadyEventPayload.java").contains("@LuaManifestFlag(\"hasSettingsIcon\")"));
		assertTrue(chat.contains("@LuaReview(\"chat\")"), "moderation reviews subscribing to ChatMessage");
		assertTrue(source("MiniMenu.java").contains("\t@LuaReview(\"mini-menu\")\n\tpublic native void Swap("));
		assertTrue(source("MiniMenuEntry.java").contains("\t@LuaReview(\"mini-menu\")\n\tpublic @Nullable String"
				+ " overrideText;"));
		assertTrue(source("Globals.java")
				.contains("\t@LuaDevOnly\n\tpublic static native void ExecuteConsoleCommand("));
		assertTrue(!source("Globals.java").contains("require"), "Oneiro writes require itself");
		assertTrue(source("CurrentPlugin.java").contains("@LuaGlobal(\"Plugin\")\npublic final class CurrentPlugin {"));
		assertTrue(source("ModifierKey.java").contains("public static final int SHIFT = 1;"));
		assertTrue(source("GameState.java").contains("public enum GameState {"));
		assertTrue(source("NPCEntity.java").contains("@LuaType(\"NPCEntity\")"));
		assertTrue(source("World.java")
				.contains("@LuaReturnValues\n\tpublic record GroundHeight(int height, boolean available)"));
		assertTrue(source("vars.java").contains("@LuaGlobal(\"var\")\npublic final class vars {"));
	}

	private static String source(String file) throws IOException {
		return source(result, file);
	}

	private static String source(BindingsGenerator.Result run, String file) throws IOException {
		try (JarFile jar = new JarFile(run.sourcesJar().toFile());
				InputStream in = jar.getInputStream(jar.getEntry("org/adelheid/oneiro/rs3/" + file))) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
