package plugin.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.adelheid.oneiro.api.LuaName;
import org.jspecify.annotations.Nullable;

/**
 * The prototyping tracker: which of the ten modules on the Discovery board a Prototype has confirmed or rejected.
 *
 * The board offers ten modules; a selected one leaves the board for the track and its board icon hides. After
 * Prototype the game returns the wrong ones to the board faded and crossed out and keeps the right ones on the
 * track, so the moment new crosses appear, every module still on the track is confirmed. A confirmed module the
 * player later takes off the track looks like any other on the board, which is what the tracker is for. Plain Lua,
 * no client API: the session feeds it the reader's board states and reads it back for the view model.
 */
public final class Prototyping {

	/** One board position as the reader reports it. */
	public record BoardState(@Nullable Integer spriteId, boolean visible, boolean rejected) {
	}

	/** What the UI reads. */
	public record View(Set<Integer> confirmed, int confirmedCount, int rejectedCount) {
	}

	private final Set<Integer> confirmed = new HashSet<>(); // sprite ID -> true
	private final Set<Integer> rejected = new HashSet<>(); // sprite ID -> true
	private int confirmedCount;
	private int rejectedCount;

	public void confirm(int sprite) {
		if (!confirmed.contains(sprite)) {
			confirmed.add(sprite);
			confirmedCount++;
		}
	}

	/**
	 * One board snapshot, { spriteId, visible, rejected } per board position as the reader reports it. New crosses
	 * confirm every module that is on the track, which is every module whose board icon is hidden, at that moment.
	 *
	 * @param states board states; nil when the reader could not read the board
	 */
	@LuaName("Ingest")
	public void ingest(@Nullable List<BoardState> states) {
		if (states == null) {
			return;
		}
		boolean newRejections = false;
		Set<Integer> onTrack = new HashSet<>();
		for (BoardState module : states) {
			Integer sprite = module.spriteId();
			if (sprite != null && sprite >= 0) {
				if (module.rejected()) {
					if (!rejected.contains(sprite)) {
						rejected.add(sprite);
						rejectedCount++;
						newRejections = true;
					}
				} else if (!module.visible()) {
					onTrack.add(sprite);
				}
			}
		}
		if (newRejections) {
			for (int sprite : onTrack) {
				confirm(sprite);
			}
		}
	}

	/**
	 * Reaching the arranging stage means the five modules on the track are the right ones, including the ones the last
	 * Prototype passed without rejecting anything. Remembered for when the game shows this blueprint's board again.
	 *
	 * @param row the five sprite IDs of a complete track
	 */
	@LuaName("ConfirmAll")
	public void confirmAll(List<Integer> row) {
		for (int slot = 0; slot < 5; slot++) {
			confirm(row.get(slot));
		}
	}

	/**
	 * Whether anything has been learned yet, which is what makes a puzzle worth keeping when the player switches
	 * blueprint before grading a single row.
	 */
	@LuaName("HasProgress")
	public boolean hasProgress() {
		return confirmedCount > 0 || rejectedCount > 0;
	}

	/**
	 * What the UI shows: the confirmed set by sprite, for marking board icons, and the two counts.
	 *
	 * @return view: { confirmed = sprite ID -> true, confirmedCount, rejectedCount }
	 */
	@LuaName("View")
	public View view() {
		return new View(confirmed, confirmedCount, rejectedCount);
	}
}
