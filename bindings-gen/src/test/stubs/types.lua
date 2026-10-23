---@meta

-- A small stand-in for runescape-api-stubs, written for Oneiro's tests in the same layout: the RS3 API's public names,
-- with descriptions of our own. It covers the shapes the bindings generator handles, not the whole API.

-- ── Primitive type aliases ───────────────────────────────────────────────────
---@alias Float number
---@alias Int integer
---@alias Long integer
---@alias Uint integer
---@alias String string
---@alias Bool boolean
---@alias Table table
---@alias PluginURL string

---@alias VarPlayer VarType

---@class ObjId : integer
---@class NPCId : integer
---@class InvId : integer
---@class VarPlayerId : integer
---@class SeqId : integer

---@class _global.id
---@field Obj { [string]: ObjId, FromID: fun(id: ObjId): string}
---@field NPC { [string]: NPCId, FromID: fun(id: NPCId): string}
---@field Inv { [string]: InvId, FromID: fun(id: InvId): string}
---@field Seq { [string]: SeqId, FromID: fun(id: SeqId): string}

id = {}

---@class _global.var
---@field VarPlayer { [string]: VarType, FromID: fun(id: VarPlayerId): VarType} Variables kept in the character's save.
var = {}

---Config lookup tables.
---@class _global.config
---@field NPC { [string]: NPCType, FromID: fun(id: NPCId): NPCType}
---@field Inv { [string]: InvType, FromID: fun(id: InvId): InvType}
---@field Obj { [string]: ObjType, FromID: fun(id: ObjId): ObjType}
config = {}
