-- src/core/prototyping.lua
-- The prototyping tracker: which of the ten modules on the Discovery board a Prototype has confirmed or rejected.
--
-- The board offers ten modules; a selected one leaves the board for the track and its board icon hides. After
-- Prototype the game returns the wrong ones to the board faded and crossed out and keeps the right ones on the
-- track, so the moment new crosses appear, every module still on the track is confirmed. A confirmed module the
-- player later takes off the track looks like any other on the board, which is what the tracker is for. Plain Lua,
-- no client API: the session feeds it the reader's board states and reads it back for the view model.

local Prototyping = {}
Prototyping.__index = Prototyping

function Prototyping.new()
    local self = setmetatable({}, Prototyping)
    self.confirmed = {} -- sprite ID -> true
    self.rejected = {} -- sprite ID -> true
    self.confirmedCount = 0
    self.rejectedCount = 0
    return self
end

function Prototyping:confirm(sprite)
    if self.confirmed[sprite] == nil then
        self.confirmed[sprite] = true
        self.confirmedCount = self.confirmedCount + 1
    end
end

-- One board snapshot, { spriteId, visible, rejected } per board position as the reader reports it. New crosses
-- confirm every module that is on the track, which is every module whose board icon is hidden, at that moment.
---@param states table[]? board states; nil when the reader could not read the board
function Prototyping:Ingest(states)
    if states == nil then
        return
    end
    local newRejections = false
    local onTrack = {}
    for _, module in ipairs(states) do
        local sprite = module.spriteId
        if sprite ~= nil and sprite >= 0 then
            if module.rejected then
                if self.rejected[sprite] == nil then
                    self.rejected[sprite] = true
                    self.rejectedCount = self.rejectedCount + 1
                    newRejections = true
                end
            elseif not module.visible then
                onTrack[sprite] = true
            end
        end
    end
    if newRejections then
        for sprite in pairs(onTrack) do
            self:confirm(sprite)
        end
    end
end

-- Reaching the arranging stage means the five modules on the track are the right ones, including the ones the last
-- Prototype passed without rejecting anything. Remembered for when the game shows this blueprint's board again.
---@param row integer[] the five sprite IDs of a complete track
function Prototyping:ConfirmAll(row)
    for slot = 1, 5 do
        self:confirm(row[slot])
    end
end

-- Whether anything has been learned yet, which is what makes a puzzle worth keeping when the player switches
-- blueprint before grading a single row.
---@return boolean
function Prototyping:HasProgress()
    return self.confirmedCount > 0 or self.rejectedCount > 0
end

-- What the UI shows: the confirmed set by sprite, for marking board icons, and the two counts.
---@return table view { confirmed = sprite ID -> true, confirmedCount, rejectedCount }
function Prototyping:View()
    return { confirmed = self.confirmed, confirmedCount = self.confirmedCount, rejectedCount = self.rejectedCount }
end

return Prototyping
