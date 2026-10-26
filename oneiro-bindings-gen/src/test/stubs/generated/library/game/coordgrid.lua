---@meta

---A tile of the game world.
---@class CoordGrid
---@field level integer The level, 0 to 3.
---@field x integer The tile's x.
---@field z integer The tile's z.
CoordGrid = {}

---A tile from a packed integer.
---@param bitPacked integer The packed form.
---@return CoordGrid
function CoordGrid.new(bitPacked) end

---A tile from its parts.
---@param level integer The level.
---@param x integer The x.
---@param z integer The z.
---@return CoordGrid
function CoordGrid.new(level, x, z) end

---The packed form.
---@return integer # The packed integer.
function CoordGrid:ToPacked() end

---The tile moved by an offset.
---@param dx integer Along x.
---@param dz integer Along z.
---@return CoordGrid # The new tile.
function CoordGrid:Offset(dx, dz) end
