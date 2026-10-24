package org.adelheid.oneiro.bindings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The facts about the RS3 API that the stubs do not state, read from {@code overrides.txt}: which global tables are
 * singletons, which values can be nil, which fields are read-only, the Java names for Lua names Java cannot use, what
 * needs a permission, and so on. Each line is {@code <fact> <target> [<argument> ...]}, followed by {@code #} and how
 * the fact was checked. The generator asks for facts by kind and target; facts it never asked about are reported, as
 * they no longer match anything in the stubs.
 */
final class Overrides {

	/** One line of the file. */
	record Entry(String fact, String target, List<String> arguments, int line) {

		Entry {
			arguments = List.copyOf(arguments);
		}
	}

	/** The facts the file may state, so that a misspelt one fails loudly instead of being ignored. */
	private static final Set<String> FACTS = Set.of("singleton", "instance", "constructible", "not-constructible",
			"nullable", "not-nullable", "read-only", "writable", "copied", "not-copied", "java-name", "rename",
			"values", "type", "field", "function", "param", "superclass", "hook", "operator", "returns", "permission",
			"manifest-flag", "review", "dev-only", "skip", "element-table", "no-from-id");

	private final List<Entry> entries;
	private final Set<Entry> used = new HashSet<>();
	/** Entries applied somewhere the stubs already said the same. */
	private final Set<Entry> agreed = new HashSet<>();
	/** Entries applied somewhere the stubs said otherwise: there, the entry is still needed. */
	private final Set<Entry> corrected = new HashSet<>();

	private Overrides(List<Entry> entries) {
		this.entries = List.copyOf(entries);
	}

	/** The overrides that ship with Oneiro. */
	static Overrides bundled() {
		try (InputStream in = Objects.requireNonNull(Overrides.class.getResourceAsStream("overrides.txt"),
				"overrides.txt")) {
			return parse(in);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static Overrides parse(InputStream in) throws IOException {
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
				if (words.size() < 2 || !FACTS.contains(words.getFirst())) {
					throw new IOException("overrides.txt:" + number + ": expected <fact> <target>, with a fact from "
							+ FACTS.stream().sorted().toList() + ": " + line);
				}
				entries.add(new Entry(words.get(0), words.get(1), words.subList(2, words.size()), number));
			}
		}
		return new Overrides(entries);
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
