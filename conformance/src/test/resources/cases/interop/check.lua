-- The generated class used the way hand-written Lua would use it: PascalCase methods, plain tables in and out.
local Tracker = require("src/core/tracker")

local tracker = Tracker.new()
tracker:Ingest({
    { spriteId = 1, visible = true },
    { visible = true },
    { spriteId = 2, visible = false },
    { spriteId = 3, visible = true },
})
local summary = tracker:Summary()
assert(summary.seen == 2, "two visible positions with a sprite")
assert(summary.level == 1, "the hand-written module computed the level")

-- A listener for a signal whose listeners give nothing back
local Levels = require("src/core/levels")
tracker:watch()
Levels.fromPenalty(6)
assert(tracker.levels == 3, "the listener saw the level")
