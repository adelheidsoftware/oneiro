package org.adelheid.oneiro.plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The Lua a plugin uses that is not its own, as {@code oneiroLibraries} stages it in a folder of its own: the files of
 * each library it vendors as they ship, under {@code main/} or, for the dev source set's, {@code dev/}; the LuaCATS
 * files each library's bindings come from under {@code types/<name>/}, a vendored library's own Lua or the types the
 * build names for a library plugin it depends on; the overrides file the build keeps for one, under
 * {@code overrides/}; and the list of them, {@link #FILE}. {@code oneiroLuaBindings} reads the folder to bind each
 * library, and {@code checkPlugin} the list to tell the vendored files from the plugin's own.
 *
 * @param libraries the libraries, in the order of their names
 */
public record LuaLibraries(List<Library> libraries) {

	/** The list's file in the folder. */
	public static final String FILE = "libraries.txt";

	public LuaLibraries {
		libraries = List.copyOf(libraries);
	}

	/** What a library is to the plugin that uses it. */
	public enum Use {

		/** Lua the plugin vendors, whose modules it requires. */
		VENDORED,

		/** A library plugin the plugin cannot do without, which the client gives it as the global of its alias. */
		REQUIRED,

		/** A library plugin the player may not have, or may turn off while the plugin runs. */
		OPTIONAL
	}

	/**
	 * One library.
	 *
	 * @param name its name, or a library plugin's alias, which names the package of its bindings
	 * @param development whether only the dev source set uses it, so that it goes into the development folder alone
	 * @param files the paths of its files in the plugin: a vendored library's Lua, from the plugin's root, and its
	 *     licence, under {@code licences/<name>/}; none for a library plugin, which ships on its own
	 */
	public record Library(String name, Use use, boolean development, List<String> files) {

		public Library {
			files = List.copyOf(files);
		}
	}

	/** The vendored libraries that ship, all but those of the dev source set alone. */
	public List<Library> shipped() {
		return libraries.stream().filter(library -> library.use() == Use.VENDORED && !library.development()).toList();
	}

	/** The folder of the libraries' files as they ship: the main source set's, or the dev source set's alone. */
	public static Path shippedFolder(Path folder, boolean development) {
		return folder.resolve(development ? "dev" : "main");
	}

	/** The folder of a library's LuaCATS files, laid out as its Lua is. */
	public static Path typesFolder(Path folder, String name) {
		return folder.resolve("types").resolve(name);
	}

	/** The overrides file the build keeps for a library, which exists only when it keeps one. */
	public static Path overridesFile(Path folder, String name) {
		return folder.resolve("overrides").resolve(name + ".txt");
	}

	/**
	 * The list in a file: a line {@code <use> <name>}, such as {@code vendored zyklib} or {@code optional prettyui},
	 * which ends in {@code development} for one of the dev source set alone, and then a line {@code file <path>} for
	 * each of its files.
	 */
	public static LuaLibraries read(Path file) throws IOException {
		List<Library> libraries = new ArrayList<>();
		Library library = null;
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			String[] words = line.split(" ", 3);
			Use use = use(words[0]);
			if (use != null && words.length >= 2 && (words.length == 2 || words[2].equals("development"))) {
				add(libraries, library);
				library = new Library(words[1], use, words.length == 3, List.of());
			} else if (words[0].equals("file") && words.length >= 2 && library != null) {
				List<String> files = new ArrayList<>(library.files());
				files.add(line.substring("file ".length()));
				library = new Library(library.name(), library.use(), library.development(), files);
			} else if (!line.isBlank() && !line.startsWith("#")) {
				throw new IOException(file + " has a line Oneiro does not understand: " + line);
			}
		}
		add(libraries, library);
		return new LuaLibraries(libraries);
	}

	private static @Nullable Use use(String word) {
		for (Use use : Use.values()) {
			if (word.equals(use.name().toLowerCase(Locale.ROOT))) {
				return use;
			}
		}
		return null;
	}

	private static void add(List<Library> libraries, @Nullable Library library) {
		if (library != null) {
			libraries.add(library);
		}
	}

	/** Writes the list to a file that {@link #read} reads. */
	public void write(Path file) throws IOException {
		StringBuilder text = new StringBuilder("# The Lua libraries the plugin uses and their files, written by"
				+ " Oneiro's oneiroLibraries task.\n");
		for (Library library : libraries) {
			text.append(library.use().name().toLowerCase(Locale.ROOT)).append(' ').append(library.name())
					.append(library.development() ? " development\n" : "\n");
			library.files().forEach(path -> text.append("file ").append(path).append('\n'));
		}
		Files.createDirectories(file.toAbsolutePath().getParent());
		Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
	}
}
