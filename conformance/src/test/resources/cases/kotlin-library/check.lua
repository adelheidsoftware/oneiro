-- Kotlin's library functions behave as Kotlin's do: on numbers, text, lists, maps and sets.

local Numbers = require("src/numbers")
local Texts = require("src/texts")
local Folds = require("src/folds")
local Sets = require("src/sets")

assert(Numbers.level(0) == 1 and Numbers.level(120) == 99 and Numbers.level(50) == 50, "coerceIn")
assert(Numbers.atLeastNone(-5) == 0 and Numbers.atLeastNone(5) == 5, "coerceAtLeast")
assert(Numbers.capped(12, 10) == 10 and Numbers.capped(3, 10) == 3, "coerceAtMost")
assert(Numbers.percent(1, 8) == 13 and Numbers.percent(1, 3) == 33, "roundToInt rounds a half up")
assert(math.type(Numbers.percent(1, 2)) == "integer", "roundToInt gives an integer")
assert(Numbers.order(1, 2) == -1 and Numbers.order(2, 2) == 0 and Numbers.order(3, 2) == 1, "compareTo")
assert(Numbers.echo("ab", 3) == "ababab" and Numbers.echo("ab", 0) == "", "repeat without its index")
assert(Numbers.indexSum(4) == 6 and Numbers.indexSum(0) == 0, "repeat counts its index from 0")
assert(Numbers.positive(5) == 5 and Numbers.positive(0) == nil, "takeIf")
assert(Numbers.named("Zezima") == "Zezima" and Numbers.named("  ") == nil, "takeUnless")
assert(Numbers.lowerName("Guthix") == "guthix" and Numbers.lowerName("NONE") == nil, "takeIf on a value read once")

assert(Texts.underscored("dragon bones") == "dragon_bones", "replace")
assert(Texts.underscored("100% done") == "100%_done", "replace finds the text as it is written")
assert(Texts.lineCount("a\nb\r\nc") == 3 and Texts.lineCount("") == 1, "lines")
assert(Texts.orNothing(nil) == "" and Texts.orNothing("Bob") == "Bob", "a string's orEmpty")
assert(Texts.unnamed(nil) and Texts.unnamed("") and not Texts.unnamed("Bob"), "a string's isNullOrEmpty")
assert(Texts.blank(nil) and Texts.blank("  ") and not Texts.blank(" Bob "), "isNullOrBlank")
assert(Texts.letters("banana", "a") == 3 and Texts.letters("", "a") == 0, "a text's count")
assert(Texts.kind("rune2h") == "code" and Texts.kind("rune") == "word", "a text's any in a condition")
assert(Texts.lettersOnly("Guthix") and not Texts.lettersOnly("Guthix!"), "a text's all")

local drops = { { item = "coins", count = 50 }, { item = "rare", count = 1 }, { item = "coins", count = 6 } }
assert(Folds.total(drops, 10) == 67 and Folds.total({}, 10) == 10, "fold")
assert(Folds.biggest({ 3, 9, 4 }) == 9 and Folds.biggest({}) == nil, "reduceOrNull")
assert(Folds.averageCount(drops) == 19.0, "average")
assert(Folds.averageCount({}) ~= Folds.averageCount({}), "the average of nothing is NaN")
assert(Folds.only(drops, "rare") == drops[2], "singleOrNull finds the one")
assert(Folds.only(drops, "coins") == nil and Folds.only(drops, "bones") == nil, "and nil for more than one or none")
local everything = Folds.everything({ { "a", "b" }, {}, { "c" } })
assert(#everything == 3 and everything[1] == "a" and everything[3] == "c", "flatten")
local names = Folds.names({ "Bob", 3, true, "Zezima" })
assert(#names == 2 and names[1] == "Bob" and names[2] == "Zezima", "filterIsInstance")
local backwards = Folds.backwards({ "a", "b", "c" })
assert(backwards[1] == "c" and backwards[3] == "a" and #backwards == 3, "reversed")
local kept = Folds.keep({ "coins", "bones", "coins", "herb" }, "rare")
assert(#kept == 3 and kept[1] == "rare" and kept[2] == "bones" and kept[3] == "herb", "buildList")
assert(#Folds.keep({ "coins" }, nil) == 0, "buildList with nothing added")
assert(Folds.label(5) == "stacks" and Folds.label(1) == "one", "run gives its last expression")
assert(Folds.doubled(4) == 8, "run of one value")
assert(Folds.nextCount(4) == "Next: 5", "run inside an expression")

-- Keys in the order they were first added, as Kotlin's maps keep them
local function ordered(...)
    local map = {}
    local keys = {}
    for i = 1, select("#", ...), 2 do
        local key, value = select(i, ...)
        map[key] = value
        keys[#keys + 1] = key
    end
    return setmetatable(map, {
        __pairs = function(t)
            local i = 0
            return function()
                i = i + 1
                local key = keys[i]
                if key ~= nil then
                    return key, t[key]
                end
            end
        end,
    })
end

local function walked(set)
    local keys = {}
    for key in pairs(set) do
        keys[#keys + 1] = key
    end
    return table.concat(keys, ",")
end

local xp = ordered("attack", 10, "strength", 20, "magic", 30)
assert(walked(Sets.skills(xp)) == "attack,strength,magic", "a map's keys keep its order")
assert(table.concat(Sets.amounts(xp), ",") == "10,20,30", "a map's values")
assert(Sets.skillCount(xp) == 3, "the size of a map's keys")
assert(Sets.trained(xp, "magic") and not Sets.trained(xp, "prayer"), "in a map's keys")
assert(Sets.experience(xp, "strength") == 20 and Sets.experience(xp, "prayer") == 0, "getOrElse")
local forgotten = { attack = 1, magic = 2 }
Sets.forget(forgotten)
assert(next(forgotten) == nil, "clear")
local mine = ordered("attack", true, "magic", true)
local theirs = ordered("magic", true, "prayer", true)
assert(walked(Sets.either(mine, theirs)) == "attack,magic,prayer", "union")
assert(walked(Sets.both(mine, theirs)) == "magic", "intersect")
assert(walked(Sets.onlyMine(mine, theirs)) == "attack", "subtract")
assert(next(Sets.levels(nil)) == nil and Sets.levels(xp) == xp, "a map's orEmpty")
assert(Sets.noLevels(nil) and Sets.noLevels({}) and not Sets.noLevels(xp), "a map's isNullOrEmpty")
local starting = Sets.starting({ "attack", "defence" })
assert(walked(starting) == "attack,defence" and starting.defence == 1, "buildMap")

local Windows = require("src/windows")

local rows = Windows.rows({ "a", "b", "c", "d", "e" }, 2)
assert(#rows == 3 and rows[1][2] == "b" and #rows[3] == 1 and rows[3][1] == "e", "chunked, the last chunk smaller")
local joined = Windows.joinedRows({ "a", "b", "c" })
assert(joined[1] == "a-b" and joined[2] == "c" and #joined == 2, "chunked with a transform")
assert(Windows.rises({ 1, 3, 2, 5 }) == 2 and Windows.rises({ 4 }) == 0, "windowed")
local paired = Windows.paired({ "a", "b", "c" }, { 1, 2 })
assert(#paired == 2 and paired[2].first == "b" and paired[2].second == 2, "zip stops at the shorter list")
local labels = Windows.labels({ "a", "b" }, { 1, 2, 3 })
assert(#labels == 2 and labels[2] == "b 2", "zip with a transform")
local strong = Windows.atLeast({ "a", "b", "c" }, { 5, 1, 9 }, 5)
assert(#strong == 2 and strong[1] == "a" and strong[2] == "c", "a chain over zip's pairs")
