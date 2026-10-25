package org.adelheid.oneiro.bindings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The audit of the client's members, against the bindings of the stubs fixture in {@code src/test/stubs}. */
class ClientAuditTest {

	private static final Path STUBS = Path.of("src", "test", "stubs");

	/**
	 * Each type's line says what the bindings lack and what the client lacks, leaving out sol's own keys; a member a
	 * type inherits is there when its superclass's line lists it. A type that is no class of the stubs, or that the
	 * probe could not list, says so.
	 */
	@Test
	void eachTypeSaysWhatEachSideLacks() throws IOException {
		List<String> log = List.of(
				"[API Probe] members    ui.Component                       => SetPos, Subscribe, Unsubscribe, __index,"
						+ " class_cast, hidden, visible",
				"members    ui.Text                            => SetPos, Shadow, content, new",
				"members    ui.ComponentCollection             => GetChildren",
				"members    ui.Interfaces                      => ",
				"members    ui.Nothing                         => anything",
				"members    Player                             => error: attempt to index a nil value",
				"API probe: all cases run");
		assertEquals(List.of(
				"ui.Component: as the bindings have it.",
				"ui.Text: the bindings lack Shadow.",
				"ui.ComponentCollection: the bindings lack GetChildren.",
				"ui.Interfaces: the client lacks GetComponent.",
				"ui.Nothing: no class of the stubs has this name.",
				"Player: not listed: error: attempt to index a nil value",
				"Types listed: 6; as the bindings have them: 1; differing: 3; not listed: 2."),
				ClientAudit.report(STUBS, log));
	}

	/**
	 * The lines of one type add up, as an event's payload can be of a different C++ type for each kind of entity; a
	 * line that lists nothing, such as an error, changes nothing when another lists the type.
	 */
	@Test
	void linesOfOneTypeAddUp() throws IOException {
		List<String> log = List.of(
				"members    ui.Interfaces                      => error: no object of this type was found",
				"members    ui.Interfaces                      => ",
				"members    ui.Interfaces                      => GetComponent");
		assertEquals(List.of(
				"ui.Interfaces: as the bindings have it.",
				"Types listed: 1; as the bindings have them: 1; differing: 0; not listed: 0."),
				ClientAudit.report(STUBS, log));
	}

	/** A member inherited from a class the probe did not list is counted as unchecked, not as missing. */
	@Test
	void inheritedMembersOfAnUnlistedClassAreUnchecked() throws IOException {
		List<String> log = List.of("members    ui.Text                            => content");
		assertEquals(List.of(
				"ui.Text: as the bindings have it; 5 members inherited from ui.Component are unchecked, as the probe"
						+ " listed no ui.Component.",
				"Types listed: 1; as the bindings have them: 1; differing: 0; not listed: 0."),
				ClientAudit.report(STUBS, log));
	}
}
