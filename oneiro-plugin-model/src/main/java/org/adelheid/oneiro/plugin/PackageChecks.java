package org.adelheid.oneiro.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The checks {@code checkPlugin} runs on the folder that ships, before it is zipped. Every dependency its manifest
 * names has a Mod.io ID, by which players' clients find it. The folder may hold only
 * {@code main.lua}, {@code plugin.json}, Lua files under {@code src/}, PNG or Ogg files under {@code resources/}, and
 * the files of the libraries it vendors, their Lua and their licences. Every Lua file under {@code src/}, Oneiro's or
 * the plugin's own, starts with its path, by which the client's errors name it; every {@code require} names a file in
 * the folder; each library is required by the plugin's own Lua or the Lua Oneiro writes, since moderators would read
 * its code for nothing otherwise; no image is larger than the client loads; and no Lua uses a member of the client's
 * that is for development only, such as {@code ExecuteConsoleCommand}, the client's way into the console's cheats, nor,
 * but in a library, one that needs a permission the manifest lacks. The compiler holds the Lua it writes to those last
 * two already; the check holds the Lua it did not write to them. A library's code may name members of every
 * permission, in parts the plugin does not call, so the permissions are the plugin's own Lua's to check.
 */
public final class PackageChecks {

	/** The largest image the client loads, in either dimension. */
	private static final int MAX_IMAGE_SIZE = 1024;

	private static final Pattern REQUIRE = Pattern.compile("\\brequire\\s*\\(?\\s*([\"'])([^\"']*)\\1");
	private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
	private static final byte[] OGG = {'O', 'g', 'g', 'S'};

	private PackageChecks() {
	}

	/**
	 * What is wrong with the folder, each as a sentence naming the file; empty when it may ship.
	 *
	 * @param facts the client's members that need a permission or are for development only
	 * @param permissions the permissions the manifest requests, required or optional
	 * @param libraries the libraries the plugin vendors and ships
	 * @param unpublished the dependencies that ship but name only a folder on this machine, each by its alias, with
	 *     its kind, {@code required} or {@code optional}
	 */
	public static List<String> check(Path folder, ClientFacts facts, Set<String> permissions,
			List<LuaLibraries.Library> libraries, Map<String, String> unpublished) throws IOException {
		// Each vendored file by the library it comes from
		Map<String, String> vendored = new HashMap<>();
		libraries.forEach(library -> library.files().forEach(path -> vendored.put(path, library.name())));
		List<String> problems = new ArrayList<>();
		for (String required : List.of("main.lua", "plugin.json")) {
			if (!Files.isRegularFile(folder.resolve(required))) {
				problems.add("The plugin has no " + required + ", which the client needs at the top of the folder.");
			}
		}
		new TreeMap<>(unpublished).forEach((alias, kind) -> problems.add("The " + kind + " dependency " + alias
				+ " names only a folder on this machine, with devFolder, so the plugin cannot ship: players' clients"
				+ " find a dependency by its Mod.io ID. Once the library is published, name it, " + kind + "(\""
				+ alias + "\", <Mod.io ID>) { devFolder(...) }, and the development folder still uses the folder."));
		List<Path> files;
		try (Stream<Path> walk = Files.walk(folder)) {
			files = walk.filter(Files::isRegularFile).sorted().toList();
		}
		for (Path file : files) {
			String path = folder.relativize(file).toString().replace('\\', '/');
			if (path.equals("main.lua") || path.equals("plugin.json") || vendored.containsKey(path)) {
				continue;
			}
			if (path.startsWith("src/") && path.endsWith(".lua")) {
				continue;
			}
			if (path.startsWith("resources/")) {
				resourceProblems(file, path, problems);
				continue;
			}
			problems.add(path + " is not part of a plugin: only main.lua, plugin.json, Lua files under src/, PNG or"
					+ " Ogg files under resources/, and the files of the libraries the build vendors ship.");
		}
		// The files the plugin's own Lua and Oneiro's require, which a library must be among
		Set<String> required = new HashSet<>();
		for (Path file : files) {
			String path = folder.relativize(file).toString().replace('\\', '/');
			if (path.endsWith(".lua")) {
				Set<String> requires = luaProblems(folder, file, path, vendored.containsKey(path), facts, permissions,
						problems);
				if (!vendored.containsKey(path)) {
					required.addAll(requires);
				}
			}
		}
		for (LuaLibraries.Library library : libraries) {
			if (library.files().stream().noneMatch(required::contains)) {
				problems.add(library.name() + " is vendored, but no Lua of the plugin's requires it, so moderators"
						+ " would read its code for nothing; use it, or remove it from the build.");
			}
		}
		return problems;
	}

	/**
	 * The problems of a Lua file, and the files it requires.
	 *
	 * @param vendored whether the file is a library's, which need not start with its path, and may name members of
	 *     permissions the manifest lacks
	 */
	private static Set<String> luaProblems(Path folder, Path file, String path, boolean vendored, ClientFacts facts,
			Set<String> permissions, List<String> problems) throws IOException {
		String text = Files.readString(file, StandardCharsets.UTF_8);
		boolean startsWithPath = text.startsWith("-- " + path + "\n") || text.startsWith("-- " + path + "\r");
		if (path.startsWith("src/") && !vendored && !startsWithPath) {
			problems.add(path + " does not start with its path, -- " + path
					+ ", by which the client's errors name it.");
		}
		Set<String> requires = new HashSet<>();
		Matcher required = REQUIRE.matcher(text);
		while (required.find()) {
			String module = required.group(2) + ".lua";
			requires.add(module);
			if (!Files.isRegularFile(folder.resolve(module))) {
				problems.add(path + " requires \"" + required.group(2) + "\", which is not in the plugin.");
			}
		}
		for (Map.Entry<String, String> needed : facts.permissions().entrySet()) {
			if (!vendored && !permissions.contains(needed.getValue()) && uses(text, needed.getKey())) {
				problems.add(path + " uses " + needed.getKey() + ", which needs the " + needed.getValue()
						+ " permission the manifest does not declare; add it to the build, oneiro { plugin {"
						+ " permissions { optional(" + needed.getValue() + ") } } }.");
			}
		}
		for (String devOnly : facts.devOnly()) {
			if (uses(text, devOnly)) {
				problems.add(path + " uses " + devOnly + ", which is for development only and must never ship.");
			}
		}
		return requires;
	}

	/** Whether Lua code names a member by its path, as a whole: {@code HTTPRequest} in {@code HTTPRequest.new(...)}. */
	private static boolean uses(String text, String path) {
		return Pattern.compile("(?<![\\w.])" + Pattern.quote(path) + "(?!\\w)").matcher(text).find();
	}

	private static void resourceProblems(Path file, String path, List<String> problems) throws IOException {
		byte[] start = new byte[24];
		int read;
		try (InputStream in = Files.newInputStream(file)) {
			read = in.readNBytes(start, 0, start.length);
		}
		byte[] header = Arrays.copyOf(start, read);
		if (path.endsWith(".png") && startsWith(header, PNG)) {
			// The width and height are the first fields of the IHDR chunk, right after the signature
			if (header.length >= 24) {
				long width = unsigned(header, 16);
				long height = unsigned(header, 20);
				if (width > MAX_IMAGE_SIZE || height > MAX_IMAGE_SIZE) {
					problems.add(path + " is " + width + " by " + height + " pixels; the client loads images up to "
							+ MAX_IMAGE_SIZE + " by " + MAX_IMAGE_SIZE + ".");
				}
			}
			return;
		}
		if (path.endsWith(".ogg") && startsWith(header, OGG)) {
			return;
		}
		problems.add(path + " is not a PNG image or an Ogg Vorbis sound, the only resources the client loads.");
	}

	private static boolean startsWith(byte[] bytes, byte[] prefix) {
		return bytes.length >= prefix.length && Arrays.equals(Arrays.copyOf(bytes, prefix.length), prefix);
	}

	private static long unsigned(byte[] bytes, int offset) {
		return ((bytes[offset] & 0xFFL) << 24) | ((bytes[offset + 1] & 0xFFL) << 16)
				| ((bytes[offset + 2] & 0xFFL) << 8) | (bytes[offset + 3] & 0xFFL);
	}
}
