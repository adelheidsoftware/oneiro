-- src/base.lua
-- A base that holds nothing of its own, which another class extends.

local Base = {}
Base.__index = Base

function Base.new()
    local self = setmetatable({}, Base)
    return self
end

return Base
