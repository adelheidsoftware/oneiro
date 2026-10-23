---@meta

---@class HTTPRequest
---@field data String The response, once it arrived.
---@field successful Bool Whether it succeeded.
---@field responseCode Int The HTTP status.
HTTPRequest = {}

---A request to the base URL and an optional path.
---@param baseURL PluginURL A URL of the manifest.
---@param path String? A path after it.
---@return HTTPRequest
function HTTPRequest.new(baseURL, path) end

---Sends it as a GET.
---@param completionCallback fun(self: HTTPRequest)? # Called when it finishes.
function HTTPRequest:Get(completionCallback) end
