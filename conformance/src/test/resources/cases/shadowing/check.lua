-- Each renamed local leaves the name it shares to what the Java meant by it.
local Walker = require("src/walker")
local Badge = require("src/badge")
local Tag = require("src/tag")

assert(Walker.countDown(4) == 3, "the private method calls itself, not its local")
assert(Walker.plusTotal(5) == 15, "the private field, not the local of the same name")
assert(Walker.doubled(4) == 8, "a parameter that hides nothing it uses")
assert(Badge.new("gold", 2):describe() == "gold 2", "the component kept under the name its accessor reads")

local tag = Tag.new("#")
local all = tag:all({ "a", "b" })
assert(all[1] == "#a" and all[2] == "#b", "a method reference opened in a loop")
assert(tag:labeller()("c") == "#c", "a method reference as a function")
