---@meta

---@class PersistentDB
PersistentDB = {}

---The value stored under the key.
---@param key string The key.
---@return boolean|integer|number|string|table|nil # The value, or nil when there is none.
function PersistentDB:Get(key) end

---@param key string The key.
---@return boolean? # The value, or nil when there is none.
function PersistentDB:GetBool(key) end

---@param key string The key.
---@param value boolean
---@return boolean # Whether it was stored.
function PersistentDB:SetBool(key, value) end

---@param key string The key.
---@return integer? # The value, or nil when there is none.
function PersistentDB:GetInt(key) end

---@param key string The key.
---@param value integer
---@return boolean # Whether it was stored.
function PersistentDB:SetInt(key, value) end

---@param key string The key.
---@return number? # The value, or nil when there is none.
function PersistentDB:GetFloat(key) end

---@param key string The key.
---@param value number
---@return boolean # Whether it was stored.
function PersistentDB:SetFloat(key, value) end

---@param key string The key.
---@return string? # The value, or nil when there is none.
function PersistentDB:GetString(key) end

---@param key string The key.
---@param value string
---@return boolean # Whether it was stored.
function PersistentDB:SetString(key, value) end

---Stores a value of any of the types.
---@param key string The key.
---@param value boolean|integer|number|string|table
---@return boolean # Whether it was stored.
function PersistentDB:Set(key, value) end

---Calls the callback for each entry; it returns true to stop.
---@param callback fun(key: string, value: boolean|integer|number|string|table): boolean # Called for each entry.
---@return integer # How many entries were visited.
function PersistentDB:IterateEntries(callback) end
