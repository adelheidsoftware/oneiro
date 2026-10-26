package org.adelheid.oneiro.plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The Lua libraries a plugin vendors, as {@code oneiroLibraries} stages them in a folder of its own: each library's
 * files as they ship, under {@code main/} or, for the dev source set's, {@code dev/}; its Lua again under
 * {@code types/<name>/}, which its bindings come from; the overrides file the build keeps for it, under
 * {@code overrides/}; and the list of them, {@link #FILE}. {@code oneiroLuaBindings} reads the folder to bind each
 * library, and {@code checkPlugin} the list to tell the vendored files from the plugin's own.
 *
 * @param libraries the libraries, in the order of their names
 */
public record VendoredLibraries(List<Library> libraries) {

	/** The list's file in the folder. */
	public static final String FILE = "libraries.txt";

	public VendoredLibraries {
		libraries = List.copyOf(libraries);
	}

	/**
	 * One library.
	 *
	 * @param development whether only the dev source set uses it, so that it goes into the development folder alone
	 * @param files the paths of its files in the plugin: its Lua, from the plugin's root, and its licence, under
	 *     {@code licences/<name>/}
	 */
	public record Library(String name, boolean development, List<String> files) {

		public Library {
			files = List.copyOf(files);
		}
	}

	/** The libraries that ship, all but those of the dev source set alone. */
	public List<Library> shipped() {
		return libraries.stream().filter(library -> !library.development()).toList();
	}

	/** The folder of the libraries' files as they ship: the main source set's, or the dev source set's alone. */
	public static Path shippedFolder(Path folder, boolean development) {
		return folder.resolve(development ? "dev" : "main");
	}

	/** The folder of a library's LuaCATS files: its Lua, laid out as it ships. */
	public static Path typesFolder(Path folder, String name) {
		return folder.resolve("types").resolve(name);
	}

	/** The overrides file the build keeps for a library, which exists only when it keeps one. */
	public static Path overridesFile(Path folder, String name) {
		return folder.resolve("overrides").resolve(name + ".txt");
	}

	/**
	 * The list in a file: a line {@code library <name>}, or {@code development <name>} for one of the dev source set
	 * alone, and then a line {@code file <path>} for each of its files.
	 */
	public static VendoredLibraries read(Path file) throws IOException {
		List<Library> libraries = new ArrayList<>();
		String name = null;
		boolean development = false;
		List<String> files = new ArrayList<>();
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			int space = line.indexOf(' ');
			String kind = space < 0 ? line : line.substring(0, space);
			String value = space < 0 ? "" : line.substring(space + 1);
			switch (kind) {
				case "library", "development" -> {
					add(libraries, name, development, files);
					name = value;
					development = kind.equals("development");
					files = new ArrayList<>();
				}
				case "file" -> files.add(value);
				default -> {
					if (!line.isBlank() && !line.startsWith("#")) {
						throw new IOException(file + " has a line Oneiro does not understand: " + line);
					}
				}
			}
		}
		add(libraries, name, development, files);
		return new VendoredLibraries(libraries);
	}

	private static void add(List<Library> libraries, @Nullable String name, boolean development, List<String> files) {
		if (name != null) {
			libraries.add(new Library(name, development, files));
		}
	}

	/** Writes the list to a file that {@link #read} reads. */
	public void write(Path file) throws IOException {
		StringBuilder text = new StringBuilder("# The libraries the plugin vendors and their files, written by Oneiro's"
				+ " oneiroLibraries task.\n");
		for (Library library : libraries) {
			text.append(library.development() ? "development " : "library ").append(library.name()).append('\n');
			library.files().forEach(path -> text.append("file ").append(path).append('\n'));
		}
		Files.createDirectories(file.toAbsolutePath().getParent());
		Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
	}
}
