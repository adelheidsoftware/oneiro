-- A file of top-level functions and properties is a module, which other files require.

local Levels = require("src/levels")
local Report = require("src/report")
local Herbs = require("src/herbs")

assert(Levels.MAX_LEVEL == 99, "a top-level property of the module")
assert(Levels.clampLevel(120) == 99 and Levels.clampLevel(0) == 1, "a top-level function")
assert(Levels.levelText(99) == "99*" and Levels.levelText(50) == "level 50", "an extension function at the top level")
assert(Report.lines({ 120, 50 }) == "99*, level 50", "calls of another file's functions")
assert(Report.maxed({ 99, 50, 120 }) == 2, "a reference to another file's function")
assert(Report.highest() == 99, "another file's property")
assert(Herbs.joined({ "Guam", "RANARR" }) == "guam, ranarr", "functions and properties private to a class's file")
