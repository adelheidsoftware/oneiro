-- A tally used as the Java class would be.
local Tally = require("src/core/tally")

local tally = Tally.named("board")
tally:confirm(25074)
tally:confirm(25074)
tally:confirm(26158)
assert(tally:total() == 2, "confirming twice counts once")
tally:lock()
tally:confirm(1)
assert(tally:total() == 2, "a locked tally confirms nothing")
assert(tally.label == "board")
assert(Tally.START == 0)
