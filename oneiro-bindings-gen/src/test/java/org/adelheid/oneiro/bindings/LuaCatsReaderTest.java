package org.adelheid.oneiro.bindings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The LuaCATS reader on Lua as people write it. The fixtures in {@code src/test/libraries} are a library's annotated
 * code ({@code widgets}) and definition files laid out as an LLS-Addons repository is ({@code addon}); short sources
 * pin single rules; and Zyklib's code and Blueprint-Helper's modules, when they are checked out next to Oneiro, read
 * without a warning. The stubs are the generator's tests' to read, and their bindings must not change.
 */
class LuaCatsReaderTest {

	private static final Path LIBRARIES = Path.of("src", "test", "libraries");

	@TempDir
	Path folder;

	@Test
	void readsAnnotatedLibraryCode() throws IOException {
		ApiModel model = read(LIBRARIES.resolve("widgets"), LIBRARIES.resolve("widgets"));
		assertEquals(List.of(), model.unparsed);
		assertEquals("widgets", export(model, "widgets"));
		assertEquals(Map.of("Counter", "widgets.Counter", "LoudCounter", "widgets.LoudCounter", "text", "widgets.text"),
				classNamed(model, "widgets").tables);

		ApiModel.ClassDef counter = classNamed(model, "widgets.Counter");
		assertEquals("widgets.Counter", export(model, "widgets/Counter"));
		// The private step is no part of the API, nor is the @package reset
		assertEquals(List.of("value"), counter.fields.stream().map(ApiModel.FieldDef::name).toList());
		assertEquals(List.of("new", "increment", "advance", "bump"), functionNames(counter));
		assertEquals(List.of("start"), function(counter, "new").luaParams());
		ApiModel.FunctionDef increment = function(counter, "increment");
		assertEquals(ApiModel.CallStyle.COLON, increment.style());
		// A colon function's self is implicit, so the tag that types it goes
		assertEquals(List.of("by"), increment.params().stream().map(ApiModel.ParamDef::name).toList());
		assertEquals("count", increment.returns().getFirst().name());
		assertEquals("the count after it", increment.returns().getFirst().description());
		assertTrue(function(counter, "bump").deprecated());
	}

	@Test
	void readsTheOtherFormsOfMembers() throws IOException {
		ApiModel model = read(LIBRARIES.resolve("widgets"), LIBRARIES.resolve("widgets"));
		ApiModel.ClassDef text = classNamed(model, "widgets.text");
		assertEquals(List.of("MAX_WIDTH: integer", "ELLIPSIS: string", "align: widgets.Align"),
				text.fields.stream().map(field -> field.name() + ": " + field.type().show()).toList());
		assertEquals("The widest a line may be, in characters.", text.fields.getFirst().description().strip());
		assertEquals(List.of("trim", "find", "describe", "paint", "measure", "join"), functionNames(text));
		assertEquals(List.of("line"), function(text, "trim").luaParams());
		assertEquals(List.of("T"), function(text, "find").typeParams());
		// A function whose first parameter is self is called with a colon
		ApiModel.FunctionDef describe = function(text, "describe");
		assertEquals(ApiModel.CallStyle.COLON, describe.style());
		assertEquals(List.of("prefix"), describe.luaParams());

		assertEquals(Map.of("COLOURS", "widgets.Colour"), text.tables);
		ApiModel.EnumDef colour = enumNamed(model, "widgets.Colour");
		assertTrue(colour.keys());
		assertEquals(List.of("WHITE=0xFFFFFF", "RED=0xFF0000"),
				colour.values().stream().map(value -> value.name() + "=" + value.literal()).toList());
		assertEquals("the default", colour.values().getFirst().comment());
		assertEquals("\"left\"|\"right\"", alias(model, "widgets.Align").show());
		assertEquals("1|2|3", alias(model, "widgets.Size").show());
	}

	@Test
	void readsDefinitionFilesLaidOutForLlsAddons() throws IOException {
		Path library = LIBRARIES.resolve("addon").resolve("library");
		ApiModel model = read(library, library);
		assertEquals(List.of(), model.unparsed);
		ApiModel.ClassDef gauges = classNamed(model, "gauges");
		assertEquals(ApiModel.Binding.GLOBAL, gauges.binding);
		assertEquals(List.of("version"), gauges.fields.stream().map(ApiModel.FieldDef::name).toList());
		ApiModel.ClassDef gauge = classNamed(model, "gauges.Gauge");
		assertEquals(ApiModel.Binding.GLOBAL, gauge.binding);
		assertEquals(List.of("new", "SetValue", "Destroy", "Follow"), functionNames(gauge));
		assertEquals(ApiModel.CallStyle.COLON, function(gauge, "SetValue").style());
		assertEquals(ApiModel.Binding.NONE, classNamed(model, "gauges.GaugeOptions").binding);
		assertTrue(model.modules.isEmpty());
	}

	@Test
	void aModuleWithoutAClassIsAClassNamedByItsPath() throws IOException {
		ApiModel model = sources(Map.of("src/core/levels.lua", """
				-- Levels, annotated in part, as a hand-written module is
				local Levels = {}

				Levels.PERFECT = 0
				Levels.ALL = { [0] = "Perfect", "Excellent" }
				local NAMES = {}

				---@param level integer
				---@return string
				function Levels.name(level)
				    return NAMES[level]
				end

				local function fromPenalty(penalty)
				    return penalty // 2
				end
				Levels.fromPenalty = fromPenalty

				return Levels
				"""));
		assertEquals(List.of(), model.unparsed);
		assertEquals("src/core/levels", export(model, "src/core/levels"));
		ApiModel.ClassDef levels = classNamed(model, "src/core/levels");
		assertEquals(List.of("PERFECT: integer", "ALL: table"),
				levels.fields.stream().map(field -> field.name() + ": " + field.type().show()).toList());
		assertEquals(List.of("name", "fromPenalty"), functionNames(levels));
		assertEquals(List.of("penalty"), function(levels, "fromPenalty").luaParams());
		// The tables the file keeps to itself are none of the model's
		assertEquals(List.of("src/core/levels"), List.copyOf(model.classes.keySet()));
	}

	@Test
	void aModuleCanReturnATableLiteralOrAnotherModule() throws IOException {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("lib/parts.lua", """
				return {
				    count = 3,
				    Part = require "lib/Part",
				}
				""");
		files.put("lib/Part.lua", """
				---@class lib.Part
				local Part = {}
				return Part
				""");
		files.put("lib/alias.lua", """
				return require("lib/parts")
				""");
		ApiModel model = sources(files);
		assertEquals(List.of(), model.unparsed);
		assertEquals(Map.of("Part", "lib.Part"), classNamed(model, "lib/parts").tables);
		assertEquals("lib/parts", export(model, "lib/alias"));
	}

	@Test
	void aConstructorsFieldsTakeTheirOwnAnnotations() throws IOException {
		ApiModel model = sources(Map.of("settings.lua", """
				---@class settings
				local settings = {
				    --- How often to refresh, in ticks.
				    ---@type integer
				    interval = compute(),

				    ---@param x number
				    ---@return number
				    double = function(x) return x * 2 end,
				}
				return settings
				"""));
		assertEquals(List.of(), model.unparsed);
		ApiModel.ClassDef settings = classNamed(model, "settings");
		assertEquals("interval", settings.fields.getFirst().name());
		assertEquals("integer", settings.fields.getFirst().type().show());
		assertEquals("How often to refresh, in ticks.", settings.fields.getFirst().description().strip());
		assertEquals(List.of("x"),
				function(settings, "double").params().stream().map(ApiModel.ParamDef::name).toList());
	}

	@Test
	void aDefinitionFileReportsWhatItCannotDeclareAndCodeDoesNot() throws IOException {
		String body = """
				print("hello")
				local helpers = {}
				function unknown.f() end
				""";
		ApiModel definitions = sources(Map.of("defs.lua", "---@meta\n\n" + body + "---@field x integer\n"));
		assertEquals(List.of("code", "@field outside a class block", "local table without a class",
				"function on unknown table unknown"),
				definitions.unparsed.stream().map(ApiModel.Unparsed::pattern).toList());
		ApiModel code = sources(Map.of("code.lua", body));
		assertEquals(List.of(), code.unparsed);
	}

	@Test
	void reportsLuaThatIsNotValidAndTagsItCannotRead() throws IOException {
		ApiModel invalid = sources(Map.of("broken.lua", "local x = = 1\n"));
		assertEquals(1, invalid.unparsed.size());
		assertTrue(invalid.unparsed.getFirst().pattern().startsWith("not valid Lua: "), invalid.unparsed::toString);
		assertEquals(1, invalid.unparsed.getFirst().line());

		ApiModel tags = sources(Map.of("tags.lua", """
				---@class Vector
				---@operator add(Vector): Vector
				---@frobnicate
				---@async
				---@nodiscard
				local Vector = {}
				return Vector
				"""));
		assertEquals(List.of("@operator is not supported yet", "unknown tag @frobnicate"),
				tags.unparsed.stream().map(ApiModel.Unparsed::pattern).toList());
	}

	@Test
	void readsZyklibWithoutAWarning() throws IOException {
		String location = System.getProperty("oneiro.zyklib", "");
		Path source = Path.of(location).resolve("src");
		assumeTrue(!location.isEmpty() && Files.isDirectory(source.resolve("zyklib")),
				"Zyklib is not checked out next to Oneiro.");
		ApiModel model = read(source, source);
		assertEquals(List.of(), model.unparsed);
		assertEquals("zyklib", export(model, "zyklib"));
		assertEquals("zyklib.reactive.Event", classNamed(model, "zyklib.reactive").tables.get("Event"));
		assertEquals("zyklib.disposable.DisposableGroup",
				classNamed(model, "zyklib.disposable").tables.get("DisposableGroup"));
		assertTrue(functionNames(classNamed(model, "zyklib.disposable.DisposableGroup")).contains("new"));
		assertFalse(functionNames(classNamed(model, "zyklib.reactive.Event")).contains("addHandler"));
	}

	@Test
	void readsBlueprintHelpersModulesWithoutAWarning() throws IOException {
		String location = System.getProperty("oneiro.blueprintHelper", "");
		Path plugin = Path.of(location);
		assumeTrue(!location.isEmpty() && Files.isDirectory(plugin.resolve("src")),
				"Blueprint-Helper is not checked out next to Oneiro.");
		ApiModel model = read(plugin.resolve("src"), plugin);
		assertEquals(List.of(), model.unparsed);
		ApiModel.ClassDef seeds = classNamed(model, export(model, "src/core/seeds"));
		assertTrue(functionNames(seeds).containsAll(List.of("all", "grade", "filter")), seeds.functions::toString);
	}

	/** Reads every Lua file under {@code root}, each loaded by its path from {@code base}. */
	private static ApiModel read(Path root, Path base) throws IOException {
		ApiModel model = new ApiModel();
		LuaCatsReader reader = new LuaCatsReader(model);
		List<Path> files;
		try (Stream<Path> walk = Files.walk(root)) {
			files = walk.filter(path -> path.toString().endsWith(".lua")).sorted().toList();
		}
		for (Path file : files) {
			String path = base.relativize(file).toString().replace('\\', '/');
			reader.read(file, path, path.substring(0, path.length() - ".lua".length()));
		}
		reader.finish();
		return model;
	}

	/** Writes the sources, by their paths, into a fresh folder and reads them. */
	private ApiModel sources(Map<String, String> files) throws IOException {
		Path root = Files.createTempDirectory(folder, "lua");
		for (Map.Entry<String, String> file : files.entrySet()) {
			Path path = root.resolve(file.getKey());
			Files.createDirectories(path.getParent());
			Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
		}
		return read(root, root);
	}

	private static ApiModel.ClassDef classNamed(ApiModel model, String name) {
		ApiModel.ClassDef found = model.classes.get(name);
		return Objects.requireNonNull(found, () -> "no class " + name + ": " + model.classes.keySet());
	}

	private static String export(ApiModel model, String module) {
		ApiModel.ModuleDef found = model.modules.get(module);
		return Objects.requireNonNull(found, () -> "no module " + module + ": " + model.modules.keySet()).export();
	}

	private static ApiModel.EnumDef enumNamed(ApiModel model, String name) {
		return Objects.requireNonNull(model.enums.get(name), () -> "no enum " + name);
	}

	private static TypeRef alias(ApiModel model, String name) {
		return Objects.requireNonNull(model.aliases.get(name), () -> "no alias " + name).target();
	}

	private static List<String> functionNames(ApiModel.ClassDef classDef) {
		return classDef.functions.stream().map(ApiModel.FunctionDef::name).toList();
	}

	private static ApiModel.FunctionDef function(ApiModel.ClassDef classDef, String name) {
		return classDef.functions.stream().filter(function -> function.name().equals(name)).findFirst().orElseThrow();
	}
}
