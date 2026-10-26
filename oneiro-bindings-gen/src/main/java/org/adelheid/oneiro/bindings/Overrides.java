package org.adelheid.oneiro.bindings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The facts about an API that its LuaCATS types do not state: for the RS3 API, the bundled {@code overrides.txt}, and
 * for a library, a file the plugin keeps for it. They say which global tables are singletons, which values can be nil,
 * which fields are read-only, the Java names for Lua names Java cannot use, and so on, and for the RS3 API alone also
 * what needs a permission, what moderation reviews, and the hooks' callbacks. Each line is
 * {@code <fact> <target> [<argument> ...]}, followed by {@code #} and how the fact was checked. The generator asks for
 * facts by kind and target; facts it never asked about are reported, as they no longer match anything in the types.
 */
final class Overrides {

	/** One line of the file. */
	record Entry(String fact, String target, List<String> arguments, int line) {

		Entry {
			arguments = List.copyOf(arguments);
		}
	}

	/** The facts a library's file may state, those that hold for any Lua, so that a misspelt one fails loudly. */
	private static final Set<String> LIBRARY_FACTS = Set.of("singleton", "instance", "constructible",
			"not-constructible", "nullable", "not-nullable", "read-only", "writable", "java-name", "rename", "values",
			"type", "field", "function", "param", "superclass", "operator", "returns", "skip", "note");
	/** The facts the RS3 API's file may state: those, and the facts of the client alone. */
	private static final Set<String> RS3_FACTS = union(LIBRARY_FACTS, Set.of("copied", "not-copied", "hook",
			"permission", "manifest-flag", "review", "dev-only"));

	private final List<Entry> entries;
	private final String source;
	private final Set<Entry> used = new HashSet<>();
	/** Entries applied somewhere the stubs already said the same. */
	private final Set<Entry> agreed = new HashSet<>();
	/** Entries applied somewhere the stubs said otherwise: there, the entry is still needed. */
	private final Set<Entry> corrected = new HashSet<>();

	private Overrides(List<Entry> entries, String source) {
		this.entries = List.copyOf(entries);
		this.source = source;
	}

	/** The overrides of the RS3 API, which ship with Oneiro. */
	static Overrides bundled() {
		try (InputStream in = Objects.requireNonNull(Overrides.class.getResourceAsStream("overrides.txt"),
				"overrides.txt")) {
			return parse(in, "overrides.txt", RS3_FACTS);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** A plugin's overrides of a library, which state only the facts that hold for any Lua. */
	static Overrides library(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			return parse(in, file.getFileName().toString(), LIBRARY_FACTS);
		}
	}

	/** No overrides, for a library the plugin keeps none for. */
	static Overrides none() {
		return new Overrides(List.of(), "");
	}

	/** The overrides of the RS3 API from another source, for tests. */
	static Overrides parse(InputStream in) throws IOException {
		return parse(in, "overrides.txt", RS3_FACTS);
	}

	private static Overrides parse(InputStream in, String source, Set<String> facts) throws IOException {
		List<Entry> entries = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			int number = 0;
			while ((line = reader.readLine()) != null) {
				number++;
				int hash = line.indexOf('#');
				String content = (hash < 0 ? line : line.substring(0, hash)).strip();
				if (content.isEmpty()) {
					continue;
				}
				List<String> words = Arrays.asList(content.split("\\s+"));
				if (words.size() < 2 || !facts.contains(words.getFirst())) {
					throw new IOException(source + ":" + number + ": expected <fact> <target>, with a fact from "
							+ facts.stream().sorted().toList() + ": " + line);
				}
				if (words.getFirst().equals("note") && words.size() < 3) {
					throw new IOException(source + ":" + number + ": a note needs its text: " + line);
				}
				entries.add(new Entry(words.get(0), words.get(1), words.subList(2, words.size()), number));
			}
		}
		return new Overrides(entries, source);
	}

	private static Set<String> union(Set<String> first, Set<String> second) {
		Set<String> all = new HashSet<>(first);
		all.addAll(second);
		return Set.copyOf(all);
	}

	/** The file's name, which its messages start with. */
	String source() {
		return source;
	}

	/** Whether the file states the fact about the target. */
	boolean has(String fact, String target) {
		return entry(fact, target) != null;
	}

	/** The fact's first argument for the target, or null when the file does not state it. */
	@Nullable String value(String fact, String target) {
		Entry entry = entry(fact, target);
		return entry == null || entry.arguments().isEmpty() ? null : entry.arguments().getFirst();
	}

	/** The fact's arguments for the target, or null when the file does not state it. */
	@Nullable List<String> arguments(String fact, String target) {
		Entry entry = entry(fact, target);
		return entry == null ? null : entry.arguments();
	}

	/**
	 * Whether the file states the fact about the target, as {@link #has} does, recording whether the stubs already say
	 * the same there. A correction the stubs catch up with is then reported as no longer needed, so that a fix upstream
	 * needs no change to this file.
	 */
	boolean corrects(String fact, String target, boolean stubsAgree) {
		Entry entry = entry(fact, target);
		if (entry != null) {
			applied(entry, stubsAgree);
		}
		return entry != null;
	}

	/** Records that an entry was applied, and whether the stubs already said the same there. */
	void applied(Entry entry, boolean stubsAgree) {
		(stubsAgree ? agreed : corrected).add(entry);
	}

	/** The text of each {@code note} line about the target, in the file's order. */
	List<String> notes(String target) {
		List<String> notes = new ArrayList<>();
		for (Entry entry : entries) {
			if (entry.fact().equals("note") && entry.target().equals(target)) {
				used.add(entry);
				notes.add(String.join(" ", entry.arguments()));
			}
		}
		return notes;
	}

	/** Every entry of one fact, each marked as used. */
	List<Entry> all(String fact) {
		List<Entry> found = entries.stream().filter(entry -> entry.fact().equals(fact)).toList();
		used.addAll(found);
		return found;
	}

	/**
	 * The entries the generator never asked about: they name something the stubs no longer have. A skip is not among
	 * them, because a member the stubs no longer have is not bound anyway; {@link #noLongerNeeded} lists it.
	 */
	List<Entry> unused() {
		return entries.stream().filter(entry -> !used.contains(entry) && !isSkip(entry)).toList();
	}

	/**
	 * The entries the stubs have caught up with: the stubs already say the same everywhere the entry applies, or, for a
	 * skip, no longer have the member. Each can be deleted, but nothing changes while it stays.
	 */
	List<Entry> noLongerNeeded() {
		return entries.stream()
				.filter(entry -> (agreed.contains(entry) && !corrected.contains(entry))
						|| (isSkip(entry) && !used.contains(entry)))
				.toList();
	}

	private static boolean isSkip(Entry entry) {
		return entry.fact().equals("skip");
	}

	private @Nullable Entry entry(String fact, String target) {
		for (Entry entry : entries) {
			if (entry.fact().equals(fact) && entry.target().equals(target)) {
				used.add(entry);
				return entry;
			}
		}
		return null;
	}
}
