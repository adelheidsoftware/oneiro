-- Runs Blueprint-Helper's own tests against a copy of the plugin whose core modules Oneiro wrote. Unlike the plugin's
-- tests/run_all.lua it does not exit, which would end the JVM that runs the bundled Lua, and it lists every failure.
-- Arguments: the plugin folder, with src/ and tests/, then the names of the tests to run.

local root = arg[1]
package.path = root .. "/?.lua;" .. root .. "/tests/?.lua;" .. package.path

local Harness = require("harness")
Harness.PLUGIN_ROOT = root
for index = 2, #arg do
    require(arg[index])
end
print(string.format("%d passed, %d failed", Harness.passed, Harness.failed))
for _, failure in ipairs(Harness.failures) do
    print("FAIL " .. failure)
end
