---@meta

---@class HTTPRequest
---@field data string (Read-only) The response, once it arrived.
---@field successful boolean (Read-only) Whether it succeeded.
---@field responseCode integer (Read-only) The HTTP status.
HTTPRequest = {}

---A request to the base URL and an optional path.
---@param baseURL PluginURL A URL of the manifest.
---@param path? string A path after it.
---@return HTTPRequest
function HTTPRequest.new(baseURL, path) end

---Sends it as a GET.
---@param completionCallback fun(self: HTTPRequest)? # Called when it finishes.
function HTTPRequest:Get(completionCallback) end
