-- Runs one conformance case's generated modules under Lua 5.4: loads every module to check that it parses, then runs
-- the case's check.lua, if it has one, with the plugin folder on package.path and the client's formatStr and require
-- stood in for. Arguments: the plugin folder, the check script or "", the formatStr and require stand-ins, then the
-- generated files.

local root, check = arg[1], arg[2]
package.path = root .. "/?.lua;" .. package.path
dofile(arg[3])
dofile(arg[4])

for index = 5, #arg do
    assert(loadfile(arg[index]))
end
if check ~= "" then
    dofile(check)
end
