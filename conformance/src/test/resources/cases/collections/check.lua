-- Each method against what the Java collections would hold.
local Tables = require("src/tables")

-- add 3, add 1: { 3, 1 }; add(0, 2): { 2, 3, 1 }; set(1, 5): { 2, 5, 1 }; sorted: { 1, 2, 5 }
assert(table.concat(Tables.numbers(), ",") == "1,2,5")
assert(Tables.inspect({ 2, 5 }))
assert(not Tables.inspect({ 1, 2, 5 }))
local numbers = { 7, 8 }
assert(Tables.removeFirst(numbers) == 7 and #numbers == 1 and numbers[1] == 8)
local scores = Tables.scores()
assert(scores.ann == 3 and scores.bob == nil)
assert(Tables.lookup(scores, "ann") == 3)
assert(Tables.lookup(scores, "zed") == 0)
local seen = Tables.seen({ 0, 1, 1, 2 })
assert(seen[1] and seen[2] and seen[0] == nil)
assert(Tables.has(seen, 1) and not Tables.has(seen, 0))
assert(table.concat(Tables.doubled({ "a" }), ",") == "a,a")
assert(table.concat(Tables.fixed(), ",") == "a,b")
local fixedMap = Tables.fixedMap()
assert(fixedMap.a == 1 and fixedMap.b == 2)
local fixedSet = Tables.fixedSet()
assert(fixedSet.x and fixedSet.y)
assert(table.concat(Tables.sorted({ 3, 1, 2 }), ",") == "1,2,3")
assert(Tables.weighed({ ab = 3, c = 4 }) == 2 + 1 + 3 + 4)
