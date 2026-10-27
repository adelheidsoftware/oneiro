-- src/report.lua
-- A report of levels, made with the functions of another file.

local Levels = require("src/levels")

local Report = {}

-- Each level, kept within bounds, as text.
---@param levels integer[]
---@return string
function Report.lines(levels)
    local parts = {}
    for _, it in ipairs(levels) do
        parts[#parts + 1] = Levels.levelText(Levels.clampLevel(it))
    end
    return table.concat(parts, ", ")
end

-- How many of the levels are the highest.
---@param levels integer[]
---@return integer
function Report.maxed(levels)
    local count = 0
    for _, item in ipairs(levels) do
        if Levels.isMaxed(item) then
            count = count + 1
        end
    end
    return count
end

-- The highest level, as the other file says.
---@return integer
function Report.highest()
    return Levels.MAX_LEVEL
end

return Report
