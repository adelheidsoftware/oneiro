-- src/counters.lua
-- Integer division on values that can never be negative, and on values that can.

local quotient = require("utils/quotient").quotient

local Counters = {}
Counters.__index = Counters

function Counters.new()
    local self = setmetatable({}, Counters)
    self.ticks = 0
    self.balance = 0
    self.shared = 0
    return self
end

function Counters:tick()
    self.ticks = self.ticks + 1
end

function Counters:spend(amount)
    self.balance = self.balance - amount
end

-- Whole minutes of ticks, at 100 a minute.
---@return integer
function Counters:minutes()
    return self.ticks // 100
end

function Counters:report()
    local minutes = self:minutes()
    local hours = minutes // 60
    return formatStr("{}h {}m", hours, minutes % 60)
end

function Counters:halves(values)
    local total = 0
    for _, value in ipairs(values) do
        total = total + math.abs(value)
    end
    return total // 2
end

function Counters:balanceTens()
    return quotient(self.balance, 10)
end

function Counters:sharedTens()
    return quotient(self.shared, 10)
end

function Counters:level()
    return self.ticks // 1000
end

function Counters:levelHalves()
    return quotient(self:level(), 2)
end

return Counters
