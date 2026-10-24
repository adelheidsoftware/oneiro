-- src/core/board.lua
-- A board of cells, with the cell class nested inside.

local Board = {}
Board.__index = Board

-- Declared ahead: used above their definitions below.
local Format

-- One cell of the board.
local Cell = {}
Cell.__index = Cell
Board.Cell = Cell

function Cell.new(index)
    local self = setmetatable({}, Cell)
    self.index = 0
    self.marked = false
    self.index = index
    return self
end

function Cell:mark()
    self.marked = true
end

function Cell:label()
    return Format.cell(self.index, self.marked)
end

-- Text helpers, private to the board.
Format = {}

function Format.cell(index, marked)
    return marked and formatStr("[{}]", index) or formatStr(" {} ", index)
end

-- How many cells a new board has.
local SIZE = 3

function Board.new()
    local self = setmetatable({}, Board)
    self.cells = {}
    for index = 0, SIZE - 1 do
        self.cells[#self.cells + 1] = Cell.new(index)
    end
    return self
end

function Board:marked()
    local count = 0
    for _, cell in ipairs(self.cells) do
        if cell.marked then
            count = count + 1
        end
    end
    return count
end

function Board:mark(index)
    self.cells[index + 1]:mark()
end

function Board:first()
    return self.cells[1]
end

return Board
