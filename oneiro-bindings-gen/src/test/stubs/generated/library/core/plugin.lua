---@meta

---The permissions a plugin may ask for.
---@enum PluginPermissions
PluginPermissions = {
    httpAccess = "httpAccess", -- HTTP requests to the manifest's URLs.
    chatAccess = "chatAccess", -- Chat messages.
    trackingAccess = "trackingAccess", -- The character's tracking ID.
}

---@class Plugin
---@field urls table The manifest's URLs.
---@field resources table The plugin's resources.
Plugin = {}

---Whether the user granted the permission.
---@param permission PluginPermissions The permission.
---@return boolean # True when granted.
function Plugin.HasPermission(permission) end

---A file of the plugin's resources folder.
---@class PluginResource
---@field path string (Read-only) Where the file is.
---@field loaded boolean (Read-only) Whether it is loaded.
local PluginResource = {}

---Loads the resource now.
---@return boolean # Whether it loaded.
function PluginResource:Load() end

---@class PluginImageResource : PluginResource
PluginImageResource = {}

---@class PluginAudioResource : PluginResource
PluginAudioResource = {}
