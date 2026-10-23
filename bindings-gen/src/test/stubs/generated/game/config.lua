---@meta

---An NPC's config.
---@class NPCType
---@field id Int The config's ID.
---@field name String The NPC's name.
local NPC = {}

---An inventory's config.
---@class InvType
---@field id Int The config's ID.
local InvType = {}

---An object's config.
---@class ObjType
---@field id Int The config's ID.
---@field name String The object's name.
local Obj = {}

---A variable's config.
---@class VarType
---@field id Int The variable's ID.
---@field value Int|String The value.
local VarType = {}

---@class Inventory
Inventory = {}

---The inventory with the config, when the client has it.
---@param config InvType The config.
---@return Inv? # The inventory, or nil.
function Inventory.Find(config) end

---An inventory's slots.
---@class Inv
---@field valid Bool Whether it still exists.
---@field slotCount Int How many slots it has.
local Inv = {}

---How many of the object it holds.
---@param objType ObjType The object.
---@return Int # The count.
function Inv:GetTotal(objType) end
