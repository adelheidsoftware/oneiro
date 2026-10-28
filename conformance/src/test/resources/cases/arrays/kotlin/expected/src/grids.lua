-- src/grids.lua
-- Arrays of more than one dimension, and functions that take any number of values.

local grid = require("utils/grid").grid

local Grids = {}

-- A board of the size, every square empty.
---@param rows integer
---@param columns integer
---@return boolean[][]
function Grids.board(rows, columns)
    return grid(false, rows, columns)
end

-- Marks the square of the board.
---@param board boolean[][]
---@param row integer
---@param column integer
function Grids.mark(board, row, column)
    board[row + 1][column + 1] = true
end

-- The sum of the numbers, however many there are.
---@param ... integer
---@return integer
function Grids.total(...)
    local numbers = { ... }
    local sum = 0
    for _, number in ipairs(numbers) do
        sum = sum + number
    end
    return sum
end

-- The total of a few numbers and of an array of them.
---@param more integer[]
---@return integer
function Grids.both(more)
    return Grids.total(1, 2) + Grids.total(table.unpack(more))
end

-- The label with the parts after it, such as "herbs: guam, tarromin".
---@param label string
---@param ... string
---@return string
function Grids.listed(label, ...)
    local parts = { ... }
    return formatStr("{}: {}", label, table.concat(parts, ", "))
end

return Grids
