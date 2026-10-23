-- A running total.

local Tally = {}
Tally.__index = Tally

function Tally.new()
    local self = setmetatable({}, Tally)
    self.total = 0
    return self
end

function Tally:add(amount)
    self.total = self.total + amount
end

function Tally:sum()
    return self.total
end

return Tally
