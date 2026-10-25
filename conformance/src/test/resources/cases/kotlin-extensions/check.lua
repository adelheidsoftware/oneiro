-- Extension functions take their receiver first, and read and call its members.

local Texts = require("src/texts")

assert(Texts.shout("guam") == "GUAM!", "an extension function's receiver is self")
assert(Texts.shoutTwice("ash") == "ASH! ASH!", "an extension calls another on its own receiver")

local herbs = Texts.herbs({ "guam", "logs", "ranarr" })
assert(#herbs == 2 and herbs[2] == "ranarr", "a private extension is a local function")

local tally = Texts.Tally.new(1)
Texts.addTwice(tally, 2)
assert(tally.total == 5, "an extension calls its receiver's methods")
assert(Texts.doubled(tally) == 10, "an extension reads its receiver's properties")
assert(Texts.loud("x") == "X!" and Texts.loud(nil) == nil, "?. calls an extension only on a value")

assert(Texts.rest("guam") == 3, "a library property of the receiver, unnamed")
assert(Texts.added({ 1, 2, 3 }) == 6, "a chain's last call on the receiver, unnamed")
local loud = Texts.loudHerbs({ "guam", "logs", "ranarr" })
assert(#loud == 2 and loud[1] == "GUAM" and loud[2] == "RANARR", "a chain on the receiver, unnamed")
assert(Texts.totalLength({ "ab", "cde" }) == 5, "forEach on the receiver, unnamed")
local recorded = {}
Texts.record(recorded, "guam")
Texts.record(recorded, "ash")
assert(#recorded == 3 and recorded[1] == "start" and recorded[3] == "ash", "calls that change the receiver, unnamed")
