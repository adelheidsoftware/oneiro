-- src/idioms.lua
-- Kotlin's own idioms: preconditions, fun interfaces, references to the library, literal maps and sets, labels.

local trim = require("utils/trim")
local orderedTable = require("utils/ordered_table")
local addElements = require("utils/add_elements")
local putEntries = require("utils/put_entries")

local Idioms = {}

-- Declared ahead: used above their definitions below.
local start

-- Steps of a size a private companion keeps, which only the class reaches.
local Steps = {}
Steps.__index = Steps
Idioms.Steps = Steps

local SIZE = 3

function Steps.new()
    local self = setmetatable({}, Steps)
    return self
end

-- How far the steps go, from the start.
---@param count integer
---@return integer
function Steps:total(count)
    return count * SIZE + start()
end

function start()
    return 1
end

-- A test a class gives, which Lua calls as the function every Test is.
local AtLeast = {}
AtLeast.__index = AtLeast
Idioms.AtLeast = AtLeast

function AtLeast.new(minimum)
    local self = setmetatable({}, AtLeast)
    self.minimum = minimum
    return self
end

function AtLeast:passes(value)
    return value >= self.minimum
end

-- Called as a function, as every Test is in Lua
function AtLeast:__call(...)
    return self:passes(...)
end

-- The values that pass the test.
---@param values integer[]
---@param test function
---@return integer[]
function Idioms.passing(values, test)
    local list = {}
    for _, it in ipairs(values) do
        if test(it) then
            list[#list + 1] = it
        end
    end
    return list
end

-- A test made from a lambda.
---@return function
function Idioms.positive()
    return function(it)
        return it > 0
    end
end

-- Whether the value passes a Predicate made from a lambda.
---@param value integer
---@return boolean
function Idioms.small(value)
    local predicate = function(it)
        return it < 10
    end
    return predicate(value)
end

-- A level, which must be a real one.
---@param value integer
---@return integer
function Idioms.level(value)
    if not (value >= 1 and value <= 120) then
        error(formatStr("level {}", value))
    end
    if value == 99 then
        error("Check failed.")
    end
    return value
end

-- The name of a herb, which the map must hold.
---@param names table<integer, string>
---@param id integer
---@return string
function Idioms.herbName(names, id)
    local name = names[id]
    if name == nil then
        error(formatStr("no herb {}", id))
    end
    return name
end

-- The seed of a herb, which the map must hold.
---@param seeds table<string, integer>
---@param herb string
---@return integer
function Idioms.seed(seeds, herb)
    if seeds[herb] == nil then
        error("Required value was null.")
    end
    return seeds[herb] or error("Required value was null.")
end

-- Whether a setting is on, which must be known.
---@param setting boolean?
---@return boolean
function Idioms.enabled(setting)
    local on = setting
    if on == nil then
        error("The setting is unknown.")
    end
    return on
end

-- Work for later, a Runnable made from a lambda.
---@param counts integer[]
---@return function
function Idioms.counter(counts)
    return function()
        counts[1] = counts[1] + 1
    end
end

-- The lines without their outer spaces, each printed.
---@param lines string[]
---@return string[]
function Idioms.trimmed(lines)
    local trimmed = {}
    for _, item in ipairs(lines) do
        trimmed[#trimmed + 1] = trim(item)
    end
    for _, item in ipairs(trimmed) do
        print(item)
    end
    return trimmed
end

-- Whether each name is one the set knows.
---@param names string[]
---@param set table<string, true>
---@return boolean[]
function Idioms.known(names, set)
    local list = {}
    for _, item in ipairs(names) do
        list[#list + 1] = set[item] ~= nil
    end
    return list
end

-- The levels of the herbs, in the order written.
---@return table<string, integer>
function Idioms.levels()
    return putEntries(orderedTable(), { "guam", 3, "marrentill", 5 })
end

-- The herb patches, in the order written.
---@return table<string, true>
function Idioms.patches()
    return addElements(orderedTable(), { "falador", "catherby" })
end

-- The prices, whose order does not matter.
---@return table<string, integer>
function Idioms.prices()
    return { guam = 10, ranarr = 7000 }
end

-- The total of the values before the first negative one, which a labelled break leaves at.
---@param values integer[]
---@return integer
function Idioms.beforeNegative(values)
    local total = 0
    for _, value in ipairs(values) do
        if value < 0 then
            break
        end
        total = total + value
    end
    return total
end

-- A value known to be text, as text.
---@param value any
---@return string
function Idioms.asText(value)
    return value
end

-- A value as text when it is text, and null otherwise.
---@param value any
---@return string?
function Idioms.textOrNull(value)
    return type(value) == "string" and value or nil
end

-- Logs a tick, with a lambda that never reads its parameter.
---@return function
function Idioms.ticker()
    return function()
        log("tick")
    end
end

return Idioms
