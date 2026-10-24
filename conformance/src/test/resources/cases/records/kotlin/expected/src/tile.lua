-- src/tile.lua
-- A tile on the map, with the steps to another.

local Tile = {}
Tile.__index = Tile

function Tile.new(x, y, plane)
    local self = setmetatable({}, Tile)
    self.x = x
    self.y = y
    self.plane = plane
    if plane < 0 or plane > 3 then
        error(formatStr("plane {}", plane))
    end
    return self
end

-- The number of steps to the other tile, moving diagonally.
---@param other Tile
---@return integer
function Tile:stepsTo(other)
    return math.max(math.abs(self.x - other.x), math.abs(self.y - other.y))
end

function Tile:toString()
    return formatStr("{}, {}, {}", self.x, self.y, self.plane)
end

-- The tile at the origin of the ground plane.
Tile.ORIGIN = Tile.new(0, 0, 0)

-- The tile at the coordinates on the ground plane.
---@param x integer
---@param y integer
---@return Tile
function Tile.ground(x, y)
    return Tile.new(x, y, 0)
end

return Tile
