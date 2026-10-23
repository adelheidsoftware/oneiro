---@meta

---A tile of the game world.
---@class CoordGrid
---@field level Int The level, 0 to 3.
---@field x Int The tile's x.
---@field z Int The tile's z.
CoordGrid = {}

---A tile from a packed integer.
---@param bitPacked Int The packed form.
---@return CoordGrid
function CoordGrid.new(bitPacked) end

---A tile from its parts.
---@param level Int The level.
---@param x Int The x.
---@param z Int The z.
---@return CoordGrid
function CoordGrid.new(level, x, z) end

---The packed form.
---@return Int # The packed integer.
function CoordGrid:ToPacked() end

---The tile moved by an offset.
---@param dx Int Along x.
---@param dz Int Along z.
---@return CoordGrid # The new tile.
function CoordGrid:Offset(dx, dz) end
