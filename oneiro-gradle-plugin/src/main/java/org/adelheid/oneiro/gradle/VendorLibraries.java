package org.adelheid.oneiro.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.adelheid.oneiro.plugin.VendoredLibraries;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.jspecify.annotations.Nullable;

/**
 * {@code oneiroLibraries}: the Lua libraries the plugin vendors, each from a checkout on this machine or a repository
 * fetched at a commit, staged as they ship ({@link VendoredLibraries}): the Lua files of the folder the build names,
 * laid out in the plugin's root as the library documents, and its licence under {@code licences/<name>/}. A library
 * without a licence fails, since nobody may pass on its code without one, and so does a file where the plugin's own
 * go, or that another library has too.
 */
@CacheableTask
public abstract class VendorLibraries extends DefaultTask {

	/** A file named like a licence: {@code LICENSE}, {@code LICENCE.md}, {@code COPYING.txt}. */
	private static final Pattern LICENCE = Pattern.compile("(?i)(licen[cs]e|copying)(\\.[a-z]+)?");

	private final List<OneiroExtension.VendoredLibrary> libraries = new ArrayList<>();

	/** The libraries the build declares. */
	@Nested
	public List<OneiroExtension.VendoredLibrary> getLibraries() {
		return libraries;
	}

	/** The files read from the libraries' checkouts on this machine: the Lua of each, and the files at its root. */
	@InputFiles
	@IgnoreEmptyDirectories
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getLocalFiles();

	/** Where fetched repositories are kept, one folder per commit, in Gradle's user home. */
	@Internal
	public abstract DirectoryProperty getCache();

	@OutputDirectory
	public abstract DirectoryProperty getOutputFolder();

	@TaskAction
	public void vendor() {
		try {
			Path output = getOutputFolder().get().getAsFile().toPath();
			clean(output);
			List<String> problems = new ArrayList<>();
			// Each file's path in the plugin, by the library that has it, and what each staged file copies
			Map<String, String> taken = new HashMap<>();
			Map<Path, Path> copies = new LinkedHashMap<>();
			List<VendoredLibraries.Library> listed = new ArrayList<>();
			List<OneiroExtension.VendoredLibrary> sorted = libraries.stream()
					.sorted(Comparator.comparing(OneiroExtension.VendoredLibrary::getName))
					.toList();
			for (OneiroExtension.VendoredLibrary library : sorted) {
				String name = library.getName();
				boolean development = library.getDevelopment().get();
				Path root = root(library);
				Path from = root.resolve(library.getFrom().get()).normalize();
				if (!from.startsWith(root) || !Files.isDirectory(from)) {
					problems.add(name + " has no folder " + library.getFrom().get() + " to vendor; from(...) names a"
							+ " folder of the library, from its root.");
					continue;
				}
				List<String> lua = luaFiles(from);
				if (lua.isEmpty()) {
					problems.add(name + " has no Lua files in " + (library.getFrom().get().isEmpty() ? "its root"
							: library.getFrom().get()) + "; from(...) names the folder whose Lua goes to the plugin.");
				}
				Path shipped = VendoredLibraries.shippedFolder(output, development);
				List<String> files = new ArrayList<>();
				for (String path : lua) {
					String reserved = reserved(path);
					String other = taken.putIfAbsent(path, name);
					if (reserved != null) {
						problems.add(name + "'s " + path + " would ship " + reserved + "; from(...) names the folder"
								+ " of the library whose Lua goes to the plugin's root, as its documentation lays it"
								+ " out.");
					} else if (other != null) {
						problems.add(name + " and " + other + " both have " + path + ", which only one file of the"
								+ " plugin can be.");
					} else {
						files.add(path);
						copies.put(shipped.resolve(path), from.resolve(path));
						copies.put(VendoredLibraries.typesFolder(output, name).resolve(path), from.resolve(path));
					}
				}
				Path licence = licence(library, root, problems);
				if (licence != null) {
					String path = "licences/" + name + "/" + licence.getFileName();
					files.add(path);
					copies.put(shipped.resolve(path), licence);
				}
				if (library.getOverrides().isPresent()) {
					copies.put(VendoredLibraries.overridesFile(output, name),
							library.getOverrides().get().getAsFile().toPath());
				}
				listed.add(new VendoredLibraries.Library(name, development, files));
			}
			if (!problems.isEmpty()) {
				throw new GradleException("The plugin's libraries cannot be vendored:\n  - "
						+ String.join("\n  - ", problems));
			}
			for (Map.Entry<Path, Path> copy : copies.entrySet()) {
				Files.createDirectories(copy.getKey().getParent());
				Files.copy(copy.getValue(), copy.getKey(), StandardCopyOption.REPLACE_EXISTING);
			}
			new VendoredLibraries(listed).write(output.resolve(VendoredLibraries.FILE));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The root of a library's code: its checkout on this machine, or its repository's commit, fetched once. */
	private Path root(OneiroExtension.VendoredLibrary library) throws IOException {
		String name = library.getName();
		boolean local = library.getLocal().isPresent();
		boolean remote = library.getRemote().isPresent() && library.getCommit().isPresent();
		if (local == remote) {
			throw new GradleException((local ? name + " names its code twice" : name + " names no code")
					+ "; give one of local(file(\"...\")), a checkout on this machine, or remote(\"<url>\","
					+ " \"<commit>\"), a repository on GitHub, Codeberg or GitLab at a full commit.");
		}
		if (local) {
			return library.getLocal().get().getAsFile().toPath().toAbsolutePath().normalize();
		}
		RemoteRepository repository = RemoteRepository.parse(library.getRemote().get());
		String commit = library.getCommit().get();
		getLogger().info("Oneiro vendors {} from {} at commit {}.", name, repository.url(), commit);
		return repository.checkout(commit, getCache().get().getAsFile().toPath()).toAbsolutePath().normalize();
	}

	/** The paths of the Lua files under a folder, from it, in order. */
	private static List<String> luaFiles(Path folder) throws IOException {
		try (Stream<Path> walk = Files.walk(folder)) {
			return walk.filter(Files::isRegularFile)
					.filter(file -> file.getFileName().toString().endsWith(".lua"))
					.map(file -> folder.relativize(file).toString().replace('\\', '/'))
					.sorted()
					.toList();
		}
	}

	/** Where in the plugin a path is the plugin's own, as a phrase; null for a path a library may take. */
	private static @Nullable String reserved(String path) {
		if (path.equals("main.lua")) {
			return "in place of the plugin's main.lua";
		}
		for (Map.Entry<String, String> folder : Map.of(
				"src/", "in the plugin's src/ folder, which holds its own Lua and the Lua Oneiro writes",
				"dev/", "in the plugin's dev/ folder, the dev source set's",
				"resources/", "in the plugin's resources/ folder",
				"licences/", "in the plugin's licences/ folder, which holds the libraries' licences").entrySet()) {
			if (path.startsWith(folder.getKey())) {
				return folder.getValue();
			}
		}
		return null;
	}

	/**
	 * A library's licence: the file the build names, or the one file at its root named like a licence. A library
	 * without one is reported, as is one with several the build does not choose between.
	 */
	private static @Nullable Path licence(OneiroExtension.VendoredLibrary library, Path root, List<String> problems)
			throws IOException {
		String name = library.getName();
		if (library.getLicence().isPresent()) {
			Path named = root.resolve(library.getLicence().get()).normalize();
			if (!named.startsWith(root) || !Files.isRegularFile(named)) {
				problems.add(name + " has no file " + library.getLicence().get() + "; licence(...) names the file of"
						+ " its licence, from the library's root.");
				return null;
			}
			return named;
		}
		List<Path> found;
		try (Stream<Path> files = Files.list(root)) {
			found = files.filter(Files::isRegularFile)
					.filter(file -> LICENCE.matcher(file.getFileName().toString()).matches())
					.sorted()
					.toList();
		}
		if (found.size() == 1) {
			return found.get(0);
		}
		problems.add(found.isEmpty()
				? name + " has no licence at its root; name its file with licence(\"...\"). A library without a licence"
						+ " cannot be vendored: nobody may pass on its code without one."
				: name + " has several files that may be its licence, " + found.stream()
						.map(file -> file.getFileName().toString())
						.collect(Collectors.joining(" and ")) + "; name the one that ships with licence(\"...\").");
		return null;
	}

	private static void clean(Path folder) throws IOException {
		if (!Files.exists(folder)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(folder)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				if (!path.equals(folder)) {
					Files.delete(path);
				}
			}
		}
	}
}
