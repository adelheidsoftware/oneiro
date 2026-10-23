-- A finally block runs whatever the try block does, and a resource is closed whatever happens.

local Cleanup = require("src/cleanup")

Cleanup.counted(function() end)
local ok = pcall(Cleanup.counted, function()
    error("broken")
end)
assert(not ok and Cleanup.runs == 2, "the finally block runs when the try block fails, and the error goes on")
assert(Cleanup.firstOr("guam", "?") == "g" and Cleanup.firstOr("", "?") == "?", "the try and catch blocks' values")
assert(Cleanup.runs == 4, "and the finally block ran each time")
assert(Cleanup.read("ranarr") == "r" and Cleanup.Reader.closes == 1, "the resource is closed after its value is read")
assert(not pcall(Cleanup.read, "") and Cleanup.Reader.closes == 2, "and closed when reading fails")
