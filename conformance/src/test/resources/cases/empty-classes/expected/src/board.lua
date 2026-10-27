-- src/board.lua
-- Makes marks.

local Marker = require("src/marker")

local Board = {}

function Board.mark()
    return Marker.new()
end

function Board.marker()
    return Marker.new
end

return Board
