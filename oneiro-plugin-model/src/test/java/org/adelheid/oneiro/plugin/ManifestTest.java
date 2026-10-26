package org.adelheid.oneiro.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The manifest's plugin.json, and the problems that the client or Mod.io would refuse. */
class ManifestTest {

	private static Manifest manifest(String version, String email, List<String> optional, Map<String, String> urls) {
		return new Manifest("herb-tracker", "Herb Tracker", version, email,
				Map.of("prettyui", Manifest.Dependency.modIo(6398181)),
				Map.of("Markers", new Manifest.Dependency(4242, "TileMarkerLib")),
				Map.of("rsprite", new Manifest.Dependency(null, "rsprite")), List.of(), optional, urls, true, false);
	}

	@Test
	void writesPluginJsonInTheExamplesLayout() {
		String json = manifest("1.2.0", "plugins@example.org", List.of("httpAccess"),
				Map.of("prices", "https://prices.example.org")).toJson();
		assertEquals("""
				{
				    "name": "Herb Tracker",
				    "version": "1.2.0",
				    "contactEmailAddress": "plugins@example.org",
				    "requiredDependencies": { "prettyui": 6398181 },
				    "optionalDependencies": { "Markers": 4242 },
				    "requiredPermissions": [],
				    "optionalPermissions": [ "httpAccess" ],
				    "urls": { "prices": "https://prices.example.org" },
				    "hasSettingsIcon": true,
				    "hasRibbonIcon": false
				}
				""", json);
	}

	@Test
	void theDevelopmentFoldersManifestNamesFoldersAndTheDevelopmentDependencies() {
		String json = manifest("1.2.0", "plugins@example.org", List.of(), Map.of()).development().toJson();
		assertTrue(json.contains("\"requiredDependencies\": { \"prettyui\": 6398181, \"rsprite\": \"rsprite\" },"),
				json);
		assertTrue(json.contains("\"optionalDependencies\": { \"Markers\": \"TileMarkerLib\" },"), json);
	}

	@Test
	void anAliasIsALuaNameThatNoGlobalHasAndOfOneKind() {
		Manifest manifest = new Manifest("x", "X", "1", "a@b.org",
				Map.of("print", Manifest.Dependency.modIo(1), "shared", Manifest.Dependency.modIo(2)),
				Map.of("Player", Manifest.Dependency.modIo(3), "shared", Manifest.Dependency.modIo(2)),
				Map.of("pretty-ui", new Manifest.Dependency(null, " ")), List.of(), List.of(), Map.of(), false, false);
		assertEquals(List.of(
				"The required dependency's alias print is a global of Lua's already, and the client exposes the"
						+ " dependency as the global of its alias; choose another, such as the library's name.",
				"The optional dependency's alias Player is a global of the client's already, and the client exposes the"
						+ " dependency as the global of its alias; choose another, such as the library's name.",
				"The development dependency's alias pretty-ui must be a Lua name, because the client exposes the"
						+ " dependency as the global of that name.",
				"The development dependency pretty-ui needs its Mod.io ID, or, for a library not published yet,"
						+ " devFolder(...) with the folder of a plugin on this machine.",
				"The dependency shared is required and optional at once; keep one."),
				manifest.problems(Set.of("Player")));
	}

	@Test
	void emptyDependenciesAreObjects() {
		Manifest empty = new Manifest("x", "X", "1", "a@b.org", Map.of(), Map.of(), Map.of(), List.of(), List.of(),
				Map.of(), false, false);
		assertTrue(empty.toJson().contains("\"requiredDependencies\": {},"), empty.toJson());
		assertTrue(empty.toJson().contains("\"optionalDependencies\": {},"), empty.toJson());
		assertEquals(List.of(), empty.problems(Set.of()));
	}

	@Test
	void reportsWhatWouldBeRefused() {
		Manifest refused = manifest("1.2-beta", "nobody", List.of("httpAccess", "fileAccess"), Map.of());
		List<String> problems = refused.problems(Set.of());
		assertEquals(4, problems.size(), problems.toString());
		assertTrue(problems.get(0).startsWith("The version \"1.2-beta\" must be digits and dots"), problems.get(0));
		assertTrue(problems.get(1).startsWith("The plugin needs a contact address"), problems.get(1));
		assertEquals("The permission fileAccess is not one the client knows; use httpAccess, chatAccess or"
				+ " trackingAccess.", problems.get(2));
		assertTrue(problems.get(3).startsWith("The httpAccess permission only reaches the manifest's base URLs"),
				problems.get(3));
	}

	@Test
	void urlsNeedTheHttpPermissionAndAWebAddress() {
		List<String> problems = manifest("1.0.0", "plugins@example.org", List.of(),
				Map.of("prices", "ftp://prices.example.org")).problems(Set.of());
		assertEquals(List.of("The URL ftp://prices.example.org must be an http or https address with a host.",
				"Base URLs are only reached with the httpAccess permission; declare it, preferably optional, or remove"
						+ " the URLs."), problems);
	}
}
