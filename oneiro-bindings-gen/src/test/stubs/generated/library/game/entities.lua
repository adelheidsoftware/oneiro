---@meta

---An NPC in view.
---@class NPCEntity
---@field isValid boolean (Read-only) Whether it still exists.
---@field uid integer (Read-only) Its unique ID.
---@field displayName string (Read-only) Its name.
---@field config NPCType (Read-only) Its config.
---@field coordGrid CoordGrid (Read-only) Its tile.
local NPCEntity = {}

---Its head bar's value, if it shows one.
---@return integer? # The value, or nil.
function NPCEntity:GetHeadbar() end

---A player in view.
---@class PlayerEntity
---@field isValid boolean (Read-only) Whether it still exists.
---@field displayName string (Read-only) The player's name.
---@field coordGrid CoordGrid (Read-only) The player's tile.
local PlayerEntity = {}

---@class PlayerList
PlayerList = {}

---The local player.
---@return PlayerEntity # The player.
function PlayerList.Self() end

---Every player in view.
---@return PlayerEntity[] # The players.
function PlayerList.All() end

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
---@field name string A name to match.
---@field coordGrid CoordGrid A tile to measure from.
---@field range integer How far from the tile.
---@field func fun(npcEntity: NPCEntity): boolean A test of each NPC.
