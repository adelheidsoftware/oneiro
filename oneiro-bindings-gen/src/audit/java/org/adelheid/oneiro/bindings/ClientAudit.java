package org.adelheid.oneiro.bindings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares the members the client's objects really have with the bindings, for Oneiro's maintainers after a client
 * update. A development plugin, the API probe, lists the keys of each object's metatable, where the client keeps every
 * member of the object's type, its properties included, and logs one line for each type it reaches:
 *
 * <pre>{@code members    NPCEntity                          => aabb, config, configID, coordGrid}</pre>
 *
 * <p>The audit reads those lines from a log, pasted from the client's console or printed from the plugin's data, and
 * says for each type which of its members the bindings lack and which of the bindings' members the client lacks, so
 * that each difference can end as an overrides line or a note. sol's own keys, the metamethods, {@code new},
 * {@code class_cast} and {@code class_check}, are left out, and so are the members the overrides skip. A member a type
 * inherits counts as there when the type's line or its superclass's line lists it; when the probe listed neither, it
 * is unchecked.
 */
final class ClientAudit {

	/** A line of the log that lists a type's members: the type, then what the probe logged for it. */
	private static final Pattern MEMBERS = Pattern.compile("(?:^|\\s)members\\s+(\\S+)\\s+=>\\s*(.*)$");

	/** What the probe logs for a type it listed: member names, separated by commas, or none. */
	private static final Pattern NAMES = Pattern.compile("|[A-Za-z_]\\w*(, [A-Za-z_]\\w*)*");

	/** The keys sol adds to the metatable of every type, which are no members of it. */
	private static final Set<String> SOL_KEYS = Set.of("new", "class_cast", "class_check");

	private final ApiModel model;
	private final Overrides overrides;

	private ClientAudit(ApiModel model, Overrides overrides) {
		this.model = model;
		this.overrides = overrides;
	}

	/**
	 * Prints the audit of a log against the bindings of the stubs that the system property {@code oneiro.stubs}
	 * names: {@code ClientAudit <log>}.
	 */
	public static void main(String[] arguments) throws IOException {
		String stubs = System.getProperty("oneiro.stubs", "");
		if (arguments.length != 1 || stubs.isEmpty()) {
			System.err.println("Give the log of the API probe's members lines:"
					+ " ./gradlew :oneiro-bindings-gen:auditClient --args=<log>, a path from Oneiro's folder.");
			System.exit(2);
		}
		List<String> log = Files.readAllLines(Path.of(arguments[0]), StandardCharsets.UTF_8);
		report(Path.of(stubs), log).forEach(System.out::println);
	}

	/** The audit of the log's members lines against the bindings of the stubs at the root, one line per type. */
	static List<String> report(Path stubsRoot, List<String> log) throws IOException {
		Overrides overrides = Overrides.bundled();
		// The bindings apply the overrides' corrections to the model as they are made: renames, added members
		Stubs stubs = Stubs.read(stubsRoot);
		JavaBindings bindings = new JavaBindings(stubs.model(), stubs.namespaces(), overrides, BindingsTarget.rs3(),
				BindingsGenerator.Language.JAVA, new ArrayList<>());
		return new ClientAudit(bindings.model, overrides).report(logged(log));
	}

	/**
	 * What the log says of each type, by its Lua name, in order. A type can have several lines, which add up: an event
	 * can pass a payload of a different C++ type for each kind of thing, as EntityDraw does for each kind of entity,
	 * each type holding the members of its kind.
	 */
	private static Map<String, List<String>> logged(List<String> log) {
		Map<String, List<String>> logged = new LinkedHashMap<>();
		for (String line : log) {
			Matcher matcher = MEMBERS.matcher(line.strip());
			if (matcher.find()) {
				logged.computeIfAbsent(matcher.group(1), _ -> new ArrayList<>()).add(matcher.group(2).strip());
			}
		}
		return logged;
	}

	/** One type's line of the report, and whether the client has the type as the bindings have it. */
	private record Compared(String line, boolean same) {
	}

	private List<String> report(Map<String, List<String>> logged) {
		// The members the client has, of each type the probe listed and the stubs have, all read first, as a type's
		// line looks at its superclasses' lines
		Map<String, Set<String>> listed = new LinkedHashMap<>();
		logged.forEach((type, texts) -> {
			if (!model.classes.containsKey(type)) {
				return;
			}
			for (String text : texts) {
				if (NAMES.matcher(text).matches()) {
					Set<String> members = listed.computeIfAbsent(type, _ -> new HashSet<>());
					for (String name : text.isEmpty() ? List.<String>of() : Arrays.asList(text.split(", "))) {
						if (!name.startsWith("__") && !SOL_KEYS.contains(name)) {
							members.add(name);
						}
					}
				}
			}
		});
		List<String> report = new ArrayList<>();
		int same = 0;
		for (Map.Entry<String, List<String>> entry : logged.entrySet()) {
			String type = entry.getKey();
			Set<String> members = listed.get(type);
			if (members != null) {
				Compared compared = compare(type, members, listed);
				same += compared.same() ? 1 : 0;
				report.add(compared.line());
			} else if (!model.classes.containsKey(type)) {
				report.add(type + ": no class of the stubs has this name.");
			} else {
				report.add(type + ": not listed: " + entry.getValue().getLast());
			}
		}
		report.add("Types listed: " + logged.size() + "; as the bindings have them: " + same + "; differing: "
				+ (listed.size() - same) + "; not listed: " + (logged.size() - listed.size()) + ".");
		return report;
	}

	/** One type's line: what the bindings lack, what the client lacks, and what the probe could not check. */
	private Compared compare(String type, Set<String> client, Map<String, Set<String>> listed) {
		Map<String, String> bound = boundMembers(type);
		Set<String> bindingsLack = new TreeSet<>();
		for (String member : client) {
			if (!bound.containsKey(member) && !isSkipped(type, member)) {
				bindingsLack.add(member);
			}
		}
		Set<String> clientLacks = new TreeSet<>();
		// The inherited members the probe could not check, by the class that declares them
		Map<String, Integer> unchecked = new TreeMap<>();
		bound.forEach((member, owner) -> {
			Set<String> ownerListed = listed.get(owner);
			if (client.contains(member) || (ownerListed != null && ownerListed.contains(member))) {
				return;
			}
			if (owner.equals(type) || ownerListed != null) {
				clientLacks.add(member);
			} else {
				unchecked.merge(owner, 1, Integer::sum);
			}
		});
		List<String> parts = new ArrayList<>();
		if (!bindingsLack.isEmpty()) {
			parts.add("the bindings lack " + String.join(", ", bindingsLack));
		}
		if (!clientLacks.isEmpty()) {
			parts.add("the client lacks " + String.join(", ", clientLacks));
		}
		boolean same = parts.isEmpty();
		if (same) {
			parts.add("as the bindings have it");
		}
		unchecked.forEach((owner, count) -> parts.add(count + " members inherited from " + owner
				+ " are unchecked, as the probe listed no " + owner));
		return new Compared(type + ": " + String.join("; ", parts) + ".", same);
	}

	/**
	 * The members the bindings give a type, its own and those it inherits, each with the class that declares it: the
	 * stubs' fields and functions as the overrides correct them, without those they skip.
	 */
	private Map<String, String> boundMembers(String type) {
		Map<String, String> members = new LinkedHashMap<>();
		Set<String> visited = new HashSet<>();
		String current = type;
		while (current != null && visited.add(current)) {
			ApiModel.ClassDef classDef = model.classes.get(current);
			if (classDef == null) {
				break;
			}
			List<String> names = new ArrayList<>();
			classDef.fields.forEach(field -> names.add(field.name()));
			classDef.functions.forEach(function -> names.add(function.name()));
			for (String name : names) {
				boolean member = !name.startsWith("[") && !name.startsWith("__") && !SOL_KEYS.contains(name);
				if (member && !isSkipped(current, name)) {
					members.putIfAbsent(name, current);
				}
			}
			current = classDef.superclass == null ? null : model.resolveAlias(classDef.superclass);
		}
		return members;
	}

	/** Whether the overrides skip the member, on the type or a class it inherits it from. */
	private boolean isSkipped(String type, String member) {
		Set<String> visited = new HashSet<>();
		String current = type;
		while (current != null && visited.add(current)) {
			if (overrides.has("skip", current + "." + member)) {
				return true;
			}
			ApiModel.ClassDef classDef = model.classes.get(current);
			current = classDef == null || classDef.superclass == null ? null : model.resolveAlias(classDef.superclass);
		}
		return false;
	}
}
