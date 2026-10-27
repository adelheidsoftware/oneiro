-- src/windows.lua
-- Kotlin's collection functions that walk a list in windows: chunked, windowed and zip.

local Windows = {}

-- The names in rows of the width.
---@param names string[]
---@param width integer
---@return string[][]
function Windows.rows(names, width)
    local list = {}
    for i = 1, #names, width do
        local chunk = table.move(names, i, math.min(i + width - 1, #names), 1, {})
        list[#list + 1] = chunk
    end
    return list
end

-- The names in rows of two, each joined.
---@param names string[]
---@return string[]
function Windows.joinedRows(names)
    local list = {}
    for i = 1, #names, 2 do
        local row = table.move(names, i, math.min(i + 1, #names), 1, {})
        list[#list + 1] = table.concat(row, "-")
    end
    return list
end

-- How many times a level is higher than the one before.
---@param levels integer[]
---@return integer
function Windows.rises(levels)
    local count = 0
    for i = 1, #levels - 1 do
        local pair = table.move(levels, i, i + 1, 1, {})
        if pair[1] < pair[2] then
            count = count + 1
        end
    end
    return count
end

-- Each name with the level at its position, as pairs.
---@param names string[]
---@param levels integer[]
---@return table[]
function Windows.paired(names, levels)
    local list = {}
    for i = 1, math.min(#names, #levels) do
        local pair = { first = names[i], second = levels[i] }
        list[#list + 1] = pair
    end
    return list
end

-- Each name with the level at its position, as text.
---@param names string[]
---@param levels integer[]
---@return string[]
function Windows.labels(names, levels)
    local list = {}
    for i = 1, math.min(#names, #levels) do
        local name, level = names[i], levels[i]
        list[#list + 1] = formatStr("{} {}", name, level)
    end
    return list
end

-- The names whose level is at least the least.
---@param names string[]
---@param levels integer[]
---@param least integer
---@return string[]
function Windows.atLeast(names, levels, least)
    local list = {}
    for i = 1, math.min(#names, #levels) do
        local it = { first = names[i], second = levels[i] }
        if it.second >= least then
            list[#list + 1] = it.first
        end
    end
    return list
end

return Windows
