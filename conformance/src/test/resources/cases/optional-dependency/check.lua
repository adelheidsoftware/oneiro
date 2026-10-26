-- A stand-in for the library plugin's global, which the client gives a plugin while the library is loaded.
local shown = nil
gauges = {
    version = "2.1.0",
    Gauge = {
        new = function(label)
            return {
                SetValue = function(self, value)
                    shown = label .. "=" .. value
                end,
            }
        end,
    },
}

local Meters = require("src/meters")

Meters.show(0.5)
assert(shown == "health=0.5", "a gauge, made where the library is loaded")
assert(Meters.version() == "2.1.0", "the library's version")
assert(Meters.present(), "loaded")
assert(Meters.label(true) == "v2.1.0" and Meters.label(false) == "-", "the check beside another condition")
assert(Meters.isGauges("gauges") and not Meters.isGauges("prettyui"), "the alias")

-- Turned off: the global is nil, and nothing reaches it
gauges = nil
shown = nil
Meters.show(0.25)
assert(shown == nil, "no gauge while the library is off")
assert(Meters.version() == "none", "no version either")
assert(not Meters.present(), "not loaded")
assert(Meters.label(true) == "-", "nor its label")
