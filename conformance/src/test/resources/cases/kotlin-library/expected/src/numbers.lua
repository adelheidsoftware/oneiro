-- src/numbers.lua
-- Kotlin's functions on numbers, and repeat, takeIf and takeUnless.

local compare = require("utils/compare")

local Numbers = {}

-- The level, kept between 1 and 99.
---@param raw integer
---@return integer
function Numbers.level(raw)
    return math.min(math.max(raw, 1), 99)
end

-- The amount, at least none.
---@param amount integer
---@return integer
function Numbers.atLeastNone(amount)
    return math.max(amount, 0)
end

-- The amount, at most the cap.
---@param amount integer
---@param cap integer
---@return integer
function Numbers.capped(amount, cap)
    return math.min(amount, cap)
end

-- The part of the whole, in whole percent, a half rounded up.
---@param part number
---@param whole number
---@return integer
function Numbers.percent(part, whole)
    return math.floor(part * 100 / whole + 0.5)
end

-- -1, 0 or 1 as the first level is below, at or above the second.
---@param first integer
---@param second integer
---@return integer
function Numbers.order(first, second)
    return compare(first, second)
end

-- The text, said the given number of times.
---@param text string
---@param times integer
---@return string
function Numbers.echo(text, times)
    local said = ""
    for _ = 1, times do
        said = said .. text
    end
    return said
end

-- The indices below the count, added up.
---@param count integer
---@return integer
function Numbers.indexSum(count)
    local total = 0
    for index = 0, count - 1 do
        total = total + index
    end
    return total
end

-- The level when it is positive, otherwise null.
---@param level integer
---@return integer?
function Numbers.positive(level)
    return level > 0 and level or nil
end

-- The name when it is not blank, otherwise null.
---@param name string
---@return string?
function Numbers.named(name)
    return name:find("%S") ~= nil and name or nil
end

-- The name in lower case, unless it is none.
---@param name string
---@return string?
function Numbers.lowerName(name)
    local lower = name:lower()
    if lower == "none" then
        lower = nil
    end
    return lower
end

return Numbers
