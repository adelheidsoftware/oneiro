-- src/shapes/circle.lua
-- A circle: a shape with a radius, named for its size.

local Shape = require("src/shapes/shape")

local Circle = setmetatable({}, { __index = Shape })
Circle.__index = Circle

function Circle.new(radius)
    local size = radius > 10 and "large" or "small"
    local self = setmetatable(Shape.new(formatStr("{} circle", size)), Circle)
    self.radius = 0.0
    self.radius = radius
    return self
end

function Circle:area()
    return math.pi * self.radius * self.radius
end

return Circle
