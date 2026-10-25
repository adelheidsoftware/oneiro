-- An if or when inside an expression gives its value as Kotlin's does.

local logged = {}
function log(...)
    logged[#logged + 1] = table.concat({ ... }, " ")
end

local Moods = require("src/moods")

Moods.report(Moods.Mood.ANGRY)
assert(logged[#logged] == "The monster is angry", "a when inside a call")
assert(Moods.level(105) == "level 105: 6 past the maximum", "an if with a block inside a call")
assert(Moods.level(50) == "level 50: normal", "an if's other branch")
assert(Moods.warn(Moods.Mood.ANGRY, false) == true and Moods.warn(Moods.Mood.CALM, false) == false,
    "an if of booleans inside a call")
Moods.both(Moods.Mood.CALM, Moods.Mood.ANGRY)
assert(logged[#logged] == "calm and upset", "two whens in one statement")
