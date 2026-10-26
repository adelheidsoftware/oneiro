---@meta

---Writes the arguments to the console, separated by spaces.
---@param ... any
function print(...) end

---Writes the arguments to the console after the plugin's name.
---@param format any
---@param ... any
function log(format, ...) end

---Runs a console command.
---@param command string
function ExecuteConsoleCommand(command) end
