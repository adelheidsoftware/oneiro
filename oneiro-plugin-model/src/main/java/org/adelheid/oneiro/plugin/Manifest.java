package org.adelheid.oneiro.plugin;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * A plugin's manifest, as the build's {@code oneiro { plugin { } }} block configures it, written as the
 * {@code plugin.json} the client reads: the shipped one, or the development folder's, {@link #development()}. The traps
 * of the hand-written file cannot be written: the dependency fields are always objects ({@code {}} when empty, because
 * the client rejects the whole manifest for the array form), and {@link #problems} names everything else the client or
 * Mod.io would refuse.
 *
 * @param id the plugin's identifier: the dev link's name and the zip's, lowercase words joined by hyphens
 * @param version digits and dots, as Mod.io requires; the build's {@code project.version}
 * @param contactEmail the address the legal sections require, which should not be a Mod.io or RuneScape login
 * @param requiredDependencies plugins that must be present, by the Lua global each is exposed as
 * @param optionalDependencies plugins used when present, by the Lua global each is exposed as
 * @param developmentDependencies plugins only the dev source set uses, which the development folder's manifest alone
 *     requires
 * @param urls the base URLs the plugin may contact, by the alias code uses, {@code Plugin.urls.<alias>}
 */
public record Manifest(
		String id,
		String name,
		String version,
		String contactEmail,
		Map<String, Dependency> requiredDependencies,
		Map<String, Dependency> optionalDependencies,
		Map<String, Dependency> developmentDependencies,
		List<String> requiredPermissions,
		List<String> optionalPermissions,
		Map<String, String> urls,
		boolean settingsIcon,
		boolean ribbonIcon) {

	/** The permissions a plugin may request, by their names in {@code plugin.json}. */
	public static final Set<String> PERMISSIONS =
			Arrays.stream(Permission.values()).map(Permission::manifestName).collect(Collectors.toUnmodifiableSet());

	private static final Pattern ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
	private static final Pattern VERSION = Pattern.compile("\\d+(\\.\\d+)*");
	private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
	// Lua's names and keywords, as in oneiro-lua-tree's LuaNames, which this module cannot use: it runs in the Gradle
	// daemon, on Java 17
	private static final Pattern LUA_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
	private static final Set<String> LUA_KEYWORDS = Set.of("and", "break", "do", "else", "elseif", "end", "false",
			"for", "function", "goto", "if", "in", "local", "nil", "not", "or", "repeat", "return", "then", "true",
			"until", "while");
	// Lua 5.4's own globals, and those a plugin defines for the client to call, which the stubs do not declare; the
	// client's API's come from the stubs (ClientFacts)
	private static final Set<String> LUA_GLOBALS = Set.of("_G", "_VERSION", "assert", "collectgarbage", "coroutine",
			"debug", "dofile", "error", "getmetatable", "io", "ipairs", "load", "loadfile", "math", "next", "os",
			"package", "pairs", "pcall", "print", "rawequal", "rawget", "rawlen", "rawset", "require", "select",
			"setmetatable", "string", "table", "tonumber", "tostring", "type", "utf8", "warn", "xpcall");
	private static final Set<String> CALLBACKS = Set.of("PluginShutdown", "PluginDependencyUpdate");

	/**
	 * A plugin this one depends on: a published one by its Mod.io ID, and the
	 * folder of a plugin on this machine that the development folder's manifest names instead, for a library not
	 * published yet or a local copy of one. At least one is set; a dependency named only by its folder cannot ship.
	 */
	public record Dependency(@Nullable Integer modId, @Nullable String devFolder) {

		public static Dependency modIo(int modId) {
			return new Dependency(modId, null);
		}

		/** The dependency as the development folder's manifest names it: by its folder, where it has one. */
		Dependency forDevelopment() {
			return devFolder != null ? new Dependency(null, devFolder) : this;
		}
	}

	public Manifest {
		requiredDependencies = Map.copyOf(requiredDependencies);
		optionalDependencies = Map.copyOf(optionalDependencies);
		developmentDependencies = Map.copyOf(developmentDependencies);
		requiredPermissions = List.copyOf(requiredPermissions);
		optionalPermissions = List.copyOf(optionalPermissions);
		urls = Map.copyOf(urls);
	}

	/**
	 * The development folder's manifest: the development dependencies are required beside the others, and each
	 * dependency with a folder on this machine names it in place of its Mod.io ID.
	 */
	public Manifest development() {
		Map<String, Dependency> required = new LinkedHashMap<>();
		requiredDependencies.forEach((alias, dependency) -> required.put(alias, dependency.forDevelopment()));
		developmentDependencies.forEach((alias, dependency) -> required.put(alias, dependency.forDevelopment()));
		Map<String, Dependency> optional = new LinkedHashMap<>();
		optionalDependencies.forEach((alias, dependency) -> optional.put(alias, dependency.forDevelopment()));
		return new Manifest(id, name, version, contactEmail, required, optional, Map.of(), requiredPermissions,
				optionalPermissions, urls, settingsIcon, ribbonIcon);
	}

	/** Every permission the manifest requests, required or optional. */
	public Set<String> permissions() {
		Set<String> all = new TreeSet<>(requiredPermissions);
		all.addAll(optionalPermissions);
		return all;
	}

	/** The names that {@code plugin.json} gives the flags that are set, for the settings icon and the ribbon icon. */
	public static Set<String> flags(boolean settingsIcon, boolean ribbonIcon) {
		Set<String> flags = new TreeSet<>();
		if (settingsIcon) {
			flags.add("hasSettingsIcon");
		}
		if (ribbonIcon) {
			flags.add("hasRibbonIcon");
		}
		return flags;
	}

	/**
	 * What the client or Mod.io would refuse, each as a sentence that says how to fix it; empty when nothing.
	 *
	 * @param clientGlobals the names the client's API takes in Lua's global table, which an alias may not take
	 */
	public List<String> problems(Set<String> clientGlobals) {
		List<String> problems = new ArrayList<>();
		if (!ID.matcher(id).matches()) {
			problems.add("The plugin id \"" + id + "\" must be lowercase letters and digits, with words joined by"
					+ " hyphens, such as herb-tracker; it names the dev link and the zip.");
		}
		if (name.isBlank()) {
			problems.add("The plugin needs a name, the one people see: oneiro { plugin { name = \"Herb Tracker\" } }.");
		}
		if (!VERSION.matcher(version).matches()) {
			problems.add("The version \"" + version + "\" must be digits and dots, such as 1.2.0, as Mod.io requires;"
					+ " it is the project's version.");
		}
		if (!EMAIL.matcher(contactEmail).matches()) {
			problems.add("The plugin needs a contact address, which the legal sections require: oneiro { plugin {"
					+ " contactEmail = \"plugins@example.org\" } }. Use one that is not a Mod.io or RuneScape login.");
		}
		// Each alias's kinds, of which it may have one
		Map<String, List<String>> kinds = new TreeMap<>();
		dependencyProblems(requiredDependencies, "required", clientGlobals, kinds, problems);
		dependencyProblems(optionalDependencies, "optional", clientGlobals, kinds, problems);
		dependencyProblems(developmentDependencies, "development", clientGlobals, kinds, problems);
		kinds.forEach((alias, declared) -> {
			if (declared.size() > 1) {
				problems.add("The dependency " + alias + " is " + String.join(" and ", declared) + " at once; keep"
						+ " one.");
			}
		});
		for (String permission : permissions()) {
			if (!PERMISSIONS.contains(permission)) {
				problems.add("The permission " + permission + " is not one the client knows; use "
						+ permissionChoices() + ".");
			}
			if (requiredPermissions.contains(permission) && optionalPermissions.contains(permission)) {
				problems.add("The permission " + permission + " is both required and optional; keep one, preferably"
						+ " optional.");
			}
		}
		for (Map.Entry<String, String> url : urls.entrySet()) {
			if (!isLuaName(url.getKey())) {
				problems.add("The URL alias " + url.getKey() + " must be a Lua name, because code reads it as"
						+ " Plugin.urls." + url.getKey() + ".");
			}
			if (!isWebAddress(url.getValue())) {
				problems.add("The URL " + url.getValue() + " must be an http or https address with a host.");
			}
		}
		boolean http = permissions().contains(Permission.HTTP_ACCESS.manifestName());
		if (http && urls.isEmpty()) {
			problems.add("The httpAccess permission only reaches the manifest's base URLs; add one: oneiro { plugin {"
					+ " urls { url(\"prices\", \"https://prices.example.org\") } } }.");
		}
		if (!http && !urls.isEmpty()) {
			problems.add("Base URLs are only reached with the httpAccess permission; declare it, preferably optional,"
					+ " or remove the URLs.");
		}
		return problems;
	}

	private static void dependencyProblems(Map<String, Dependency> dependencies, String kind,
			Set<String> clientGlobals, Map<String, List<String>> kinds, List<String> problems) {
		for (Map.Entry<String, Dependency> dependency : new TreeMap<>(dependencies).entrySet()) {
			String alias = dependency.getKey();
			kinds.computeIfAbsent(alias, declared -> new ArrayList<>()).add(kind);
			boolean lua = LUA_GLOBALS.contains(alias);
			if (!isLuaName(alias)) {
				problems.add("The " + kind + " dependency's alias " + alias + " must be a Lua name, because the client"
						+ " exposes the dependency as the global of that name.");
			} else if (lua || clientGlobals.contains(alias) || CALLBACKS.contains(alias)) {
				problems.add("The " + kind + " dependency's alias " + alias + " is a global of "
						+ (lua ? "Lua's" : "the client's") + " already, and the client exposes the dependency as the"
						+ " global of its alias; choose another, such as the library's name.");
			}
			Dependency target = dependency.getValue();
			Integer modId = target.modId();
			String folder = target.devFolder();
			if ((modId == null && folder == null) || (modId != null && modId <= 0)
					|| (folder != null && folder.isBlank())) {
				problems.add("The " + kind + " dependency " + alias + " needs its Mod.io ID, or, for a library not"
						+ " published yet, devFolder(...) with the folder of a plugin on this machine.");
			}
		}
	}

	/** The permissions' names as a choice: {@code httpAccess, chatAccess or trackingAccess}. */
	private static String permissionChoices() {
		List<String> names = Arrays.stream(Permission.values()).map(Permission::manifestName).toList();
		return String.join(", ", names.subList(0, names.size() - 1)) + " or " + names.get(names.size() - 1);
	}

	static boolean isLuaName(String name) {
		return LUA_NAME.matcher(name).matches() && !LUA_KEYWORDS.contains(name);
	}

	private static boolean isWebAddress(String address) {
		try {
			URI uri = new URI(address);
			String scheme = uri.getScheme();
			return scheme != null && Set.of("http", "https").contains(scheme.toLowerCase(Locale.ROOT))
					&& uri.getHost() != null;
		} catch (URISyntaxException e) {
			return false;
		}
	}

	/**
	 * The manifest as {@code plugin.json}, in the order and layout of the official example: four-space indentation,
	 * short objects and lists on one line, both dependency fields always objects. Each dependency is named by its
	 * Mod.io ID, or by its folder where it has none; the development dependencies are left out, as they are the
	 * development folder's alone.
	 */
	public String toJson() {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("name", Json.string(name));
		fields.put("version", Json.string(version));
		fields.put("contactEmailAddress", Json.string(contactEmail));
		fields.put("requiredDependencies", dependencies(requiredDependencies));
		fields.put("optionalDependencies", dependencies(optionalDependencies));
		fields.put("requiredPermissions", Json.list(requiredPermissions));
		fields.put("optionalPermissions", Json.list(optionalPermissions));
		Map<String, String> urlValues = new LinkedHashMap<>();
		new TreeMap<>(urls).forEach((alias, url) -> urlValues.put(alias, Json.string(url)));
		fields.put("urls", Json.object(urlValues));
		fields.put("hasSettingsIcon", Boolean.toString(settingsIcon));
		fields.put("hasRibbonIcon", Boolean.toString(ribbonIcon));
		StringBuilder json = new StringBuilder("{\n");
		int index = 0;
		for (Map.Entry<String, String> field : fields.entrySet()) {
			json.append("    ").append(Json.string(field.getKey())).append(": ").append(field.getValue())
					.append(++index < fields.size() ? ",\n" : "\n");
		}
		return json.append("}\n").toString();
	}

	private static String dependencies(Map<String, Dependency> dependencies) {
		Map<String, String> values = new LinkedHashMap<>();
		new TreeMap<>(dependencies).forEach((alias, dependency) -> {
			Integer modId = dependency.modId();
			values.put(alias, modId != null ? modId.toString() : Json.string(String.valueOf(dependency.devFolder())));
		});
		return Json.object(values);
	}
}
