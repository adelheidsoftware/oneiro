-- An inner object keeps its outer object, and reads its fields and methods through it.

local Bank = require("src/bank")

local bank = Bank.new("Zezima")
local tab = bank:open("herbs")
assert(tab:title() == "Zezima's herbs (2 of 2)", "an inner object reads its outer object's fields and methods")
assert(tab.outer == bank, "the inner object keeps its outer one")
assert(bank:tabCount() == 2, "the instance initializer ran before the constructor")
