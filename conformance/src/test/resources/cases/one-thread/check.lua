-- volatile and synchronized change nothing on the client's one thread: the count goes up by one a visit.

local Visits = require("src/visits")

assert(Visits.visit() == 1, "the first visit")
assert(Visits.visit() == 2, "the second visit")
