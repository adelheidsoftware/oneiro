package org.adelheid.oneiro.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
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
		BuildResult result = gradle("build", "assembleDevPlugin").build();
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
