---@meta

---Bits of a key event's modifiers.
---@enum ModifierKey
ModifierKey = {
    SHIFT = 1,
    CONTROL = 2,
    ALT = 4,
}

---@class Keyboard
Keyboard = {}

---Whether the keyboard can be read on this device.
---@return Bool # True on desktops.
function Keyboard.IsAvailable() end

---Whether a shift key is down.
---@return Bool # True while one is held.
function Keyboard.IsShiftDown() end
