---@meta

---Writes the arguments to the console, separated by spaces.
---@param ... any
function print(...) end

---Writes the arguments to the console after the plugin's name.
---@param format String
---@param ... any
function log(format, ...) end

---Runs a console command.
---@param command String
function ExecuteConsoleCommand(command) end

---Loads a file of the plugin.
---@param path string
---@return any
function require(path) end
