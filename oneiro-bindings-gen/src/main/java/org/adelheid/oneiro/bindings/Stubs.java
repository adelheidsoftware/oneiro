package org.adelheid.oneiro.bindings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A checkout of rs3-api-types, read: the declarations of {@code generated/library/**}{@code /*.lua}, and the name lists
 * of {@code generated/constants/}, a CSV a namespace. The constants' definition files beside the CSVs, which name the
 * same IDs for LuaLS, are not read.
 *
 * @param namespaces the csv name lists by file name, such as {@code Obj}
 */
record Stubs(ApiModel model, Map<String, CsvConstants.Namespace> namespaces) {

	static Stubs read(Path root) throws IOException {
		return read(root, true);
	}

	/**
	 * The stubs' declarations alone, without the name lists, which the bindings of other Lua need only to resolve the
	 * types it names.
	 */
	static Stubs readTypes(Path root) throws IOException {
		return read(root, false);
	}

	private static Stubs read(Path root, boolean withNames) throws IOException {
		Path library = root.resolve("generated/library");
		if (!Files.isDirectory(library)) {
			throw new IOException(root + " is not a checkout of rs3-api-types: it has no generated/library/ folder.");
		}
		ApiModel model = new ApiModel();
		LuaCatsReader reader = new LuaCatsReader(model);
		List<Path> files;
		try (Stream<Path> walk = Files.walk(library)) {
			files = walk.filter(path -> path.toString().endsWith(".lua")).sorted().toList();
		}
		for (Path file : files) {
			String path = root.relativize(file).toString().replace('\\', '/');
			reader.read(file, path, path.substring(0, path.length() - ".lua".length()));
		}
		reader.finish();

		Map<String, CsvConstants.Namespace> namespaces = new LinkedHashMap<>();
		Path csvs = root.resolve("generated/constants");
		if (withNames && Files.isDirectory(csvs)) {
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
