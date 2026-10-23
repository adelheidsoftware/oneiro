-- Runs one conformance case's generated modules under Lua 5.4: loads every module to check that it parses, then runs
-- the case's check.lua, if it has one, with the plugin folder on package.path and the formatStr stand-in defined.
-- Arguments: the plugin folder, the formatStr stand-in, the check script or "", then the generated files.

local root, standIn, check = arg[1], arg[2], arg[3]
package.path = root .. "/?.lua;" .. package.path
dofile(standIn)

for index = 4, #arg do
    assert(loadfile(arg[index]))
end
if check ~= "" then
    dofile(check)
end
