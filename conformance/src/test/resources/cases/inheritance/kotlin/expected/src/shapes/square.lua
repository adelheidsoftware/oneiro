-- src/shapes/square.lua
-- A square: a shape with four equal sides.

local Shape = require("src/shapes/shape")

local Square = setmetatable({}, { __index = Shape })
Square.__index = Square

function Square.new(side)
    local self = setmetatable(Shape.new("square"), Square)
    self.side = side
    return self
end

function Square:area()
    return self.side * self.side
end

function Square:describe()
    return formatStr("a {}", Shape.describe(self))
end

return Square
