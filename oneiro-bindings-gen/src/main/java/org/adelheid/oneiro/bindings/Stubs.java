package org.adelheid.oneiro.bindings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A checkout of runescape-api-stubs, read: the declarations of {@code generated/**}{@code /*.lua} and
 * {@code types.lua}, and the name lists of {@code csvs/}. The stubs' {@code consts/} folder is generated from the csvs,
 * so it is not read.
 *
 * @param namespaces the csv name lists by file name, such as {@code Obj}
 */
record Stubs(ApiModel model, Map<String, CsvConstants.Namespace> namespaces) {

	static Stubs read(Path root) throws IOException {
		if (!Files.isRegularFile(root.resolve("types.lua")) || !Files.isDirectory(root.resolve("generated"))) {
			throw new IOException(root + " is not a checkout of runescape-api-stubs: it has no types.lua and"
					+ " generated/ folder.");
		}
		ApiModel model = new ApiModel();
		LuaCatsParser parser = new LuaCatsParser(model);
		List<Path> files;
		try (Stream<Path> walk = Files.walk(root.resolve("generated"))) {
			files = new ArrayList<>(walk.filter(path -> path.toString().endsWith(".lua")).sorted().toList());
		}
		files.add(root.resolve("types.lua"));
		for (Path file : files) {
			parser.parseFile(file, root.relativize(file).toString().replace('\\', '/'));
		}
		parser.finish();

		Map<String, CsvConstants.Namespace> namespaces = new LinkedHashMap<>();
		Path csvs = root.resolve("csvs");
		if (Files.isDirectory(csvs)) {
			try (Stream<Path> list = Files.list(csvs)) {
				for (Path csv : list.filter(path -> path.toString().endsWith(".csv")).sorted().toList()) {
					CsvConstants.Namespace namespace = CsvConstants.read(csv);
					namespaces.put(namespace.name(), namespace);
				}
			}
		}
		return new Stubs(model, namespaces);
	}
}
