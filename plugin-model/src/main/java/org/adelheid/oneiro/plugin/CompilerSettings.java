package org.adelheid.oneiro.plugin;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What Oneiro's javac plugin needs from the build besides the sources, written by the Gradle plugin to a properties
 * file whose URI is the plugin's argument, because javac splits plugin arguments at spaces and paths may hold them.
 *
 * @param rootPackage the plugin's root package, or empty to take the package of the class that extends
 *     {@code Plugin}
 * @param id the plugin's identifier, which names its event subscriptions
 * @param permissions the permissions the manifest requests
 * @param flags the manifest flags that are set, such as {@code hasSettingsIcon}
 * @param dev whether the sources are the {@code dev} source set
 * @param luaOutput the folder the Lua is written to, replacing what it held
 * @param warningsAsErrors whether a warning, such as a comment that cannot be placed, fails the build
 */
public record CompilerSettings(String rootPackage, String id, Set<String> permissions, Set<String> flags, boolean dev,
		Path luaOutput, boolean warningsAsErrors) {

	public CompilerSettings {
		permissions = Set.copyOf(permissions);
		flags = Set.copyOf(flags);
	}

	public void write(Path file) throws IOException {
		Properties properties = new Properties();
		properties.setProperty("rootPackage", rootPackage);
		properties.setProperty("id", id);
		properties.setProperty("permissions", String.join(",", new TreeSet<>(permissions)));
		properties.setProperty("flags", String.join(",", new TreeSet<>(flags)));
		properties.setProperty("dev", Boolean.toString(dev));
		properties.setProperty("luaOutput", luaOutput.toAbsolutePath().toString());
		properties.setProperty("warningsAsErrors", Boolean.toString(warningsAsErrors));
		Files.createDirectories(file.toAbsolutePath().getParent());
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			properties.store(writer, "Written by Oneiro's Gradle plugin for its javac plugin");
		}
	}

	public static CompilerSettings read(Path file) throws IOException {
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		return new CompilerSettings(properties.getProperty("rootPackage", ""), required(properties, "id", file),
				list(properties.getProperty("permissions", "")), list(properties.getProperty("flags", "")),
				Boolean.parseBoolean(properties.getProperty("dev", "false")),
				Path.of(required(properties, "luaOutput", file)),
				Boolean.parseBoolean(properties.getProperty("warningsAsErrors", "false")));
	}

	private static String required(Properties properties, String key, Path file) throws IOException {
		String value = properties.getProperty(key);
		if (value == null || value.isBlank()) {
			throw new IOException(file + " has no " + key + ", which Oneiro's compiler needs.");
		}
		return value;
	}

	private static Set<String> list(String commaSeparated) {
		return Stream.of(commaSeparated.split(",", -1)).map(String::strip).filter(value -> !value.isEmpty())
				.collect(Collectors.toSet());
	}
}
