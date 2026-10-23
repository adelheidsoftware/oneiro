-- Interfaces share constants, default and static methods; library interfaces give classes metamethods.

local Herbal = require("src/herbal")
local Garden = require("src/garden")
local Describable = require("src/describable")

local guam = Herbal.new("guam", 3)
local ranarr = Herbal.new("ranarr", 25)
assert(guam:describe() == "a guam herb", "a default method copied into the class reads the class's own kind")
assert(Describable.shout(ranarr) == "A RANARR HERB", "a static method of the interface")
assert(Garden.shout(guam) == "A GUAM HERB", "a static interface method from another module")

local sorted = Garden.byLevel({ ranarr, guam })
assert(sorted[1] == guam and sorted[2] == ranarr, "Comparable orders through __lt")

local planted = Garden.plant({ "guam", "tarromin" })
assert(#planted == 2 and planted[2] == "tarromin", "an object that implements Consumer is called as a function")
