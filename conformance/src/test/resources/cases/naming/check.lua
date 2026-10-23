local NPCTracker = require("src/core/npc_tracker")

assert(NPCTracker.track({ "a", "b", "a" }) == 2 + 3, "two distinct names plus the limit")
