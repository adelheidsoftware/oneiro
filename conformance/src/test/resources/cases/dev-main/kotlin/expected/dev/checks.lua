-- dev/checks.lua
-- Dev code that uses the main source set's types as the main set's Lua makes them.

local SummaryStyle = require("src/summary_style")
local Section = require("src/section")
local Tally = require("src/tally")
local Shape = require("src/shape")
local Bank = require("src/bank")

-- Whether the value is an object of the class, or of a class that extends it, as Kotlin's is.
local function isInstance(value, class)
    if type(value) ~= "table" then
        return false
    end
    local current = getmetatable(value)
    while current ~= nil do
        if current == class then
            return true
        end
        -- A subclass's table looks up what it lacks in its superclass's
        local parent = getmetatable(current)
        current = parent and parent.__index
    end
    return false
end

local Checks = {}

-- How many summary styles there are.
---@return integer
function Checks.styles()
    return #{ SummaryStyle.COMPACT, SummaryStyle.DETAILED }
end

-- A style's name.
---@param style string
---@return string
function Checks.styleName(style)
    return style
end

-- Where the skills section comes.
---@return integer
function Checks.skillsOrder()
    return Section.SKILLS.ordinal
end

-- The skills section's name and title.
---@return string
function Checks.skillsLabel()
    return formatStr("{}: {}", Section.SKILLS.name, Section.SKILLS.title)
end

-- Whether the speed is the fast one, by its order.
---@param speed Speed
---@return boolean
function Checks.isFast(speed)
    return speed.ordinal == 1
end

-- A summary of one level.
---@param xp integer
---@return table
function Checks.summary(xp)
    return { xp = xp, levels = 1 }
end

-- A tally of three, doubled.
---@return integer
function Checks.doubledThree()
    return Tally.new(3):doubled()
end

-- Whether the value is a shape, a circle among them.
---@param value any
---@return boolean
function Checks.isShape(value)
    return isInstance(value, Shape)
end

-- A tab of the bank.
---@param bank Bank
---@param name string
---@return Tab
function Checks.tab(bank, name)
    return Bank.Tab.new(bank, name)
end

return Checks
