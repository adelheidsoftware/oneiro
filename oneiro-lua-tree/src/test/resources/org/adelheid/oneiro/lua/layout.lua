-- Line breaking: each statement is too long for one line, so the text shows where StyLua breaks it.

local message = formatStr(
    "Blueprint {} at level {} with {} readings and {} more words here plus a lot more",
    name,
    level,
    count,
    other,
    more
)
local call =
    someFunction(argumentNumberOne, argumentNumberTwo, argumentNumberThree, argumentNumberFour, argumentNumberFive6)
local list =
    { "identity", "moduleOf", "spriteOf", "readings", "visited", "contradiction", "answers", "answer", "suggestion" }
local longer = {
    "identity",
    "moduleOf",
    "spriteOf",
    "readings",
    "visited",
    "contradiction",
    "answers",
    "answer",
    "suggestion",
    "prototyping",
}
local named =
    { text = "a fairly long string value for this table field", colour = Theme.MUTED, size = 12, x = 1, y = 2, z = 3 }
view:AddText({
    text = "a fairly long string value for this table field",
    colour = Theme.MUTED,
    size = 12,
    x = 1,
    y = 2,
    z = 3,
})
local total = alphaValueNumberOne
    + betaValueNumberTwo * gammaValueNumberThree
    - deltaValueNumberFour
    + epsilonValueLongerStill
local joined = firstStringPart
    .. secondStringPart
    .. thirdStringPart
    .. fourthStringPart
    .. fifthStringPart
    .. sixthPartLongerStill
Event.ServerLogic.Subscribe("tick-counter.server-logic", function(payload)
    count = count + 1
end)
pcall(function() end)
local object = withMembers(Shape.new("square"), {
    area = function(self)
        return 1.0
    end,
})
someFunction(
    argumentNumberOne,
    argumentNumberTwo,
    argumentNumberThree,
    argumentNumberFour,
    argumentNumberFive,
    function()
        return 1
    end
)
pcall(function()
    count = count + 1
end, count)
local labels = someFunction(
    alpha,
    beta,
    { text = "a fairly long string value for this table field", colour = Theme.MUTED, size = 12 }
)

local function check(
    parameterNumberOne,
    parameterNumberTwo,
    parameterNumberThree,
    parameterNumberFour,
    parameterFiveSixSeven
)
    if
        someLongConditionName ~= nil
        and anotherLongConditionName ~= nil
        and yetAnotherConditionName ~= nil
        and lastOne
    then
        return 1
    elseif
        someFunctionName(argumentNumberOne, argumentNumberTwo, argumentNumberThree, argumentNumberFour, argument5)
    then
        return 2
    end
    return someLongConditionName ~= nil
        or anotherLongConditionName ~= nil
        or yetAnotherConditionName ~= nil
        or lastOne1234
end
