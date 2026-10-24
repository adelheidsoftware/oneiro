-- src/shapes/shape.lua
-- A shape with a name, which subclasses give an area.

local Shape = {}
Shape.__index = Shape

function Shape.new(name)
    local self = setmetatable({}, Shape)
    self.name = name
    return self
end

function Shape:describe()
    return formatStr("{} of {}", self.name, self:area())
end

function Shape:toString()
    return self:describe()
end

return Shape
