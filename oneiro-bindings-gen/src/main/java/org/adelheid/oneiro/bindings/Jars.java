package org.adelheid.oneiro.bindings;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Writes reproducible jars: entries in sorted order, each with the same fixed time, so equal input gives equal bytes.
 */
final class Jars {

	/**
	 * 1 February 1980, the time Gradle's reproducible archives use; the zip format cannot store anything earlier. It is
	 * stored as a local time, so that the bytes do not depend on the machine's time zone.
	 */
	private static final LocalDateTime FIXED_TIME = LocalDateTime.of(1980, 2, 1, 0, 0);

	private Jars() {
	}

	/**
	 * Writes the files under {@code folder} whose path relative to it starts with {@code prefix} into {@code jar}, at
	 * that path; with an empty prefix, every file. One folder of classes, compiled together, can so make several jars.
	 */
	static void write(Path folder, Path jar, String prefix) throws IOException {
		Files.createDirectories(jar.toAbsolutePath().getParent());
		List<Path> files;
		try (Stream<Path> walk = Files.walk(folder)) {
			files = walk.filter(Files::isRegularFile)
					.filter(file -> entryName(folder, file).startsWith(prefix))
					.sorted((first, second) -> entryName(folder, first).compareTo(entryName(folder, second)))
					.toList();
		}
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out)) {
			JarEntry manifestEntry = new JarEntry("META-INF/MANIFEST.MF");
			manifestEntry.setTimeLocal(FIXED_TIME);
			jarOut.putNextEntry(manifestEntry);
			manifest.write(jarOut);
			jarOut.closeEntry();
			for (Path file : files) {
				JarEntry entry = new JarEntry(entryName(folder, file));
				entry.setTimeLocal(FIXED_TIME);
				jarOut.putNextEntry(entry);
				Files.copy(file, jarOut);
				jarOut.closeEntry();
			}
		}
	}

	private static String entryName(Path folder, Path file) {
		return folder.relativize(file).toString().replace('\\', '/');
	}
}
