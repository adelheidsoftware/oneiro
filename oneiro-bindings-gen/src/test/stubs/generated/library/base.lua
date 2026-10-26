---@meta

-- A small stand-in for rs3-api-types, written for Oneiro's tests in the same layout: the RS3 API's public names, with
-- descriptions of our own. It covers the shapes the bindings generator handles, not the whole API.

---An integer the docs type as 64 bits wide.
---@alias Long integer

---A base URL of the manifest's: an object of the client's, not a string.
---@class PluginURL
local PluginURL = {}

---@alias VarPlayer VarType
