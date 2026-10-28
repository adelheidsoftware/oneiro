package org.adelheid.oneiro.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Builds a small plugin with the Gradle plugin, as its author would: Oneiro resolved from a Maven repository, the
 * bindings generated from the bindings generator's stubs fixture, and every task from the bindings to the zip, the
 * dev folder and the Lua tests. It runs with the configuration cache, as Oneiro's own build does.
 */
class OneiroPluginFunctionalTest {

	@TempDir
	Path project;

	private void write(String path, String text) throws IOException {
		Path file = project.resolve(path);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text, StandardCharsets.UTF_8);
	}

	private void plugin() throws IOException {
		String repository = Path.of(System.getProperty("oneiro.repository")).toUri().toString();
		String stubs = System.getProperty("oneiro.stubsFixture").replace('\\', '/');
		write("settings.gradle.kts", """
				pluginManagement {
				    repositories {
				        maven { url = uri("%s") }
				        gradlePluginPortal()
				    }
				}

				rootProject.name = "herb-tracker"
				""".formatted(repository));
		write("build.gradle.kts", """
				plugins {
				    id("org.adelheid.oneiro") version "%s"
				}

				version = "1.2.0"

				repositories {
				    maven { url = uri("%s") }
				    mavenCentral()
				}

				oneiro {
				    stubs {
				        local(file("%s"))
				    }
				    plugin {
				        name = "Herb Tracker"
				        contactEmail = "plugins@example.org"
				        settingsIcon = true
				        permissions {
				            optional(chatAccess)
				        }
				    }
				}
				""".formatted(System.getProperty("oneiro.version"), repository, stubs));
		write("src/main/java/com/example/herbs/HerbPlugin.java", """
				package com.example.herbs;

				import static org.adelheid.oneiro.rs3.Globals.log;

				import org.adelheid.oneiro.api.Plugin;
				import org.adelheid.oneiro.api.Subscribe;
				import org.adelheid.oneiro.rs3.ChatMessageEventPayload;
				import org.adelheid.oneiro.rs3.ServerLogicEventPayload;
				import org.adelheid.oneiro.rs3.SettingsLayerReadyEventPayload;

				/** Counts game ticks while the plugin runs. */
				public final class HerbPlugin extends Plugin {

					private HerbConfig settings;
					private final Tally tally = new Tally();

					@Override
					protected void startUp() {
						Resources.icons.herb.Load();
						log("Herb tracker started");
					}

					@Subscribe
					public void onServerLogic(ServerLogicEventPayload tick) {
						tally.add(1);
					}

					@Subscribe
					public void onChatMessage(ChatMessageEventPayload message) {
						if (settings.echo()) {
							log(message.chatMessage);
						}
					}

					@Subscribe
					public void onSettingsLayerReady(SettingsLayerReadyEventPayload layer) {
						log("Settings opened after", tally.sum(), "ticks");
					}
				}
				""");
		write("src/main/java/com/example/herbs/HerbConfig.java", """
				package com.example.herbs;

				import org.adelheid.oneiro.api.ConfigGroup;
				import org.adelheid.oneiro.api.ConfigItem;

				/** The herb tracker's settings. */
				@ConfigGroup("herbs")
				public interface HerbConfig {

					@ConfigItem(keyName = "echo", name = "Echo chat")
					default boolean echo() {
						return false;
					}
				}
				""");
		write("src/main/java/com/example/herbs/Tally.java", """
				package com.example.herbs;

				/** A running total. */
				public class Tally {

					private int total;

					public void add(int amount) {
						total += amount;
					}

					public int sum() {
						return total;
					}
				}
				""");
		write("src/dev/java/com/example/herbs/Cheats.java", """
				package com.example.herbs;

				import static org.adelheid.oneiro.rs3.Globals.ExecuteConsoleCommand;

				import org.adelheid.oneiro.api.DevPlugin;

				/** Development helpers: teleports to the herb patch when the plugin starts. */
				public final class Cheats extends DevPlugin {

					@Override
					protected void startUp() {
						ExecuteConsoleCommand("!tele 3058 3311 0");
					}
				}
				""");
		write("src/test/lua/tally_test.lua", """
				local Tally = require("src/tally")

				local tally = Tally.new()
				tally:add(2)
				tally:add(3)
				assert(tally:sum() == 5, "the total of what was added")
				""");
		Files.createDirectories(project.resolve("src/main/resources/icons"));
		Files.write(project.resolve("src/main/resources/icons/herb.png"), png());
	}

	/** The start of a 16 by 16 PNG: what checkPlugin reads of an image. */
	private static byte[] png() {
		ByteBuffer png = ByteBuffer.allocate(24);
		png.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'});
		png.putInt(16).putInt(16);
		return png.array();
	}

	private GradleRunner gradle(String... arguments) {
		List<String> all = new ArrayList<>(List.of(arguments));
		all.add("--configuration-cache");
		all.add("--stacktrace");
		return GradleRunner.create().withProjectDir(project.toFile()).withArguments(all).forwardOutput();
	}

	@Test
	void buildsThePluginAsItShips() throws IOException {
		plugin();
		BuildResult result = gradle("build").build();
		assertEquals(TaskOutcome.SUCCESS, outcome(result, ":checkPlugin"));
		// Moderation reviews the chat, and the compiler says so where the plugin subscribes to it
		String warning = "HerbPlugin.java:28: warning: Subscribing to ChatMessage uses the chat,";
		assertTrue(result.getOutput().contains(warning), result.getOutput());
		assertEquals(TaskOutcome.SUCCESS, outcome(result, ":testLua"));

		Path shipped = project.resolve("build/oneiro/plugin");
		String main = Files.readString(shipped.resolve("main.lua"));
		assertTrue(main.contains("local HerbConfig = require(\"src/herb_config\")"), main);
		assertTrue(main.contains("Plugin.resources.icons.herb:Load()"), main);
		assertTrue(main.contains("Event.ServerLogic.Subscribe(\"herb-tracker.onServerLogic\", onServerLogic)"), main);
		assertEquals("""
				{
				    "name": "Herb Tracker",
				    "version": "1.2.0",
				    "contactEmailAddress": "plugins@example.org",
				    "requiredDependencies": {},
				    "optionalDependencies": {},
				    "requiredPermissions": [],
				    "optionalPermissions": [ "chatAccess" ],
				    "urls": {},
				    "hasSettingsIcon": true,
				    "hasRibbonIcon": false
				}
				""", Files.readString(shipped.resolve("plugin.json")));

		Path zip = project.resolve("build/distributions/herb-tracker-1.2.0.zip");
		assertEquals(List.of("main.lua", "plugin.json", "resources/", "resources/icons/", "resources/icons/herb.png",
				"src/", "src/herb_config.lua", "src/tally.lua"), entries(zip));

		Path dev = project.resolve("build/oneiro/dev-plugin");
		assertTrue(Files.readString(dev.resolve("main.lua")).endsWith("require(\"dev/main\")\n"));
		String cheats = Files.readString(dev.resolve("dev/main.lua"));
		assertTrue(cheats.contains("ExecuteConsoleCommand(\"!tele 3058 3311 0\")"), cheats);

		// The same sources give the same bytes
		byte[] first = Files.readAllBytes(zip);
		gradle("clean", "packagePlugin").build();
		assertEquals(-1, Files.mismatch(zip, writeTemporary(first)));
	}

	@Test
	void buildMakesNoDevelopmentFolderWithoutADevSourceSet() throws IOException {
		plugin();
		try (Stream<Path> files = Files.walk(project.resolve("src/dev"))) {
			for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(file);
			}
		}
		gradle("build").build();
		assertTrue(Files.isDirectory(project.resolve("build/oneiro/plugin")));
		assertFalse(Files.exists(project.resolve("build/oneiro/dev-plugin")));
	}

	@Test
	void helpersShipInFilesOfTheirOwn() throws IOException {
		plugin();
		write("src/main/java/com/example/herbs/Names.java", """
				package com.example.herbs;

				/** Names as the player types them. */
				public final class Names {

					private Names() {
					}

					/** The name without the spaces around it. */
					public static String tidy(String name) {
						return name.trim();
					}
				}
				""");
		write("src/dev/java/com/example/herbs/Cheats.java", """
				package com.example.herbs;

				import static org.adelheid.oneiro.rs3.Globals.log;

				import org.adelheid.oneiro.api.DevPlugin;

				/** Development helpers: logs a tidy name, and whether it is a leaf's. */
				public final class Cheats extends DevPlugin {

					@Override
					protected void startUp() {
						String name = " guam leaf ".trim();
						String kind = "leaf";
						log(Names.tidy(name), name.endsWith(kind));
					}
				}
				""");
		BuildResult result = gradle("build").build();
		assertEquals(TaskOutcome.SUCCESS, outcome(result, ":checkPlugin"));
		// The helper the main set uses ships in a file of its own, which the module that uses it requires
		assertEquals(List.of("main.lua", "plugin.json", "resources/", "resources/icons/", "resources/icons/herb.png",
				"src/", "src/herb_config.lua", "src/names.lua", "src/tally.lua", "utils/", "utils/trim.lua"),
				entries(project.resolve("build/distributions/herb-tracker-1.2.0.zip")));
		String names = Files.readString(project.resolve("build/oneiro/plugin/src/names.lua"));
		assertTrue(names.contains("local trim = require(\"utils/trim\")"), names);
		// The dev set's helpers join the main set's in the development folder, the one only it uses among them
		Path utils = project.resolve("build/oneiro/dev-plugin/utils");
		assertTrue(Files.isRegularFile(utils.resolve("trim.lua")));
		assertTrue(Files.isRegularFile(utils.resolve("ends_with.lua")));
	}

	@Test
	void theDevSetReadsTheMainSetsSources() throws IOException {
		plugin();
		write("src/main/java/com/example/herbs/Mode.java", """
				package com.example.herbs;

				/** How the tracker counts, each mode with its label. */
				public enum Mode {
					FAST("fast"), SAFE("safe");

					public final String label;

					Mode(String label) {
						this.label = label;
					}
				}
				""");
		write("src/dev/java/com/example/herbs/Cheats.java", """
				package com.example.herbs;

				import static org.adelheid.oneiro.rs3.Globals.log;

				import org.adelheid.oneiro.api.DevPlugin;

				/** Development helpers: logs the fast mode's name. */
				public final class Cheats extends DevPlugin {

					@Override
					protected void startUp() {
						log("mode", Mode.FAST.name());
					}
				}
				""");
		gradle("build").build();
		Path cheats = project.resolve("build/oneiro/dev-plugin/dev/main.lua");
		// An object enum of the main set's, whose constants keep their names, as the main set's Lua makes them
		String lua = Files.readString(cheats);
		assertTrue(lua.contains("local Mode = require(\"src/mode\")"), lua);
		assertTrue(lua.contains("log(\"mode\", Mode.FAST.name)"), lua);

		// The main set's sources change what the dev set's Lua is: a simple enum's constants are their names
		write("src/main/java/com/example/herbs/Mode.java", """
				package com.example.herbs;

				/** How the tracker counts. */
				public enum Mode {
					FAST, SAFE
				}
				""");
		gradle("build").build();
		lua = Files.readString(cheats);
		assertTrue(lua.contains("log(\"mode\", Mode.FAST)"), lua);
	}

	@Test
	void explainErrorShowsTheJavaLinesOfAClientError() throws IOException {
		plugin();
		gradle("compileJava").build();
		// An error as the client prints it, at the line of main.lua that HerbPlugin.java's tally.add(1) became
		List<String> main = Files.readAllLines(project.resolve("build/oneiro/lua/main/main.lua"));
		int call = main.indexOf("    tally:add(1)") + 1;
		write("console.txt", """
				[ERROR] in Herb Tracker(-1): [string "-- main.lua..."]:%d: attempt to index a nil value
				stack traceback:
				\t[string "-- main.lua..."]:%d: in function
				[ERROR] in Coin Watch(-1): [string "-- main.lua..."]:3: attempt to call a nil value
				""".formatted(call, call));
		String output = gradle("explainError", "--input=console.txt").build().getOutput();
		String java = "src/main/java/com/example/herbs/HerbPlugin.java:25: ";
		assertTrue(output.contains(java + "attempt to index a nil value"), output);
		assertTrue(output.contains("\tat " + java + "tally.add(1);"), output);
		assertTrue(output.contains("1 error of Herb Tracker, newest first, as the console lists them."), output);
		assertTrue(output.contains("Left out 1 error of other plugins."), output);
	}

	@Test
	void aRejectionFailsCompileJava() throws IOException {
		plugin();
		write("src/main/java/com/example/herbs/Tracking.java", """
				package com.example.herbs;

				import org.adelheid.oneiro.rs3.Player;

				/** Reads the character's tracking ID, which needs a permission the manifest does not declare. */
				public final class Tracking {

					private Tracking() {
					}

					public static Long id() {
						return Player.GetTrackingID();
					}
				}
				""");
		BuildResult result = gradle("compileJava").buildAndFail();
		assertTrue(result.getOutput().contains("Player.GetTrackingID needs the trackingAccess permission"),
				result.getOutput());
	}

	/**
	 * Lua of the plugin's own: {@code levels}, which a class of the plugin's calls through the bindings of its
	 * annotations, and for the dev source set, {@code teleports}, which the dev set's class calls.
	 */
	private void ownLua() throws IOException {
		write("src/main/lua/core/levels.lua", """
				-- src/core/levels.lua
				-- The levels an arrangement scores, kept in Lua.

				local Levels = {}

				Levels.PERFECT = 0

				---@param penalty integer
				---@return integer level
				function Levels.fromPenalty(penalty)
				    return penalty // 2
				end

				return Levels
				""");
		write("src/dev/lua/teleports.lua", """
				-- dev/teleports.lua
				-- Where the development helpers teleport to.

				local Teleports = {}

				---@return string command
				function Teleports.herbPatch()
				    return "!tele 3058 3311 0"
				end

				return Teleports
				""");
		write("src/test/lua/grades_test.lua", """
				local Grades = require("src/core/grades")

				assert(Grades.best(4) == 2, "the plugin's own Lua, called from the plugin's class")
				""");
	}

	@Test
	void usesThePluginsOwnLua() throws IOException {
		plugin();
		ownLua();
		write("src/main/java/com/example/herbs/core/Grades.java", """
				package com.example.herbs.core;

				/** The best level for a penalty, from the plugin's own levels module. */
				public final class Grades {

					private Grades() {
					}

					public static int best(int penalty) {
						return Levels.fromPenalty(penalty) + Levels.PERFECT;
					}
				}
				""");
		write("src/dev/java/com/example/herbs/Cheats.java", """
				package com.example.herbs;

				import static org.adelheid.oneiro.rs3.Globals.ExecuteConsoleCommand;

				import org.adelheid.oneiro.api.DevPlugin;

				/** Development helpers: teleports to the herb patch when the plugin starts. */
				public final class Cheats extends DevPlugin {

					@Override
					protected void startUp() {
						ExecuteConsoleCommand(Teleports.herbPatch());
					}
				}
				""");
		BuildResult result = gradle("build").build();
		assertEquals(TaskOutcome.SUCCESS, outcome(result, ":checkPlugin"));
		assertEquals(TaskOutcome.SUCCESS, outcome(result, ":testLua"));

		String grades = Files.readString(project.resolve("build/oneiro/plugin/src/core/grades.lua"));
		assertTrue(grades.contains("local Levels = require(\"src/core/levels\")"), grades);
		assertTrue(grades.contains("return Levels.fromPenalty(penalty) + Levels.PERFECT"), grades);
		assertEquals(List.of("main.lua", "plugin.json", "resources/", "resources/icons/", "resources/icons/herb.png",
				"src/", "src/herb_config.lua", "src/tally.lua", "src/core/", "src/core/grades.lua",
				"src/core/levels.lua"), entries(project.resolve("build/distributions/herb-tracker-1.2.0.zip")));
		Path dev = project.resolve("build/oneiro/dev-plugin");
		assertTrue(Files.isRegularFile(dev.resolve("dev/teleports.lua")));
		String cheats = Files.readString(dev.resolve("dev/main.lua"));
		assertTrue(cheats.contains("local Teleports = require(\"dev/teleports\")"), cheats);
		assertTrue(cheats.contains("ExecuteConsoleCommand(Teleports.herbPatch())"), cheats);

		// An error in the plugin's own Lua is shown as the line of its file
		write("console.txt", """
				[ERROR] in Herb Tracker(-1): [string "-- src/core/levels.lua..."]:11: attempt to perform arithmetic
				stack traceback:
				\t[string "-- src/core/levels.lua..."]:11: in function 'fromPenalty'
				""");
		String output = gradle("explainError", "--input=console.txt").build().getOutput();
		assertTrue(output.contains("\tat src/main/lua/core/levels.lua:11: return penalty // 2"), output);
	}

	@Test
	void thePluginsOwnLuaIsChecked() throws IOException {
		plugin();
		write("src/main/lua/fetch.lua", """
				local Fetch = {}

				function Fetch.prices()
				    return HTTPRequest.new(Plugin.urls.prices, "/")
				end

				return Fetch
				""");
		String output = gradle("checkPlugin").buildAndFail().getOutput();
		assertTrue(output.contains("src/fetch.lua does not start with its path, -- src/fetch.lua,"), output);
		assertTrue(output.contains("src/fetch.lua uses HTTPRequest, which needs the httpAccess permission"), output);
	}

	@Test
	void aClassWithThePathOfTheOwnLuaFailsCompileJava() throws IOException {
		plugin();
		write("src/main/lua/tally.lua", """
				-- src/tally.lua
				-- A running total, kept in Lua as well.

				return {}
				""");
		String output = gradle("compileJava").buildAndFail().getOutput();
		assertTrue(output.contains("The Lua of Tally would be src/tally.lua, a file the plugin's own Lua has already;"
				+ " rename the class or that file."), output);
	}

	@Test
	void aClassOfTheOwnLuaWithTheNameOfAPackageOfTheCodeFailsItsBindings() throws IOException {
		plugin();
		write("src/main/lua/helpers.lua", """
				-- src/helpers.lua
				-- Helpers kept in Lua, whose class takes the name of a package of the plugin's code.

				---@class core
				local core = {}

				return core
				""");
		write("src/dev/java/com/example/herbs/core/timers/Cheats.java", """
				package com.example.herbs.core.timers;

				/** A class of the dev source set's, whose package makes com.example.herbs.core one too. */
				public final class Cheats {

					private Cheats() {
					}
				}
				""");
		String output = gradle("compileJava").buildAndFail().getOutput();
		assertTrue(output.contains("helpers.lua: the class core would be com.example.herbs.core, but the plugin's code"
				+ " makes that a package"), output);
	}

	/**
	 * A library the plugin vendors from a checkout beside it, {@code tallies}, whose Lua is in its {@code src/} folder,
	 * and one for the dev source set alone, {@code inspect}, whose Lua is at its root and declares no class, so that
	 * its class is named after its file, {@code Inspect}.
	 */
	private void libraries() throws IOException {
		write("libs/tallies/LICENSE", "MIT License\n\nCopyright (c) 2026 The tallies authors\n");
		write("libs/tallies/README.md", "A library for the functional test.\n");
		write("libs/tallies/src/tallies.lua", """
				--- Tallies of things.
				---@class tallies
				local tallies = {}

				local sum = require("tallies/sum")

				--- The total of some numbers.
				---@param values integer[]
				---@return integer total
				function tallies.total(values)
				    return sum.of(values)
				end

				return tallies
				""");
		write("libs/tallies/src/tallies/sum.lua", """
				local sum = {}

				---@param values integer[]
				---@return integer
				function sum.of(values)
				    local total = 0
				    for _, value in ipairs(values) do
				        total = total + value
				    end
				    return total
				end

				return sum
				""");
		write("libs/inspect/LICENCE.md", "MIT License\n");
		write("libs/inspect/inspect.lua", """
				local inspect = {}

				---@param value any
				---@return string
				function inspect.describe(value)
				    return "<" .. tostring(value) .. ">"
				end

				return inspect
				""");
		String build = Files.readString(project.resolve("build.gradle.kts"));
		write("build.gradle.kts", build + """

				oneiro {
				    libraries {
				        vendored("tallies") {
				            local(file("libs/tallies"))
				            from("src")
				        }
				        development("inspect") {
				            local(file("libs/inspect"))
				        }
				    }
				}
				""");
		write("src/test/lua/counting_test.lua", """
				local Counting = require("src/counting")

				assert(Counting.total({ 1, 2, 3 }) == 6, "the vendored library's total, called from the plugin's class")
				""");
	}

	@Test
	void vendorsALibrary() throws IOException {
		plugin();
		libraries();
		write("src/main/java/com/example/herbs/Counting.java", """
				package com.example.herbs;

				import java.util.List;
				import org.adelheid.oneiro.lib.tallies.tallies;

				/** Totals, with the vendored library. */
				public final class Counting {

					private Counting() {
					}

					public static int total(List<Integer> values) {
						return tallies.total(values);
					}
				}
				""");
		write("src/dev/java/com/example/herbs/Cheats.java", """
				package com.example.herbs;

				import static org.adelheid.oneiro.rs3.Globals.log;

				import org.adelheid.oneiro.api.DevPlugin;
				import org.adelheid.oneiro.lib.inspect.Inspect;

				/** Development helpers: describes a value with the library of the dev source set alone. */
				public final class Cheats extends DevPlugin {

					@Override
					protected void startUp() {
						log(Inspect.describe(Counting.total(java.util.List.of(1, 2))));
					}
				}
				""");
		BuildResult result = gradle("build").build();
		assertEquals(TaskOutcome.SUCCESS, outcome(result, ":checkPlugin"));
		assertEquals(TaskOutcome.SUCCESS, outcome(result, ":testLua"));

		String counting = Files.readString(project.resolve("build/oneiro/plugin/src/counting.lua"));
		assertTrue(counting.contains("local tallies = require(\"tallies\")"), counting);
		assertTrue(counting.contains("return tallies.total(values)"), counting);
		assertEquals(List.of("main.lua", "plugin.json", "tallies.lua", "licences/", "licences/tallies/",
				"licences/tallies/LICENSE", "resources/", "resources/icons/", "resources/icons/herb.png", "src/",
				"src/counting.lua", "src/herb_config.lua", "src/tally.lua", "tallies/", "tallies/sum.lua"),
				entries(project.resolve("build/distributions/herb-tracker-1.2.0.zip")));
		Path dev = project.resolve("build/oneiro/dev-plugin");
		assertTrue(Files.isRegularFile(dev.resolve("inspect.lua")));
		assertTrue(Files.isRegularFile(dev.resolve("licences/inspect/LICENCE.md")));
		String cheats = Files.readString(dev.resolve("dev/main.lua"));
		assertTrue(cheats.contains("local Inspect = require(\"inspect\")"), cheats);
	}

	@Test
	void aLibraryWithLuaWhereOneirosHelpersGoFails() throws IOException {
		plugin();
		libraries();
		write("libs/tallies/src/utils/round.lua", "return math.floor\n");
		String output = gradle("oneiroLibraries").buildAndFail().getOutput();
		assertTrue(output.contains("tallies's utils/round.lua would ship in the plugin's utils/ folder, which holds the"
				+ " helpers Oneiro writes;"), output);
	}

	@Test
	void aLibraryWithoutALicenceFails() throws IOException {
		plugin();
		libraries();
		Files.delete(project.resolve("libs/tallies/LICENSE"));
		String output = gradle("oneiroLibraries").buildAndFail().getOutput();
		assertTrue(output.contains("tallies has no licence at its root; name its file with licence(\"...\"). A library"
				+ " without a licence cannot be vendored: nobody may pass on its code without one."), output);
	}

	/**
	 * Library plugins the plugin depends on: {@code gauges}, published and optional, whose definition file the build
	 * names, used where a check that it is loaded reaches; {@code beacons}, not published yet, named by its folder on
	 * this machine; and {@code probe}, which only the dev source set uses.
	 */
	@Test
	void dependsOnLibraryPlugins() throws IOException {
		plugin();
		write("lua-types/gauges/gauges.lua", """
				---@meta
				-- Gauges, a library plugin: bars that show a value.

				---@class gauges
				---@field version string
				gauges = {}

				---@class gauges.Gauge
				gauges.Gauge = {}

				---@param label string
				---@return gauges.Gauge
				function gauges.Gauge.new(label) end

				---@param value number
				function gauges.Gauge:SetValue(value) end
				""");
		String build = Files.readString(project.resolve("build.gradle.kts"));
		write("build.gradle.kts", build + """

				oneiro {
				    plugin {
				        dependencies {
				            optional("gauges", 4242) {
				                types { local(file("lua-types/gauges")) }
				            }
				            required("beacons") { devFolder("beacons") }
				            development("probe", 5151)
				        }
				    }
				}
				""");
		write("src/main/java/com/example/herbs/Meters.java", """
				package com.example.herbs;

				import org.adelheid.oneiro.lib.gauges.gauges;

				/** Shows a count on a gauge, where the optional library is loaded. */
				public final class Meters {

					private Meters() {
					}

					public static void show(int count) {
						if (!gauges.isLoaded()) {
							return;
						}
						new gauges.Gauge("ticks").SetValue(count);
					}
				}
				""");
		gradle("assembleDevPlugin").build();

		String meters = Files.readString(project.resolve("build/oneiro/plugin/src/meters.lua"));
		assertTrue(meters.contains("if not gauges then"), meters);
		assertTrue(meters.contains("gauges.Gauge.new(\"ticks\"):SetValue(count)"), meters);
		assertTrue(Files.notExists(project.resolve("build/oneiro/plugin/gauges.lua")), "the types never ship");
		String shipped = Files.readString(project.resolve("build/oneiro/plugin/plugin.json"));
		assertTrue(shipped.contains("\"requiredDependencies\": { \"beacons\": \"beacons\" },"), shipped);
		assertTrue(shipped.contains("\"optionalDependencies\": { \"gauges\": 4242 },"), shipped);
		String dev = Files.readString(project.resolve("build/oneiro/dev-plugin/plugin.json"));
		assertTrue(dev.contains("\"requiredDependencies\": { \"beacons\": \"beacons\", \"probe\": 5151 },"), dev);
		assertTrue(dev.contains("\"optionalDependencies\": { \"gauges\": 4242 },"), dev);

		String output = gradle("checkPlugin").buildAndFail().getOutput();
		assertTrue(output.contains("The required dependency beacons names only a folder on this machine, with"
				+ " devFolder, so the plugin cannot ship"), output);
	}

	/** The same plugin written in Kotlin, built with the Kotlin JVM plugin applied before Oneiro's. */
	private void kotlinPlugin() throws IOException {
		plugin();
		Files.delete(project.resolve("src/main/java/com/example/herbs/HerbPlugin.java"));
		Files.delete(project.resolve("src/main/java/com/example/herbs/HerbConfig.java"));
		Files.delete(project.resolve("src/main/java/com/example/herbs/Tally.java"));
		Files.delete(project.resolve("src/dev/java/com/example/herbs/Cheats.java"));
		String build = Files.readString(project.resolve("build.gradle.kts"));
		write("build.gradle.kts", build.replace("plugins {\n", "plugins {\n    kotlin(\"jvm\") version \"%s\"\n"
				.formatted(System.getProperty("oneiro.kotlinVersion"))));
		write("src/main/kotlin/com/example/herbs/HerbPlugin.kt", """
				package com.example.herbs

				import org.adelheid.oneiro.api.Plugin
				import org.adelheid.oneiro.api.Subscribe
				import org.adelheid.oneiro.rs3.ChatMessageEventPayload
				import org.adelheid.oneiro.rs3.Globals.log
				import org.adelheid.oneiro.rs3.ServerLogicEventPayload

				/** Counts game ticks while the plugin runs. */
				class HerbPlugin : Plugin() {

					private lateinit var settings: HerbConfig
					private val tally = Tally()

					override fun startUp() {
						Resources.icons.herb.Load()
						log("Herb tracker started")
					}

					@Subscribe
					fun onServerLogic(tick: ServerLogicEventPayload) {
						tally.add(1)
					}

					@Subscribe
					fun onChatMessage(message: ChatMessageEventPayload) {
						if (settings.echo()) {
							log("Chat: ${message.chatMessage}")
						}
					}
				}
				""");
		write("src/main/kotlin/com/example/herbs/HerbConfig.kt", """
				package com.example.herbs

				import org.adelheid.oneiro.api.ConfigGroup
				import org.adelheid.oneiro.api.ConfigItem

				/** The herb tracker's settings. */
				@ConfigGroup("herbs")
				interface HerbConfig {

					@ConfigItem(keyName = "echo", name = "Echo chat")
					fun echo(): Boolean = false
				}
				""");
		write("src/main/kotlin/com/example/herbs/Tally.kt", """
				package com.example.herbs

				/** A running total. */
				class Tally {

					private var total = 0

					fun add(amount: Int) {
						total += amount
					}

					fun sum(): Int = total
				}
				""");
	}

	@Test
	void buildsAKotlinPluginAsItShips() throws IOException {
		kotlinPlugin();
		BuildResult result = gradle("build").build();
		assertEquals(TaskOutcome.SUCCESS, outcome(result, ":oneiroKotlin"));
		// Moderation reviews the chat, and the warning says so where the plugin subscribes to it, as kotlinc warns
		String warning = "w: " + project.resolve("src/main/kotlin/com/example/herbs/HerbPlugin.kt").toUri()
				+ ":25:2 Subscribing to ChatMessage uses the chat,";
		assertTrue(result.getOutput().contains(warning), result.getOutput());
		assertEquals(TaskOutcome.SUCCESS, outcome(result, ":checkPlugin"));
		assertEquals(TaskOutcome.SUCCESS, outcome(result, ":testLua"));

		Path shipped = project.resolve("build/oneiro/plugin");
		String main = Files.readString(shipped.resolve("main.lua"));
		assertTrue(main.contains("local HerbConfig = require(\"src/herb_config\")"), main);
		assertTrue(main.contains("Plugin.resources.icons.herb:Load()"), main);
		assertTrue(main.contains("log(formatStr(\"Chat: {}\", message.chatMessage))"), main);
		assertTrue(main.contains("Event.ServerLogic.Subscribe(\"herb-tracker.onServerLogic\", onServerLogic)"), main);
		assertEquals(List.of("main.lua", "plugin.json", "resources/", "resources/icons/", "resources/icons/herb.png",
				"src/", "src/herb_config.lua", "src/tally.lua"),
				entries(project.resolve("build/distributions/herb-tracker-1.2.0.zip")));
		assertTrue(Files.readString(project.resolve("build/oneiro/line-maps/main.txt")).contains("HerbPlugin.kt"));
	}

	@Test
	void theDevSetReadsTheMainSetsKotlin() throws IOException {
		kotlinPlugin();
		write("src/main/kotlin/com/example/herbs/Mode.kt", """
				package com.example.herbs

				/** How the tracker counts. */
				enum class Mode { FAST, SAFE }
				""");
		write("src/dev/kotlin/com/example/herbs/Cheats.kt", """
				package com.example.herbs

				import org.adelheid.oneiro.api.DevPlugin
				import org.adelheid.oneiro.rs3.Globals.log

				/** Development helpers: logs how many modes there are. */
				class Cheats : DevPlugin() {

					override fun startUp() {
						log("modes", Mode.entries.size)
					}
				}
				""");
		gradle("build").build();
		Path cheats = project.resolve("build/oneiro/dev-plugin/dev/main.lua");
		// The main set's enum, read from its sources, which its compiled classes do not hold
		String lua = Files.readString(cheats);
		assertTrue(lua.contains("local Mode = require(\"src/mode\")"), lua);
		assertTrue(lua.contains("log(\"modes\", #{ Mode.FAST, Mode.SAFE })"), lua);

		// A change to the main set's sources compiles the dev set again
		write("src/main/kotlin/com/example/herbs/Mode.kt", """
				package com.example.herbs

				/** How the tracker counts. */
				enum class Mode { FAST, SAFE, IDLE }
				""");
		gradle("build").build();
		lua = Files.readString(cheats);
		assertTrue(lua.contains("log(\"modes\", #{ Mode.FAST, Mode.SAFE, Mode.IDLE })"), lua);
	}

	@Test
	void usesThePluginsOwnLuaFromKotlin() throws IOException {
		kotlinPlugin();
		ownLua();
		write("src/main/kotlin/com/example/herbs/core/Grades.kt", """
				package com.example.herbs.core

				/** The best level for a penalty, from the plugin's own levels module. */
				object Grades {

					fun best(penalty: Int): Int = Levels.fromPenalty(penalty) + Levels.PERFECT
				}
				""");
		BuildResult result = gradle("build").build();
		assertEquals(TaskOutcome.SUCCESS, outcome(result, ":testLua"));
		String grades = Files.readString(project.resolve("build/oneiro/plugin/src/core/grades.lua"));
		assertTrue(grades.contains("local Levels = require(\"src/core/levels\")"), grades);
		assertTrue(Files.isRegularFile(project.resolve("build/oneiro/plugin/src/core/levels.lua")));
	}

	@Test
	void vendorsALibraryFromKotlin() throws IOException {
		kotlinPlugin();
		libraries();
		write("src/main/kotlin/com/example/herbs/Counting.kt", """
				package com.example.herbs

				import org.adelheid.oneiro.lib.tallies.tallies

				/** Totals, with the vendored library. */
				object Counting {

					fun total(values: List<Int>): Int = tallies.total(values)
				}
				""");
		BuildResult result = gradle("build").build();
		assertEquals(TaskOutcome.SUCCESS, outcome(result, ":checkPlugin"));
		assertEquals(TaskOutcome.SUCCESS, outcome(result, ":testLua"));
		String counting = Files.readString(project.resolve("build/oneiro/plugin/src/counting.lua"));
		assertTrue(counting.contains("local tallies = require(\"tallies\")"), counting);
	}

	@Test
	void aKotlinRejectionFailsTheBuildAsKotlincReportsAnError() throws IOException {
		kotlinPlugin();
		write("src/main/kotlin/com/example/herbs/Tracking.kt", """
				package com.example.herbs

				import org.adelheid.oneiro.rs3.Player

				/** Reads the character's tracking ID, which needs a permission the manifest does not declare. */
				object Tracking {

					fun id(): Long? = Player.GetTrackingID()
				}
				""");
		BuildResult result = gradle("oneiroKotlin").buildAndFail();
		String output = result.getOutput();
		assertTrue(output.contains("e: " + project.resolve("src/main/kotlin/com/example/herbs/Tracking.kt").toUri()
				+ ":8:20 Player.GetTrackingID needs the trackingAccess permission"), output);
	}

	private static TaskOutcome outcome(BuildResult result, String task) {
		BuildTask ran = result.task(task);
		return ran == null ? TaskOutcome.SKIPPED : ran.getOutcome();
	}

	private Path writeTemporary(byte[] bytes) throws IOException {
		Path file = project.resolve("first.zip");
		Files.write(file, bytes);
		return file;
	}

	private static List<String> entries(Path zip) throws IOException {
		List<String> names = new ArrayList<>();
		try (ZipFile file = new ZipFile(zip.toFile())) {
			Enumeration<? extends ZipEntry> entries = file.entries();
			while (entries.hasMoreElements()) {
				names.add(entries.nextElement().getName());
			}
		}
		return names;
	}
}
