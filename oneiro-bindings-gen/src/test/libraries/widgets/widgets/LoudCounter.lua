--- A counter that prints each step.
---@class widgets.LoudCounter : widgets.Counter
local LoudCounter = setmetatable({}, { __index = require("widgets/Counter") })
LoudCounter.__index = LoudCounter

---@return widgets.LoudCounter
function LoudCounter.new()
    return setmetatable({ value = 0, step = 1 }, LoudCounter)
end

-- An override without annotations: the method it overrides gives its types
function LoudCounter:advance(by)
    print("advance", by)
    self.value = self.value + by
    return self
end

return LoudCounter
