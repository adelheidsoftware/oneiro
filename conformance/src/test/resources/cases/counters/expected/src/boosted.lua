-- Counters whose level can drop below zero.

local Counters = require("src/counters")

local Boosted = setmetatable({}, { __index = Counters })
Boosted.__index = Boosted

function Boosted.new()
    local self = setmetatable(Counters.new(), Boosted)
    return self
end

function Boosted:level()
    return Counters.level(self) - 5
end

return Boosted
