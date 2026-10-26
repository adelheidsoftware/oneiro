---@meta

---Bits of a key event's modifiers.
---@enum ModifierKey
ModifierKey = {
    SHIFT = 1,
    CONTROL = 2,
    ALT = 4,
}

---The name of one of the values, as `ModifierKey.FromID(ModifierKey.SHIFT)` is `"SHIFT"`.
---@param value integer
---@return string
---@diagnostic disable-next-line: inject-field
function ModifierKey.FromID(value) end

---@class Keyboard
Keyboard = {}

---Whether the keyboard can be read on this device.
---@return boolean # True on desktops.
function Keyboard.IsAvailable() end

---Whether a shift key is down.
---@return boolean # True while one is held.
function Keyboard.IsShiftDown() end
