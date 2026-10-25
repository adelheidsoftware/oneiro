package org.adelheid.oneiro.plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The client's members that {@code checkPlugin} looks for in the Lua that ships, which the compiler knows from the
 * bindings' annotations: those that need a permission, such as {@code Chat.AddMessage}, and those for development only,
 * such as {@code ExecuteConsoleCommand}. Oneiro's bindings generator writes them next to the RS3 bindings, from the
 * overrides those come from, so that the Lua Oneiro did not compile is held to what the compiler holds Java and Kotlin
 * to.
 *
 * @param permissions the permission each member or table needs, by its Lua path
 * @param devOnly the Lua paths of the members for development only
 */
public record ClientFacts(Map<String, String> permissions, Set<String> devOnly) {

	/** The file's name, next to the RS3 bindings' jars. */
	public static final String FILE = "client-facts.txt";

	private static final Pattern SPACES = Pattern.compile("\\s+");

	public ClientFacts {
		permissions = Map.copyOf(permissions);
		devOnly = Set.copyOf(devOnly);
	}

	/** The facts in a file, one per line: {@code permission <path> <permission>} or {@code dev-only <path>}. */
	public static ClientFacts read(Path file) throws IOException {
		Map<String, String> permissions = new TreeMap<>();
		Set<String> devOnly = new TreeSet<>();
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			List<String> words = SPACES.splitAsStream(line.strip()).toList();
			if (words.size() == 3 && words.get(0).equals("permission")) {
				permissions.put(words.get(1), words.get(2));
			} else if (words.size() == 2 && words.get(0).equals("dev-only")) {
				devOnly.add(words.get(1));
			} else if (!line.isBlank() && !line.startsWith("#")) {
				throw new IOException(file + " has a line Oneiro does not understand: " + line);
			}
		}
		return new ClientFacts(permissions, devOnly);
	}

	/** Writes the facts to a file that {@link #read} reads, in sorted order. */
	public void write(Path file) throws IOException {
		List<String> lines = new ArrayList<>(List.of("# The client's members that checkPlugin looks for in the Lua that"
				+ " ships, written by Oneiro's bindings generator."));
		new TreeMap<>(permissions).forEach((path, permission) -> lines.add("permission " + path + " " + permission));
		new TreeSet<>(devOnly).forEach(path -> lines.add("dev-only " + path));
		Files.createDirectories(file.toAbsolutePath().getParent());
		Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
	}
}
