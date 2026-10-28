-- src/patterns.lua
-- Values Lua reads twice that only Java has: a pattern's value, a bound reference's object, and & of a call.

local Patterns = {}

-- Marks texts with its tag.
local Labeller = {}
Labeller.__index = Labeller
Patterns.Labeller = Labeller

function Labeller.new(tag)
    local self = setmetatable({}, Labeller)
    self.tag = tag
    return self
end

function Labeller:label(text)
    return formatStr("{} {}", self.tag, text)
end

local checks = 0

-- A setting's value, which may be text or a number.
---@param name string
---@return any
function Patterns.setting(name)
    return name == "mode" and "fast" or 0
end

-- A setting's length, or -1 when it is no text: a call's value tested by a pattern.
---@param name string
---@return integer
function Patterns.settingLength(name)
    local setting = Patterns.setting(name)
    if type(setting) == "string" then
        return #setting
    end
    return -1
end

-- A labeller of the tag.
---@param tag string
---@return Labeller
function Patterns.labeller(tag)
    return Labeller.new(tag)
end

-- The names labelled, by a reference to a method of a call's value.
---@param names string[]
---@param tag string
---@return string[]
function Patterns.labelled(names, tag)
    local labeller = Patterns.labeller(tag)
    local list = {}
    for _, item in ipairs(names) do
        list[#list + 1] = labeller:label(item)
    end
    return list
end

-- Whether a name is long, counting the checks.
---@param name string
---@return boolean
local function isLong(name)
    checks = checks + 1
    return #name > 3
end

-- Whether the plugin is ready and the name long, checking the name either way, as Java's & does.
---@param ready boolean
---@param name string
---@return boolean
function Patterns.readyAndLong(ready, name)
    local long = isLong(name)
    return ready and long
end

-- How many checks the names have had.
---@return integer
function Patterns.checkCount()
    return checks
end

return Patterns
