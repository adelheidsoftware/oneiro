-- Type tests read an object's metatable, and a class that others extend walks up the chain.

local Monster = require("src/monster")
local Boss = require("src/boss")
local Pet = require("src/pet")
local Encounters = require("src/encounters")

assert(Encounters.danger(Boss.new("Kalphite Queen", 333)) == "deadly Kalphite Queen", "the first case that matches")
assert(Encounters.danger(Monster.new("Abyssal demon", 124)) == "strong Abyssal demon", "a guard")
assert(Encounters.danger(Monster.new("Goblin", 2)) == "weak Goblin", "the same type without the guard")
assert(Encounters.danger(Pet.new("Baby mole")) == "friendly Baby mole", "a class nothing extends, by its metatable")
assert(Encounters.level(Boss.new("Vorkath", 732)) == 732, "instanceof accepts a subclass")
assert(Encounters.level(Pet.new("Pet rock")) == 0, "instanceof rejects another line")
assert(Encounters.isText("guam") and not Encounters.isText(5), "instanceof String")
