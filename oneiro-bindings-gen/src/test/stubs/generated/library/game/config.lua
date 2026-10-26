---@meta

---An NPC's config.
---@class NPCType
---@field id integer (Read-only) The config's ID.
---@field name string (Read-only) The NPC's name.
local NPCType = {}

---An inventory's config.
---@class InvType
---@field id integer (Read-only) The config's ID.
local InvType = {}

---An object's config.
---@class ObjType
---@field id integer (Read-only) The config's ID.
---@field name string (Read-only) The object's name.
local ObjType = {}

---A variable's config.
---@class VarType
---@field id integer (Read-only) The variable's ID.
---@field value integer|string (Read-only) The value.
local VarType = {}

---@class Inventory
Inventory = {}

---The inventory with the config; one the client does not have has no slots.
---@param config InvType The config.
---@return Inv # The inventory.
function Inventory.Find(config) end

---An inventory's slots.
---@class Inv
---@field isValid boolean (Read-only) Whether it still exists.
---@field slotCount integer (Read-only) How many slots it has.
local Inv = {}

---How many of the object it holds.
---@param objType ObjType The object.
---@return integer # The count.
function Inv:GetTotal(objType) end
