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
