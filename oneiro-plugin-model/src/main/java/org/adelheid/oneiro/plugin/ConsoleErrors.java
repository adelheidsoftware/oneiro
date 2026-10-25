package org.adelheid.oneiro.plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Errors copied from the client's console, explained in the plugin's Java terms. The client names a plugin's file by
 * its first line, which Oneiro makes the file's path, so a place in the Lua reads
 * {@code [string "-- src/core/row.lua..."]:12}. Each error of the plugin is shown with its message and the calls that
 * led to it, innermost first, each as the Java file and line it came from with that line's code, in the
 * {@code file:line: text} form that IDEs link, and, for a message of the client's own, what it means. Errors of other
 * plugins, whose files can share a path with this plugin's, are left out.
 */
public final class ConsoleErrors {

	/**
	 * The explained errors.
	 *
	 * @param text each error of the plugin, in the order the console listed them, with a blank line after each
	 * @param errors how many errors of the plugin the text holds
	 * @param otherPlugins how many errors of other plugins were left out
	 */
	public record Explanation(String text, int errors, int otherPlugins) {
	}

	// The line an error starts with: the plugin's name, a line number the client gives errors as -1, and Lua's message
	private static final Pattern ERROR = Pattern.compile("\\[ERROR] in (.+?)\\((-?\\d+)\\): (.*)");
	// A place in the Lua, as Lua names a file it was given without a name: [string "first line..."]:line
	private static final Pattern PLACE = Pattern.compile("\\[string \"(.*?)\"]:(\\d+)");
	// A first line that names its file, as Oneiro writes it: -- src/core/row.lua
	private static final Pattern PATH = Pattern.compile("-- [\\w./-]+");
	private static final String TRACEBACK = "stack traceback:";

	private final String pluginName;
	private final LuaLineMap lines;
	private final List<Path> sourceFolders;
	private final Path projectFolder;
	// The lines of each Java file read so far, by its path relative to its source folder
	private final Map<String, @Nullable SourceFile> sources = new HashMap<>();

	private record SourceFile(String shownPath, List<String> lines) {
	}

	private ConsoleErrors(String pluginName, LuaLineMap lines, List<Path> sourceFolders, Path projectFolder) {
		this.pluginName = pluginName;
		this.lines = lines;
		this.sourceFolders = List.copyOf(sourceFolders);
		this.projectFolder = projectFolder.toAbsolutePath();
	}

	/**
	 * Explains the errors of a plugin in text copied from the console.
	 *
	 * @param console the console's text, as copied: errors, their tracebacks, and anything else, which is left out
	 * @param pluginName the plugin's name, which the console puts in front of each of its errors
	 * @param lines the line maps of the plugin's build
	 * @param sourceFolders the folders that hold the plugin's Java sources, where each line's code is read
	 * @param projectFolder the folder that the Java files' paths are shown relative to
	 */
	public static Explanation explain(String console, String pluginName, LuaLineMap lines, List<Path> sourceFolders,
			Path projectFolder) {
		return new ConsoleErrors(pluginName, lines, sourceFolders, projectFolder).explain(console);
	}

	private Explanation explain(String console) {
		List<String> input = List.of(console.replace("\r", "").split("\n", -1));
		StringBuilder text = new StringBuilder();
		int errors = 0;
		int otherPlugins = 0;
		for (int index = 0; index < input.size(); index++) {
			Matcher error = ERROR.matcher(input.get(index).strip());
			if (!error.matches()) {
				continue;
			}
			// The traceback that follows: one call per line, each indented in the console
			List<String> calls = new ArrayList<>();
			int next = index + 1;
			if (next < input.size() && input.get(next).strip().equals(TRACEBACK)) {
				next++;
				while (next < input.size() && isCall(input.get(next))) {
					calls.add(input.get(next).strip());
					next++;
				}
			}
			index = next - 1;
			if (!error.group(1).equals(pluginName)) {
				otherPlugins++;
				continue;
			}
			// A line without a place in the Lua, such as the one that follows an error in an event's callback, is shown
			// but is not an error of its own
			if (appendError(text, error.group(3), calls)) {
				errors++;
			}
		}
		return new Explanation(text.toString(), errors, otherPlugins);
	}

	private static boolean isCall(String line) {
		String call = line.strip();
		return !call.isEmpty() && (Character.isWhitespace(line.charAt(0)) || call.startsWith("[string \"")
				|| call.startsWith("[C]") || call.startsWith("(...tail calls...)"));
	}

	/**
	 * The message with its place in front, then the place of each call: Lua's own calls, such as error, and the
	 * client's have none. Last, what a message of the client's own means, when it is one {@link ClientMessages} knows.
	 *
	 * @return whether the error names a place in the Lua
	 */
	private boolean appendError(StringBuilder text, String message, List<String> calls) {
		Matcher place = PLACE.matcher(message);
		boolean placed = place.lookingAt();
		String rest = message;
		if (placed) {
			rest = message.substring(place.end()).replaceFirst("^: ", "");
			text.append(shown(place, false)).append(": ").append(rest).append('\n');
		} else {
			text.append(message).append('\n');
		}
		for (String call : calls) {
			Matcher callPlace = PLACE.matcher(call);
			if (callPlace.lookingAt()) {
				placed = true;
				text.append("\tat ").append(shown(callPlace, true)).append('\n');
			}
		}
		String explanation = ClientMessages.explain(rest, calls);
		if (explanation != null) {
			text.append('\t').append(explanation).append('\n');
		}
		text.append('\n');
		return placed;
	}

	/**
	 * A place in the Lua as the Java line it came from, with that line's code when asked for; as the Lua file and line
	 * when the line maps do not know it, such as a line of hand-written Lua.
	 */
	private String shown(Matcher place, boolean withCode) {
		int luaLine = Integer.parseInt(place.group(2));
		String luaFile = luaFile(place.group(1));
		LuaLineMap.Source source = luaFile == null ? null : lines.sourceOf(luaFile, luaLine);
		if (source == null) {
			return (luaFile != null ? luaFile : consoleName(place.group(1))) + ":" + luaLine;
		}
		SourceFile file = sources.computeIfAbsent(source.path(), this::find);
		if (file == null) {
			return source.path() + ":" + source.line();
		}
		String shown = file.shownPath() + ":" + source.line();
		if (withCode && source.line() <= file.lines().size()) {
			shown += ": " + file.lines().get(source.line() - 1).strip();
		}
		return shown;
	}

	/**
	 * The plugin's Lua file a console name stands for. The name is the file's first line, cut to 45 characters, which
	 * for a file Oneiro wrote is its path; a cut path stands for the one file whose path starts with it.
	 */
	private @Nullable String luaFile(String consoleName) {
		String name = uncut(consoleName);
		if (!PATH.matcher(name).matches()) {
			return null;
		}
		String path = name.substring(3);
		if (lines.luaFiles().contains(path)) {
			return path;
		}
		List<String> candidates = lines.luaFiles().stream().filter(file -> file.startsWith(path)).toList();
		return candidates.size() == 1 ? candidates.get(0) : null;
	}

	/** A file the line maps do not know: its path when its first line is one, or else what the console names it. */
	private static String consoleName(String consoleName) {
		String name = uncut(consoleName);
		return PATH.matcher(name).matches() ? name.substring(3) : "[string \"" + consoleName + "\"]";
	}

	/** The first line as the console shows it, without the ... that Lua adds when more lines follow or it was cut. */
	private static String uncut(String consoleName) {
		return consoleName.endsWith("...") ? consoleName.substring(0, consoleName.length() - 3) : consoleName;
	}

	private @Nullable SourceFile find(String path) {
		for (Path folder : sourceFolders) {
			Path file = folder.resolve(path);
			if (Files.isRegularFile(file)) {
				Path absolute = file.toAbsolutePath();
				String shownPath = absolute.startsWith(projectFolder)
						? projectFolder.relativize(absolute).toString().replace('\\', '/')
						: absolute.toString();
				try {
					return new SourceFile(shownPath, Files.readAllLines(file, StandardCharsets.UTF_8));
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
		}
		return null;
	}
}
