package org.adelheid.oneiro.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The manifest's plugin.json, and the problems that the client or Mod.io would refuse. */
class ManifestTest {

	private static Manifest manifest(String version, String email, List<String> optional, Map<String, String> urls) {
		return new Manifest("herb-tracker", "Herb Tracker", version, email,
				Map.of("prettyui", Manifest.Dependency.modIo(6398181)),
				Map.of("Markers", Manifest.Dependency.localFolder("TileMarkerLib")), List.of(), optional, urls, true,
				false);
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
				    "optionalDependencies": { "Markers": "TileMarkerLib" },
				    "requiredPermissions": [],
				    "optionalPermissions": [ "httpAccess" ],
				    "urls": { "prices": "https://prices.example.org" },
				    "hasSettingsIcon": true,
				    "hasRibbonIcon": false
				}
				""", json);
	}

	@Test
	void emptyDependenciesAreObjects() {
		Manifest empty = new Manifest("x", "X", "1", "a@b.org", Map.of(), Map.of(), List.of(), List.of(), Map.of(),
				false, false);
		assertTrue(empty.toJson().contains("\"requiredDependencies\": {},"), empty.toJson());
		assertTrue(empty.toJson().contains("\"optionalDependencies\": {},"), empty.toJson());
		assertEquals(List.of(), empty.problems());
	}

	@Test
	void reportsWhatWouldBeRefused() {
		Manifest refused = manifest("1.2-beta", "nobody", List.of("httpAccess", "fileAccess"), Map.of());
		List<String> problems = refused.problems();
		assertEquals(4, problems.size(), problems.toString());
		assertTrue(problems.get(0).startsWith("The version \"1.2-beta\" must be digits and dots"), problems.get(0));
		assertTrue(problems.get(1).startsWith("The plugin needs a contact address"), problems.get(1));
		assertTrue(problems.get(2).startsWith("The permission fileAccess is not one the client knows"),
				problems.get(2));
		assertTrue(problems.get(3).startsWith("The httpAccess permission only reaches the manifest's base URLs"),
				problems.get(3));
	}

	@Test
	void urlsNeedTheHttpPermissionAndAWebAddress() {
		List<String> problems = manifest("1.0.0", "plugins@example.org", List.of(),
				Map.of("prices", "ftp://prices.example.org")).problems();
		assertEquals(List.of("The URL ftp://prices.example.org must be an http or https address with a host.",
				"Base URLs are only reached with the httpAccess permission; declare it, preferably optional, or remove"
						+ " the URLs."), problems);
	}
}
