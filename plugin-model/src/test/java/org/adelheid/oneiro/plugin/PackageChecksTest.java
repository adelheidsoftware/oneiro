package org.adelheid.oneiro.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The checks of the folder that ships. */
class PackageChecksTest {

	@TempDir
	Path folder;

	private void write(String path, String text) throws IOException {
		Path file = folder.resolve(path);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
	}

	private void writePng(String path, int width, int height) throws IOException {
		ByteBuffer png = ByteBuffer.allocate(24);
		png.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'});
		png.putInt(width).putInt(height);
		Path file = folder.resolve(path);
		Files.createDirectories(file.getParent());
		Files.write(file, png.array());
	}

	@Test
	void aPluginAsItShipsPasses() throws IOException {
		write("main.lua", "local Tally = require(\"src/tally\")\n");
		write("plugin.json", "{}\n");
		write("src/tally.lua", "return {}\n");
		writePng("resources/icons/herb.png", 32, 32);
		Files.write(folder.resolve("resources/ding.ogg"), new byte[] {'O', 'g', 'g', 'S', 0});
		assertEquals(List.of(), PackageChecks.check(folder));
	}

	@Test
	void everyProblemIsNamed() throws IOException {
		write("main.lua", "local Missing = require(\"src/missing\")\nExecuteConsoleCommand(\"!tele 3200 3200\")\n");
		write("README.md", "notes\n");
		write("resources/notes.txt", "not an image\n");
		writePng("resources/huge.png", 2048, 16);
		assertEquals(List.of(
				"The plugin has no plugin.json, which the client needs at the top of the folder.",
				"README.md is not part of a plugin: only main.lua, plugin.json, Lua files under src/ and PNG or Ogg files"
						+ " under resources/ ship.",
				"resources/huge.png is 2048 by 16 pixels; the client loads images up to 1024 by 1024.",
				"resources/notes.txt is not a PNG image or an Ogg Vorbis sound, the only resources the client loads.",
				"main.lua requires \"src/missing\", which is not in the plugin.",
				"main.lua calls ExecuteConsoleCommand, which runs development cheats and must never ship."),
				PackageChecks.check(folder));
	}
}
