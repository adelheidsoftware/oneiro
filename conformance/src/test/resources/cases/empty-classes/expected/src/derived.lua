-- src/derived.lua
-- A base with a value.

local Base = require("src/base")

local Derived = setmetatable({}, { __index = Base })
Derived.__index = Derived

function Derived.new()
    local self = setmetatable(Base.new(), Derived)
    return self
end

function Derived:value()
    return 1
end

return Derived
