package org.adelheid.oneiro.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Client errors explained in Java terms. The console text is what the client printed for a plugin that raises errors
 * on purpose, copied as it was (client 951-1, 2026-10-24); the Java files stand in for that plugin's, with its code at
 * its lines.
 */
class ConsoleErrorsTest {

	private static final String PLUGIN = "Oneiro Error Probe";
	private static final String FOLDER = "src/main/java/org/adelheid/oneiro/errorprobe/";

	@TempDir
	Path project;

	private LuaLineMap lines;

	@BeforeEach
	void plugin() throws IOException {
		javaFile("inner/Deep.java", Map.of(11, "\t\tthrow new IllegalStateException(message);"));
		javaFile("Failures.java", Map.of(
				20, "\t\tDeep.fail(\"error-probe: thrown from Java \" + where);",
				27, "\t\treturn missing.length();",
				33, "\t\t\tthrown(\"and caught\");",
				36, "\t\t\tthrow e;"));
		javaFile("ErrorProbe.java", Map.of(
				29, "\t\t\tFailures.thrown(\"while the plugin loads\");",
				39, "\t\t\tFailures.thrown(\"in a ServerLogic callback\");",
				49, "\t\t\tFailures.thrown(\"in a button's callback\");",
				53, "\t\t\tFailures.lengthOfNil();",
				57, "\t\t\tFailures.rethrown();",
				76, "\t\tbutton.Subscribe(ui.Hook.ONBUTTONPRESSED, (_, _, _) -> action.run());"));
		String java = "org/adelheid/oneiro/errorprobe/";
		Map<String, Map<Integer, LuaLineMap.Source>> files = new TreeMap<>();
		files.put("src/inner/deep.lua", Map.of(9, new LuaLineMap.Source(java + "inner/Deep.java", 11)));
		files.put("src/failures.lua", Map.of(
				12, new LuaLineMap.Source(java + "Failures.java", 20),
				20, new LuaLineMap.Source(java + "Failures.java", 27),
				26, new LuaLineMap.Source(java + "Failures.java", 33),
				30, new LuaLineMap.Source(java + "Failures.java", 36)));
		files.put("main.lua", Map.of(
				19, new LuaLineMap.Source(java + "ErrorProbe.java", 39),
				29, new LuaLineMap.Source(java + "ErrorProbe.java", 49),
				33, new LuaLineMap.Source(java + "ErrorProbe.java", 53),
				37, new LuaLineMap.Source(java + "ErrorProbe.java", 57),
				57, new LuaLineMap.Source(java + "ErrorProbe.java", 76),
				65, new LuaLineMap.Source(java + "ErrorProbe.java", 29)));
		lines = new LuaLineMap(files);
	}

	@Test
	void eachErrorOfThePluginBecomesItsJavaLines() throws IOException {
		ConsoleErrors.Explanation explanation = explain(console());
		String deep = FOLDER + "inner/Deep.java:11: ";
		String failures = FOLDER + "Failures.java:";
		String probe = FOLDER + "ErrorProbe.java:";
		String subscribe =
				"\tat " + probe + "76: button.Subscribe(ui.Hook.ONBUTTONPRESSED, (_, _, _) -> action.run());\n";
		assertEquals(String.join("",
				deep + "error-probe: thrown from Java while the plugin loads\n",
				"\tat " + deep + "throw new IllegalStateException(message);\n",
				"\tat " + failures + "20: Deep.fail(\"error-probe: thrown from Java \" + where);\n",
				"\tat " + probe + "29: Failures.thrown(\"while the plugin loads\");\n",
				"\n",
				"Event ServerLogic callback error\n",
				"\n",
				deep + "error-probe: thrown from Java in a ServerLogic callback\n",
				"\tat " + deep + "throw new IllegalStateException(message);\n",
				"\tat " + failures + "20: Deep.fail(\"error-probe: thrown from Java \" + where);\n",
				"\tat " + probe + "39: Failures.thrown(\"in a ServerLogic callback\");\n",
				"\n",
				deep + "error-probe: thrown from Java and caught\n",
				"\tat " + failures + "36: throw e;\n",
				"\tat " + probe + "57: Failures.rethrown();\n",
				subscribe,
				"\n",
				failures + "27: attempt to get length of a nil value (local 'missing')\n",
				"\tat " + failures + "27: return missing.length();\n",
				"\tat " + probe + "53: Failures.lengthOfNil();\n",
				subscribe,
				"\n",
				deep + "error-probe: thrown from Java in a button's callback\n",
				"\tat " + deep + "throw new IllegalStateException(message);\n",
				"\tat " + failures + "20: Deep.fail(\"error-probe: thrown from Java \" + where);\n",
				"\tat " + probe + "49: Failures.thrown(\"in a button's callback\");\n",
				subscribe,
				"\n"), explanation.text());
		// The client's line after an error in a callback is shown, but is not an error of its own
		assertEquals(5, explanation.errors());
		assertEquals(0, explanation.otherPlugins());
	}

	@Test
	void errorsOfOtherPluginsAreLeftOut() {
		// Every plugin has a main.lua, so another plugin's error must not be read as this one's
		ConsoleErrors.Explanation explanation = explain("""
				[ERROR] in Coin Watch(-1): [string "-- main.lua..."]:19: attempt to index a nil value
				stack traceback:
				\t[string "-- main.lua..."]:19: in main chunk
				""");
		assertEquals("", explanation.text());
		assertEquals(0, explanation.errors());
		assertEquals(1, explanation.otherPlugins());
	}

	@Test
	void filesTheLineMapsDoNotKnowKeepTheirLuaPlaces() {
		Map<String, Map<Integer, LuaLineMap.Source>> files = new TreeMap<>();
		files.put("src/notifications/opening_table_entries.lua",
				Map.of(4, new LuaLineMap.Source("org/adelheid/oneiro/errorprobe/inner/Deep.java", 11)));
		lines = new LuaLineMap(files);
		// A path longer than the 42 characters the console shows stands for the one file that starts with it; a file
		// the maps do not know keeps its Lua place, by its path when its first line is one
		ConsoleErrors.Explanation explanation = explain("""
				[ERROR] in Oneiro Error Probe(-1): [string "-- src/notifications/opening_table_entri..."]:4: boom
				stack traceback:
				\t[string "-- src/hand_written.lua..."]:7: in field 'call'
				\t[string "-- The innermost call, in a folder of its own..."]:8: in main chunk
				""");
		assertEquals(FOLDER + """
				inner/Deep.java:11: boom
				\tat src/hand_written.lua:7
				\tat [string "-- The innermost call, in a folder of its own..."]:8

				""", explanation.text());
	}

	/**
	 * A place in a file of the plugin's own Lua, which starts with its path as Oneiro's files do, is that line of the
	 * file, with its code, as a place in Oneiro's Lua is the Java line it came from.
	 */
	@Test
	void aLineOfThePluginsOwnLuaIsShownWithItsCode() throws IOException {
		Path levels = project.resolve("src/main/lua/core/levels.lua");
		Files.createDirectories(levels.getParent());
		Files.writeString(levels, """
				-- src/core/levels.lua

				local Levels = {}

				function Levels.fromPenalty(penalty)
				    return penalty // nil
				end
				""", StandardCharsets.UTF_8);
		ConsoleErrors.Explanation explanation = explain("""
				[ERROR] in Oneiro Error Probe(-1): [string "-- src/core/levels.lua..."]:6: attempt to divide by nil
				stack traceback:
				\t[string "-- src/core/levels.lua..."]:6: in function 'fromPenalty'
				\t[string "-- src/failures.lua..."]:20: in function
				""");
		assertEquals("""
				src/main/lua/core/levels.lua:6: attempt to divide by nil
				\tat src/main/lua/core/levels.lua:6: return penalty // nil
				\tat %sFailures.java:27: return missing.length();

				""".formatted(FOLDER), explanation.text());
	}

	/**
	 * A place in one of the helpers Oneiro writes, in a file of its own under {@code utils/}, has no source line, so it
	 * keeps its Lua place, named as the helper; the call after it is the plugin's.
	 */
	@Test
	void aLineOfAHelperIsNamedAsOneirosHelper() throws IOException {
		ConsoleErrors.Explanation explanation = explain("""
				[ERROR] in Oneiro Error Probe(-1): [string "-- utils/ordered_table.lua..."]:12: attempt to index nil
				stack traceback:
				\t[string "-- utils/ordered_table.lua..."]:12: in function 'orderedTable'
				\t[string "-- src/failures.lua..."]:20: in function
				""");
		assertEquals("""
				utils/ordered_table.lua:12 (orderedTable, a helper Oneiro writes): attempt to index nil
				\tat utils/ordered_table.lua:12 (orderedTable, a helper Oneiro writes)
				\tat %sFailures.java:27: return missing.length();

				""".formatted(FOLDER), explanation.text());
	}

	@Test
	void aMessageOfTheClientsOwnIsExplained() throws IOException {
		// Rich Death's console as it was, newest line first, with its main.lua for the file; the Java line stands in
		// for the one that assigned a ui.Model's rotation
		javaFile("Hat.java", Map.of(12, "\t\tmodel.rotation = new Vector3(0.5f, 0.2f, 0f);"));
		lines = new LuaLineMap(Map.of("main.lua",
				Map.of(80, new LuaLineMap.Source("org/adelheid/oneiro/errorprobe/Hat.java", 12))));
		String message = "stack index 3, expected userdata, received sol.jag::math::Vector3T: value at this index does"
				+ " not properly reflect the desired type (bad argument into 'void(const jag::math::Vector3T&)')";
		ConsoleErrors.Explanation explanation = explain("""
				[ERROR] in Oneiro Error Probe(-1): Event Draw callback error

				[ERROR] in Oneiro Error Probe(-1): %s
				stack traceback:
				\t[C]: in metamethod 'newindex'
				\t[string "-- main.lua..."]:80: in function
				""".formatted(message));
		assertEquals("Event Draw callback error\n\n" + message + "\n"
				+ "\tat " + FOLDER + "Hat.java:12: model.rotation = new Vector3(0.5f, 0.2f, 0f);\n"
				+ "\tThe client does not take this value for the field: it wants a value of another C++ type, although"
				+ " the console can show the two by the same name, as it leaves out the part of a name between < and"
				+ " >. Oneiro's bindings follow the docs, which give the field this type. The field cannot be set this"
				+ " way; where its type has a method that sets the same thing, use that.\n\n", explanation.text());
		assertEquals(1, explanation.errors());
	}

	private ConsoleErrors.Explanation explain(String console) {
		return ConsoleErrors.explain(console, PLUGIN, lines, List.of(project.resolve("src/main/java")),
				Map.of("src", project.resolve("src/main/lua"), "dev", project.resolve("src/dev/lua")), project);
	}

	private void javaFile(String path, Map<Integer, String> code) throws IOException {
		List<String> text = new ArrayList<>();
		int last = code.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
		for (int line = 1; line <= last; line++) {
			text.add(code.getOrDefault(line, ""));
		}
		Path file = project.resolve(FOLDER + path);
		Files.createDirectories(file.getParent());
		Files.write(file, text, StandardCharsets.UTF_8);
	}

	private static String console() throws IOException {
		InputStream resource = ConsoleErrorsTest.class.getResourceAsStream("console-951-1.txt");
		try (InputStream in = Objects.requireNonNull(resource)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
