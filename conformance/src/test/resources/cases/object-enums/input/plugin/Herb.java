package plugin;

/** Herbs a patch can grow, each with its seed and the Farming level it needs. */
public enum Herb {
	/** The first herb a farmer grows. */
	GUAM(5291, 9),
	MARRENTILL(5292, 14), // a little later
	RANARR(5295, 32) {
		private final String note = "the prayer herb";

		@Override
		public String describe() {
			return note;
		}
	};

	private final int seedId;
	private final int requiredLevel;

	Herb(int seedId, int requiredLevel) {
		this.seedId = seedId;
		this.requiredLevel = requiredLevel;
	}

	/** Whether a farmer of the level can plant it. */
	public boolean canPlant(int farming) {
		return farming >= requiredLevel;
	}

	/** What the herb is, for the log. */
	public String describe() {
		return name().toLowerCase() + " from seed " + seedId;
	}

	/** The herb after this one, or this one when it is the last. */
	public Herb next() {
		Herb[] all = values();
		if (ordinal() + 1 < all.length) {
			return all[ordinal() + 1];
		}
		return this;
	}
}
