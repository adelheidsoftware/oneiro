package org.adelheid.oneiro.bindings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The name lists in rs3-api-types' {@code generated/constants/} folder, one file per namespace: {@code NPC.csv}
 * backs {@code id.NPC} and {@code config.NPC}. Each row is {@code id,NAME} followed, for a variable, by its value type
 * and, for a bit variable (whose ID is negative), its base variable and bit mask. The LuaCATS constants beside them
 * are generated from these rows, so the rows are read directly.
 */
final class CsvConstants {

	/** One row: the numeric ID, the name as written, and any extra columns. */
	record Row(long id, String name, List<String> extra) {

		Row {
			extra = List.copyOf(extra);
		}
	}

	/** One csv file, with the lines that were not rows. */
	record Namespace(String name, List<Row> rows, List<String> malformed) {

		Namespace {
			rows = List.copyOf(rows);
			malformed = List.copyOf(malformed);
		}
	}

	private CsvConstants() {
	}

	static Namespace read(Path csv) throws IOException {
		String name = csv.getFileName().toString().replaceFirst("\\.csv$", "");
		List<Row> rows = new ArrayList<>();
		List<String> malformed = new ArrayList<>();
		for (String line : Files.readAllLines(csv, StandardCharsets.UTF_8)) {
			if (line.isBlank()) {
				continue;
			}
			List<String> cells = Arrays.asList(line.split(","));
			if (cells.size() < 2) {
				malformed.add(line);
				continue;
			}
			try {
				long id = Long.parseLong(cells.get(0).trim());
				rows.add(new Row(id, cells.get(1).trim(), cells.subList(2, cells.size())));
			} catch (NumberFormatException exception) {
				malformed.add(line);
			}
		}
		return new Namespace(name, rows, malformed);
	}
}
