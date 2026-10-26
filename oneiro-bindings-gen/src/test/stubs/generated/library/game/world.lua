---@meta

---@class World
World = {}

---Whether the world can be read.
---@return boolean # True once it has loaded.
function World.IsAvailable() end

---The ground's height at a position.
---@param level integer The level.
---@param x integer The x, in fine units.
---@param z integer The z, in fine units.
---@return integer # The height, or -1.
---@return boolean
function World.GetGroundHeight(level, x, z) end

World.RemoveRoof = {}

---Hides the roof above a tile.
---@param name string A name for the entry.
---@param coord CoordGrid The tile.
function World.RemoveRoof.Add(name, coord) end
