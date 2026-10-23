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
