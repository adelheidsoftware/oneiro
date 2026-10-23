---@meta

---@class World
World = {}

---Whether the world can be read.
---@return Bool # True once it has loaded.
function World.IsAvailable() end

---The ground's height at a position.
---@param level Int The level.
---@param x Int The x, in fine units.
---@param z Int The z, in fine units.
---@return Int # The height, or -1.
---@return Bool
function World.GetGroundHeight(level, x, z) end

World.RemoveRoof = {}

---Hides the roof above a tile.
---@param name String A name for the entry.
---@param coord CoordGrid The tile.
function World.RemoveRoof.Add(name, coord) end
