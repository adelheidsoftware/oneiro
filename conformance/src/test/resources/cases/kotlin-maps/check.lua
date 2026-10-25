-- Chains over a map's entries give what Kotlin's give.

local Stock = require("src/stock")

local stock = { guam = 3, ranarr = 0, toadflax = 7, snapdragon = 1 }

local plenty = Stock.plenty(stock, 2)
table.sort(plenty)
assert(table.concat(plenty, ",") == "guam,toadflax", "filter over entries, then map")

local inStock = Stock.inStock(stock)
assert(inStock.guam == 3 and inStock.ranarr == nil and inStock.snapdragon == 1, "a map's filter gives a map")

assert(Stock.stacks(stock) == 2, "count over entries")
assert(Stock.mostCommon(stock) == "toadflax" and Stock.mostCommon({}) == nil, "maxByOrNull, then ?.key")
assert(Stock.fewest(stock) == 0 and Stock.fewest({}) == 0, "minByOrNull, then ?.value ?: 0")
assert(Stock.firstWith(stock, 7) == "toadflax" and Stock.firstWith(stock, 9) == nil, "firstOrNull, then ?.key")

local worths = Stock.worths(stock, 10)
assert(worths.guam == 30 and worths.ranarr == 0, "mapValues")
local shouted = Stock.shouted(stock)
assert(shouted.GUAM == 3 and shouted.guam == nil, "mapKeys")
local long = Stock.longNames(stock)
assert(long.toadflax == 7 and long.snapdragon == nil and long.guam == nil and long.ranarr == nil,
    "filterKeys, then filterValues")

assert(Stock.lines({ guam = 3 }) == "guam: 3", "joinToString over entries")
assert(Stock.total(stock) == 11, "a for loop over the entries reads entry.value")
assert(#Stock.items(stock) == 4, "forEach over the entries reads it.key")
