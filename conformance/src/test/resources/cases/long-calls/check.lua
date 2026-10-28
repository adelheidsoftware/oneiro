-- A call whose list is too long for its line gives what it would on one line.

local Panel = require("src/panel")

assert(Panel.session(5, 1) == 3, "the list's three lines")
