-- A record with members of its own is a class whose fields are its components.

local Tile = require("src/tile")

local tile = Tile.ground(3, 4)
assert(tile.x == 3 and tile.y == 4 and tile.plane == 0, "a static factory, and the components as fields")
assert(tile:stepsTo(Tile.ORIGIN) == 4, "a method reading components through fields and accessors")
assert(tile:toString() == "3, 4, 0", "the record's own toString")
assert(not pcall(Tile.new, 0, 0, 7), "the compact constructor checks before the components are set")
