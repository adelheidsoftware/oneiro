local Shape = require("src/shapes/shape")
local Square = require("src/shapes/square")
local Shapes = require("src/shapes/shapes")
local Circle = require("src/shapes/circle")

local square = Square.new(3)
assert(square:area() == 9)
assert(square.name == "square", "the superclass constructor ran")
assert(square:describe() == "a square of 9", "super.describe() calls the superclass's method")
assert(square:toString() == "a square of 9", "the inherited method calls the override")
assert(getmetatable(Square).__index == Shape)

local rectangle = Shapes.Rectangle.new(2, 5)
assert(rectangle:describe() == "rectangle of 10")
assert(Shapes.total({ rectangle, Shapes.Rectangle.new(1, 1) }) == 11)
assert(Shapes.doubled(rectangle) == 20)
assert(Shapes.text(square) == "shape: a square of 9")

assert(Circle.new(12).name == "large circle" and Circle.new(1).name == "small circle", "code before super(...)")
