-- Plain tables hold what the order-keeping helper held, where the code reads no order, and give the same.

local Stock = require("src/stock")

Stock.add("ranarr", 2)
Stock.add("guam", 3)
Stock.add("ranarr", 1)
assert(Stock.count("ranarr") == 3 and Stock.count("guam") == 3 and Stock.count("tarromin") == -1, "a plain map and set")
assert(Stock.order() == "ranarrguam", "a map whose order the code reads keeps it")
assert(Stock.price("guam") == 10 and Stock.price("tarromin") == 0, "a plain map of entries")
assert(Stock.kinds({ "a", "b", "a" }) == 2, "a plain map of a function's own")
