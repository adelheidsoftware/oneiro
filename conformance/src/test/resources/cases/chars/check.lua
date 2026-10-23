-- Chars are one-character strings, and give Java's numbers where Java reads them as numbers.

local Letters = require("src/letters")

assert(Letters.caesar("abc xyz!", 3) == "def abc!", "letters move and wrap, and the rest stays")
assert(Letters.initials("Session Clock") == "SC", "charAt in a loop that counts from 1")
assert(Letters.digitSum("a1b2c39") == 15, "a digit's number less the number of '0'")

local counts = Letters.letterCounts("Abba!")
assert(counts.a == 2 and counts.b == 2 and counts["!"] == nil, "chars as map keys")

assert(Letters.alphabet("a", "e") == "abcde", "c++ steps to the next char")
assert(Letters.alphabet("c", "a") == "", "a loop that never starts")
assert(Letters.code("A") == 65, "an int set from a char")
assert(Letters.fromCode(97) == "a", "a cast from int to char")
assert(Letters.grade(2) == "B" and Letters.grade(7) == "F", "chars from a switch")
assert(Letters.describe("A") == "A=65", "%c and the char's number")
assert(Letters.capitalised("oak") == "Oak", "toUpperCase of the first char")
assert(Letters.find("hello", "l") == 2 and Letters.find("hello", "z") == -1, "indexOf a char")
assert(Letters.joined({ "o", "k" }) == "ok 2", "a char array as text")
