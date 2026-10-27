package org.adelheid.oneiro.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The Java classes that make a plugin's resources and base URLs typed: {@code Resources}, one field per file under
 * {@code src/main/resources/} and a nested class per folder, so that {@code Resources.icons.herb} is checked when the
 * plugin compiles and becomes {@code Plugin.resources.icons.herb}; and {@code Urls}, one field per alias of the
 * manifest's {@code urls}, {@code Urls.prices} for {@code Plugin.urls.prices}. Both are annotated {@code @LuaGlobal},
 * so they produce no Lua of their own, and {@code @Generated} with {@link #GENERATOR}, which tells the compiler that
 * they may carry the annotations that no class of the plugin's own may.
 */
public final class TypedSources {

	/** The generator the classes name in {@code javax.annotation.processing.Generated}: this class. */
	public static final String GENERATOR = TypedSources.class.getName();

	private static final Set<String> JAVA_KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case",
			"catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends",
			"final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface",
			"long", "native", "new", "package", "private", "protected", "public", "return", "short", "static",
			"strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
			"volatile", "while", "true", "false", "null", "var", "record", "yield", "sealed", "permits", "_");
	private static final String LUA_ONLY = """

				private static <T> T lua() {
					throw new UnsupportedOperationException(
							"%s exist only in Lua; Oneiro compiles what uses them.");
				}
			""";

	private static final String GENERATED = "@Generated(\"" + GENERATOR + "\")\n";

	private TypedSources() {
	}

	/**
	 * The {@code Resources} class for the files under a resources folder, or null when it has none. Files that are not
	 * PNG images or Ogg Vorbis sounds, and two files that would share a name, are reported in {@code problems}.
	 */
	public static @Nullable String resources(String rootPackage, Path folder, List<String> problems)
			throws IOException {
		if (!Files.isDirectory(folder)) {
			return null;
		}
		StringBuilder members = new StringBuilder();
		boolean any = folderMembers(folder, folder, "Plugin.resources", "\t", members, problems);
		if (!any) {
			return null;
		}
		return header(rootPackage, "the plugin's resources folder")
				+ "import javax.annotation.processing.Generated;\n"
				+ "import org.adelheid.oneiro.api.LuaName;\n"
				+ "import org.adelheid.oneiro.api.binding.LuaGlobal;\n"
				+ "import org.adelheid.oneiro.rs3.PluginAudioResource;\n"
				+ "import org.adelheid.oneiro.rs3.PluginImageResource;\n\n"
				+ "/**\n * The plugin's resources, the files under {@code src/main/resources/} that the client loads.\n"
				+ " *\n * <p>Lua: {@code Plugin.resources}\n */\n"
				+ GENERATED
				+ "@LuaGlobal(\"Plugin.resources\")\n"
				+ "public final class Resources {\n\n"
				+ "\tprivate Resources() {\n\t}\n"
				+ members
				+ LUA_ONLY.formatted("Resources")
				+ "}\n";
	}

	/** One folder's files as fields and its folders as nested classes; whether it holds any resource. */
	private static boolean folderMembers(Path root, Path folder, String luaPath, String indent, StringBuilder out,
			List<String> problems) throws IOException {
		Map<String, Path> files = new TreeMap<>();
		Map<String, Path> folders = new TreeMap<>();
		try (Stream<Path> list = Files.list(folder)) {
			for (Path entry : list.sorted().toList()) {
				String fileName = entry.getFileName().toString();
				String relative = "resources/" + root.relativize(entry).toString().replace('\\', '/');
				if (Files.isDirectory(entry)) {
					// The folder's table path is dotted, as in Plugin.resources.icons, so its name cannot hold a dot
					if (fileName.contains(".")) {
						problems.add(relative + "/ has a dot in its name; rename the folder, since Lua reaches it as a"
								+ " table key, Plugin.resources.<folder>.");
						continue;
					}
					folders.put(fileName, entry);
					continue;
				}
				String lower = fileName.toLowerCase(Locale.ROOT);
				if (!lower.endsWith(".png") && !lower.endsWith(".ogg")) {
					problems.add(relative + " is not a PNG image or an Ogg Vorbis sound, the only resources the"
							+ " client loads.");
					continue;
				}
				String key = fileName.substring(0, fileName.lastIndexOf('.'));
				if (files.put(key, entry) != null || folders.containsKey(key)) {
					problems.add(relative + " has the name of another resource in its folder, " + key
							+ "; the client keys them by name without the extension.");
				}
			}
		}
		boolean any = false;
		for (Map.Entry<String, Path> file : files.entrySet()) {
			String key = file.getKey();
			String relative = "resources/" + root.relativize(file.getValue()).toString().replace('\\', '/');
			String type = file.getValue().toString().toLowerCase(Locale.ROOT).endsWith(".png")
					? "PluginImageResource"
					: "PluginAudioResource";
			out.append("\n").append(indent).append("/** {@code ").append(relative).append("}: {@code ")
					.append(lua(luaPath, key)).append("} */\n");
			appendName(out, indent, key);
			out.append(indent).append("public static final ").append(type).append(" ").append(javaName(key))
					.append(" = lua();\n");
			any = true;
		}
		for (Map.Entry<String, Path> nested : folders.entrySet()) {
			String key = nested.getKey();
			StringBuilder inner = new StringBuilder();
			String innerPath = luaPath + "." + key;
			if (!folderMembers(root, nested.getValue(), innerPath, indent + "\t", inner, problems)) {
				continue;
			}
			String relative = "resources/" + root.relativize(nested.getValue()).toString().replace('\\', '/') + "/";
			out.append("\n").append(indent).append("/** The files under {@code ").append(relative)
					.append("}: {@code ").append(lua(luaPath, key)).append("} */\n");
			out.append(indent).append("@LuaGlobal(\"").append(innerPath).append("\")\n");
			out.append(indent).append("public static final class ").append(javaName(key)).append(" {\n\n")
					.append(indent).append("\tprivate ").append(javaName(key)).append("() {\n")
					.append(indent).append("\t}\n")
					.append(inner)
					.append(indent).append("}\n");
			any = true;
		}
		return any;
	}

	/** The {@code Urls} class for the manifest's base URLs, or null when it declares none. */
	public static @Nullable String urls(String rootPackage, Map<String, String> urls) {
		if (urls.isEmpty()) {
			return null;
		}
		StringBuilder members = new StringBuilder();
		new TreeMap<>(urls).forEach((alias, url) -> {
			members.append("\n\t/** {@code ").append(url).append("}: {@code ").append(lua("Plugin.urls", alias))
					.append("} */\n");
			appendName(members, "\t", alias);
			members.append("\tpublic static final PluginURL ").append(javaName(alias)).append(" = lua();\n");
		});
		return header(rootPackage, "the manifest's urls")
				+ "import javax.annotation.processing.Generated;\n"
				+ "import org.adelheid.oneiro.api.LuaName;\n"
				+ "import org.adelheid.oneiro.api.binding.LuaGlobal;\n"
				+ "import org.adelheid.oneiro.rs3.PluginURL;\n\n"
				+ "/**\n * The base URLs the manifest declares, by their aliases; HTTP requests reach only these.\n"
				+ " *\n * <p>Lua: {@code Plugin.urls}\n */\n"
				+ GENERATED
				+ "@LuaGlobal(\"Plugin.urls\")\n"
				+ "public final class Urls {\n\n"
				+ "\tprivate Urls() {\n\t}\n"
				+ members
				+ LUA_ONLY.formatted("URLs")
				+ "}\n";
	}

	private static String header(String rootPackage, String from) {
		return "// Generated by Oneiro from " + from + "; it changes when they do.\npackage " + rootPackage + ";\n\n";
	}

	/** {@code @LuaName} for a key Java cannot spell, such as {@code herb-icon}. */
	private static void appendName(StringBuilder out, String indent, String key) {
		if (!javaName(key).equals(key)) {
			out.append(indent).append("@LuaName(\"").append(key.replace("\\", "\\\\").replace("\"", "\\\""))
					.append("\")\n");
		}
	}

	/** {@code Plugin.resources.icons}, or {@code Plugin.resources["my icons"]} for a key that is not a Lua name. */
	private static String lua(String path, String key) {
		return Manifest.isLuaName(key) ? path + "." + key : path + "[\"" + key + "\"]";
	}

	private static String javaName(String key) {
		String cleaned = key.replaceAll("[^A-Za-z0-9_]", "_");
		if (cleaned.isEmpty() || Character.isDigit(cleaned.charAt(0))) {
			cleaned = "_" + cleaned;
		}
		return JAVA_KEYWORDS.contains(cleaned) ? cleaned + "_" : cleaned;
	}
}
