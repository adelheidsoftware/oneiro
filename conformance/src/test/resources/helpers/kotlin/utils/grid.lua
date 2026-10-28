-- utils/grid.lua
-- What Kotlin's Array(rows) { IntArray(columns) } makes: a new array of the sizes, each size an array of the next, with
-- copies of value innermost.

local function grid(value, size, ...)
    local array = {}
    for i = 1, size do
        if select("#", ...) == 0 then
            array[i] = value
        else
            array[i] = grid(value, ...)
        end
    end
    return array
end

return grid
