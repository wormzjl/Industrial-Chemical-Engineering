# A press that reaches the server

Read this when a key must change something on the server. First ask what the press changes: that picks the route.
`AGENTS.md` (engine schedule) rules the first route: player inputs to the process are queued engine events. The other
two touch no process state, so they force no process update and run at once.

## The three routes

| The press changes | Route | Answer | Done by |
| --- | --- | --- | --- |
| The process: controls, a solve, a check that reads plant state | Queued for the engine's bucket (`PresentationMailbox`, `PERIOD` 100 online ticks, about 5 s) | With the next bucket's snapshot, or a rejection | `ColumnV3Network`, `AssemblyNetwork`, `FluidNetwork.edit` |
| A block's own design: nothing solves | A payload handled at once on the main thread | At once, on every path, when the screen waits | `EquipmentNetwork` |
| The settings of the item in the player's hand | A payload handled at once, gated by the open menu | None: the screen closes after Save | `GaugeSave`, `PipelineToolSave` in `FluidNetwork` |

A packet handler never forces a process update, and neither does opening a menu.

## The queued route

- Check at receipt, in the handler: the menu is open on that block and `stillValid`, the player may build, the payload
  fits the block. Reject here. `ColumnPresentation.queue` and `FluidNetwork.edit` show the form.
- Once accepted, a command runs on its bucket even if the screen closed first (owner 2026-10-28, "Run it after
  close"). Closing ends the subscription, not the input. Its action does not check the screen again, and nothing may
  bring back drop-on-close in `PresentationMailbox`.
- A session holds at most one unacknowledged command. A screen with no answer yet says it waits.
- The fluid route sends no reply from the handler: it queues a ledger event, and the engine composes the reply with
  the menu's next bucket.
- The screen must not say something is done before its bucket. "Saved: the constructor imports this design" appears
  only on the answer.

## The design route

Worked example: the constructor's template (`TemplatePayload`, `EquipmentNetwork.template`, `sendTemplate`).

1. Payload: a record with `TYPE` and `STREAM_CODEC`. Bound its strings (`MAX_NAME`) and lists in the codec.
2. Register it: `registrar.playToServer(TYPE, STREAM_CODEC, EquipmentNetwork::template)`.
3. Bump the version of the registrar it joins, and name the number in the class comment. `EquipmentNetwork.PROTOCOL`
   and `FluidNetwork.PROTOCOL` are constants; each requested display and item payload in `FluidNetwork` has its own
   registrar with a literal version (`registerDisplays`), which `FluidNetwork.PROTOCOL` does not change.
   `FluidPacketCodecTest` pins `FluidNetwork.PROTOCOL`. Wire formats get no compatibility work (`AGENTS.md`).
4. Handler: find the block with `editable(player, position)`. It is private to `EquipmentNetwork`. A copy keeps all its
   checks: loaded, the right block, within `REACH`, `mayBuild`, not a spectator, `level.mayInteract`.
5. Trust nothing the client built. It sends the input; the server makes the result (`ColumnDesign.of`), as the
   calculator's Save design does. `keepingColumn` never takes a column from `ConstructorPayload`.
6. Catch the refusal and answer with its reason: `IllegalArgumentException` becomes `template_refused`.
7. Answer on every path, because the screen is waiting: `answer(player, at, message, design)`, also when `editable`
   returned null. `ImportAnswer` and `answered` are the constructor's own; another screen needs its own pair.
8. Client: `sendTemplate` cuts the name to `MAX_NAME` and sends. When the server must see the draft first, send the
   draft (`Action.KEEP`), then the action; the two arrive in order.
9. Screen: set a waiting flag (`importing`), clear the last refusal, go to the page that shows the result.
   `answered(...)` clears the flag, then takes the design, or shows the refusal on the message display. A dialog has
   no message display: its warning line, the column's last row, says it, and the panel is sized for the longest line
   that row may hold.
10. Messages sit under `message.icheme.*` in `en_us.json`.
11. Test the round trip (`EquipmentNetworkCodecTest`). Nothing there round-trips `ImportAnswer` yet, so a `Component`
    in a payload has no pattern to copy.

## The held-item route

Worked examples: `GaugeSave` and `PipelineToolSave` in `FluidNetwork.registerDisplays`. There is no block, so reach
means nothing.

- Gate on the open menu: `player.containerMenu instanceof GaugeMenu menu && menu.containerId == p.menu && menu.stillValid(player)`.
- Change the stack through the menu (`menu.save`), then call `player.inventoryMenu.broadcastChanges()`.
- `GaugeMenu.stillValid` compares the stack with its final `initial`, so any change to the stack ends the menu, and
  the game closes it on the next server tick. This is harmless today only because `GaugeScreen` closes right after
  Save. A key that changes the stack and keeps the dialog open must update `initial` in the same step. No unit test
  catches this.
- When the item leaves the hand, `stillValid` fails and the game closes the dialog, so the dialog cannot say why. If
  the player must read a reason, it needs chat or a change to the menu: ask the owner.

## Saved data

If the action adds a field to a saved design or shape, it is a fact: append-only, with a default equal to the old
behaviour, and a corpus image under `src/test/resources/compat/` (`AGENTS.md`, saves and data formats). Read that
section before you change `EquipmentConstructorBlockEntity.Design` or anything it writes.
