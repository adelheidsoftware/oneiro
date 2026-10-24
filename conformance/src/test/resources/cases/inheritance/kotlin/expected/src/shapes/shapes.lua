-- src/shapes/shapes.lua
-- Shapes measured through interfaces, and a subclass nested next to the code that uses it.

local Shape = require("src/shapes/shape")

local Shapes = {}

-- A rectangle.
local Rectangle = setmetatable({}, { __index = Shape })
Rectangle.__index = Rectangle
Shapes.Rectangle = Rectangle

function Rectangle.new(width, height)
    local self = setmetatable(Shape.new("rectangle"), Rectangle)
    self.width = width
    self.height = height
    return self
end

function Rectangle:area()
    return self.width * self.height
end

function Shapes.total(shapes)
    local total = 0.0
    for _, shape in ipairs(shapes) do
        total = total + shape:area()
    end
    return total
end

function Shapes.scaled(shape, scale)
    return scale(shape:area())
end

function Shapes.doubled(shape)
    return Shapes.scaled(shape, function(value)
        return value * 2
    end)
end

function Shapes.text(shape)
    return formatStr("shape: {}", shape:toString())
end

return Shapes
