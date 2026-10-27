-- src/members.lua
-- Kotlin's secondary constructors, and extension properties, as Lua functions.

local Members = {}

-- A tally, which starts from a value or from none.
local Tally = {}
Tally.__index = Tally
Members.Tally = Tally

function Tally.new(name, total)
    total = total or 0
    local self = setmetatable({}, Tally)
    self.name = name
    self.total = total
    return self
end

-- Adds the amount.
---@param amount integer
function Tally:add(amount)
    self.total = self.total + amount
end

-- A counter made by its one secondary constructor.
local Counter = {}
Counter.__index = Counter
Members.Counter = Counter

function Counter.new(start)
    local self = setmetatable({}, Counter)
    self.count = start * 2
    return self
end

-- A tally of the name with nothing in it.
---@param name string
---@return Tally
function Members.empty(name)
    return Tally.new(name)
end

-- A counter from three.
---@return Counter
function Members.counter()
    return Counter.new(3)
end

-- The first letter of a text.
---@param self string
---@return string
function Members.initial(self)
    return self:sub(1, 1)
end

-- Whether a list holds more than one element.
---@param self integer[]
---@return boolean
local function many(self)
    return #self > 1
end

-- The initial of a name.
---@param name string
---@return string
function Members.initialOf(name)
    return Members.initial(name)
end

-- Whether the levels are many.
---@param levels integer[]
---@return boolean
function Members.hasMany(levels)
    return many(levels)
end

return Members
