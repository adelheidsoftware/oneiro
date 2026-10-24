package org.adelheid.oneiro.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The checks {@code checkPlugin} runs on the folder that ships, before it is zipped. The folder may hold only
 * {@code main.lua}, {@code plugin.json}, Lua files under {@code src/} and PNG or Ogg files under {@code resources/}.
 * Every {@code require} must name a file in it, no image may be larger than the client loads, and no Lua may call
 * {@code ExecuteConsoleCommand}, the client's only way into the console's development cheats.
 */
public final class PackageChecks {

	/** The largest image the client loads, in either dimension. */
	private static final int MAX_IMAGE_SIZE = 1024;

	private static final Pattern REQUIRE = Pattern.compile("\\brequire\\s*\\(?\\s*\"([^\"]*)\"");
	private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
	private static final byte[] OGG = {'O', 'g', 'g', 'S'};

	private PackageChecks() {
	}

	/** What is wrong with the folder, each as a sentence naming the file; empty when it may ship. */
	public static List<String> check(Path folder) throws IOException {
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
				luaProblems(folder, file, path, problems);
			}
		}
		return problems;
	}

	private static void luaProblems(Path folder, Path file, String path, List<String> problems) throws IOException {
		String text = Files.readString(file, StandardCharsets.UTF_8);
		Matcher required = REQUIRE.matcher(text);
		while (required.find()) {
			String module = required.group(1);
			if (!Files.isRegularFile(folder.resolve(module + ".lua"))) {
				problems.add(path + " requires \"" + module + "\", which is not in the plugin.");
			}
		}
		if (text.contains("ExecuteConsoleCommand")) {
			problems.add(path + " calls ExecuteConsoleCommand, which runs development cheats and must never ship.");
		}
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
