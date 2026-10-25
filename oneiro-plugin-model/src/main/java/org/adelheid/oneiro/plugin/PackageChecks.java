package org.adelheid.oneiro.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The checks {@code checkPlugin} runs on the folder that ships, before it is zipped. The folder may hold only
 * {@code main.lua}, {@code plugin.json}, Lua files under {@code src/} and PNG or Ogg files under {@code resources/}.
 * Every Lua file under {@code src/}, Oneiro's or the plugin's own, starts with its path, by which the client's errors
 * name it; every {@code require} names a file in the folder; no image is larger than the client loads; and no Lua uses
 * a member of the client's that needs a permission the manifest lacks, or that is for development only, such as
 * {@code ExecuteConsoleCommand}, the client's way into the console's cheats. The compiler holds the Lua it writes to
 * those last two already; the check holds the plugin's own Lua to them.
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
	 */
	public static List<String> check(Path folder, ClientFacts facts, Set<String> permissions) throws IOException {
		List<String> problems = new ArrayList<>();
		for (String required : List.of("main.lua", "plugin.json")) {
			if (!Files.isRegularFile(folder.resolve(required))) {
				problems.add("The plugin has no " + required + ", which the client needs at the top of the folder.");
			}
		}
		List<Path> files;
		try (Stream<Path> walk = Files.walk(folder)) {
			files = walk.filter(Files::isRegularFile).sorted().toList();
		}
		for (Path file : files) {
			String path = folder.relativize(file).toString().replace('\\', '/');
			if (path.equals("main.lua") || path.equals("plugin.json")) {
				continue;
			}
			if (path.startsWith("src/") && path.endsWith(".lua")) {
				continue;
			}
			if (path.startsWith("resources/")) {
				resourceProblems(file, path, problems);
				continue;
			}
			problems.add(path + " is not part of a plugin: only main.lua, plugin.json, Lua files under src/ and"
					+ " PNG or Ogg files under resources/ ship.");
		}
		for (Path file : files) {
			String path = folder.relativize(file).toString().replace('\\', '/');
			if (path.endsWith(".lua")) {
				luaProblems(folder, file, path, facts, permissions, problems);
			}
		}
		return problems;
	}

	private static void luaProblems(Path folder, Path file, String path, ClientFacts facts, Set<String> permissions,
			List<String> problems) throws IOException {
		String text = Files.readString(file, StandardCharsets.UTF_8);
		if (path.startsWith("src/") && !text.startsWith("-- " + path + "\n") && !text.startsWith("-- " + path + "\r")) {
			problems.add(path + " does not start with its path, -- " + path
					+ ", by which the client's errors name it.");
		}
		Matcher required = REQUIRE.matcher(text);
		while (required.find()) {
			String module = required.group(2);
			if (!Files.isRegularFile(folder.resolve(module + ".lua"))) {
				problems.add(path + " requires \"" + module + "\", which is not in the plugin.");
			}
		}
		for (Map.Entry<String, String> needed : facts.permissions().entrySet()) {
			if (!permissions.contains(needed.getValue()) && uses(text, needed.getKey())) {
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
