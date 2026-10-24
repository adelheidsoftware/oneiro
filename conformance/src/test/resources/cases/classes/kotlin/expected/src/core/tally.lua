-- src/core/tally.lua
-- A tally of confirmed modules, as a class with instances.

local Tally = {}
Tally.__index = Tally

-- Declared ahead: used above their definitions below.
local bump

-- Every tally starts here.
Tally.START = 0

function Tally.new(label)
    local self = setmetatable({}, Tally)
    self.label = label
    self.confirmed = {} -- sprite ID -> true
    self.count = Tally.START
    self.locked = false
    return self
end

-- Confirms a module once; confirming it again changes nothing.
---@param sprite integer
function Tally:confirm(sprite)
    if self.locked or self.confirmed[sprite] ~= nil then
        return
    end
    self.confirmed[sprite] = true
    bump(self)
end

function Tally:total()
    return self.count
end

function Tally:lock()
    self.locked = true
end

function bump(self)
    self.count = self.count + 1
end

function Tally.named(label)
    return Tally.new(label)
end

return Tally
