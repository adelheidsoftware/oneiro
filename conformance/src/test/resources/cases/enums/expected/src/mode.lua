-- src/mode.lua
-- How the tracker reacts to a new reading.

local Mode = {
    -- Reacts at once.
    FAST = "FAST",
    -- waits for a second reading
    SAFE = "SAFE",
    OFF = "OFF",
}

return Mode
