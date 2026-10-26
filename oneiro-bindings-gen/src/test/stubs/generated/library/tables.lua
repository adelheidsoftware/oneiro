---@meta

---The game's IDs, by namespace and name.
---@class id
---@field Obj id.Obj
---@field NPC id.NPC
---@field Inv id.Inv
---@field Seq id.Seq
id = {}

---@class id.Obj
---@field [string] integer
---@field FromID fun(id: integer): string The name of an ID.

---@class id.NPC
---@field [string] integer
---@field FromID fun(id: integer): string The name of an ID.

---@class id.Inv
---@field [string] integer
---@field FromID fun(id: integer): string The name of an ID.

---@class id.Seq
---@field [string] integer
---@field FromID fun(id: integer): string The name of an ID.

---The game's variables, by domain and name.
---@class var
---@field VarPlayer var.VarPlayer Variables kept in the character's save.
var = {}

---@class var.VarPlayer
---@field [string] VarType

---Config lookup tables.
---@class config
---@field NPC config.NPC
---@field Inv config.Inv
---@field Obj config.Obj
config = {}

---@class config.NPC
---@field [string] NPCType
---@field FromID fun(id: integer): NPCType The config of an ID.

---@class config.Inv
---@field [string] InvType
---@field FromID fun(id: integer): InvType The config of an ID.

---@class config.Obj
---@field [string] ObjType
---@field FromID fun(id: integer): ObjType The config of an ID.
