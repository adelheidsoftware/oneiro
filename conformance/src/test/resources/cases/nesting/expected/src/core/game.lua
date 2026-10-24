-- src/core/game.lua
-- Uses the board, and its public nested class, from another file.

local Board = require("src/core/board")

local Game = {}

function Game.opening()
    local board = Board.new()
    board:mark(1)
    local extra = Board.Cell.new(5)
    return formatStr("{}/{}/{}", board:first():label(), board:marked(), extra:label())
end

return Game
