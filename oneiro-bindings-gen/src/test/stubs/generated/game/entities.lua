---@meta

---An NPC in view.
---@class NPCEntity
---@field isValid Bool Whether it still exists.
---@field uid Int Its unique ID.
---@field displayName String Its name.
---@field config NPCType Its config.
---@field coordGrid CoordGrid Its tile.
local NPCEntity = {}

---Its head bar's value, if it shows one.
---@return Int? # The value, or nil.
function NPCEntity:GetHeadbar() end

---A player in view.
---@class PlayerEntity
---@field isValid Bool Whether it still exists.
---@field displayName String The player's name.
---@field coordGrid CoordGrid The player's tile.
local PlayerEntity = {}

---@class NPCList
NPCList = {}

---Every NPC in view.
---@return NPCEntity[] # The NPCs.
function NPCList.All() end

---The NPCs that pass every test of the settings.
---@param filterSet NPCListFilterSettings The tests.
---@return NPCEntity[] # The NPCs.
function NPCList.Filter(filterSet) end

---@class NPCListFilterSettings
---@field name String A name to match.
---@field coordGrid CoordGrid A tile to measure from.
---@field range Int How far from the tile.
---@field func fun(npcEntity: NPCEntity): Bool A test of each NPC.
