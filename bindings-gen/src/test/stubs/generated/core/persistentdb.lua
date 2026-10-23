---@meta

---@class PersistentDB
PersistentDB = {}

---The value stored under the key.
---@param key String The key.
---@return Bool|Int|Float|String|Table|nil # The value, or nil when there is none.
function PersistentDB:Get(key) end

---@param key String The key.
---@return Bool? # The value, or nil when there is none.
function PersistentDB:GetBool(key) end

---@param key String The key.
---@param value Bool
---@return Bool # Whether it was stored.
function PersistentDB:SetBool(key, value) end

---@param key String The key.
---@return Int? # The value, or nil when there is none.
function PersistentDB:GetInt(key) end

---@param key String The key.
---@param value Int
---@return Bool # Whether it was stored.
function PersistentDB:SetInt(key, value) end

---@param key String The key.
---@return Float? # The value, or nil when there is none.
function PersistentDB:GetFloat(key) end

---@param key String The key.
---@param value Float
---@return Bool # Whether it was stored.
function PersistentDB:SetFloat(key, value) end

---@param key String The key.
---@return String? # The value, or nil when there is none.
function PersistentDB:GetString(key) end

---@param key String The key.
---@param value String
---@return Bool # Whether it was stored.
function PersistentDB:SetString(key, value) end

---Stores a value of any of the types.
---@param key String The key.
---@param value Bool|Int|Float|String|Table
---@return Bool # Whether it was stored.
function PersistentDB:Set(key, value) end

---Calls the callback for each entry; it returns true to stop.
---@param callback fun(key: String, value: Bool|Int|Float|String|Table): Bool # Called for each entry.
---@return Int # How many entries were visited.
function PersistentDB:IterateEntries(callback) end
