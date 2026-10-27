-- A class that holds nothing has objects where code makes them or a class extends it; one of constants has none.

local Board = require("src/board")
local Derived = require("src/derived")
local Marker = require("src/marker")
local Limits = require("src/limits")

assert(getmetatable(Board.mark()) == Marker, "an empty class that code creates")
assert(getmetatable(Board.marker()()) == Marker, "an empty class whose constructor a reference names")
assert(Derived.new():value() == 1, "a class that extends an empty one")
assert(Limits.MOST == 3 and Limits.new == nil, "a class of constants alone has no objects")
