# Rendering investigation — Paper 1.21.11 / SeasonalTitles 1.0.2

## Current fix: scoreboard suppression (1.0.2)

The reported `team-name-hidden` originates in `TitleRenderer.tick()` → `visibilityReason()` → `teamVisible()`. In 1.0.1 a false team result returned `team-name-hidden` before `showEntity()` could run. The display therefore existed and was attached but remained hidden by default: no client spawn/tracking, regardless of operator access.

`teamVisible()` reads the owner's entry in the **viewer's scoreboard**. `NEVER` suppresses the vanilla label; team-relative rules can also suppress it for a particular viewer. This is a real vanilla-label restriction, but does not establish player concealment. Custom nametag plugins commonly suppress that label to render a replacement. Bukkit records neither the writer nor its intent; the supplied plugin list cannot identify which plugin set it. That attribution requires inspecting the live team's owning plugin/configuration.

In 1.0.2 team name rules are informational by default. Actual invisibility, vanish/canSee, spectator, crouching, unsafe poses, range and observer line-of-sight gates remain unchanged. Servers deliberately using team rules for stealth can opt into `visibility.respect-team-nametag-visibility: true`; only other viewers are affected, with policy `scoreboard-observer-rule`. The owner's own equipped/preview title remains eligible in F5, including operators, subject to normal concealment and self-view configuration. Missing keys in existing config files default to false. No team, prefix, objective, attachment geometry or movement behavior is changed.

## Earlier confirmed causes (fixed in 1.0.1)

Version 1.0.0 used `(viewer != owner || preview)` before calling `showEntity`. Its TextDisplay was hidden by default. Equipping therefore saved the selection and created/attached the display, but **never sent a spawn packet to the owning client**. F5 cannot render an entity the client has not received. Operator access affected title selection, not this visibility exclusion. Only previews included the owner.

A separate blanket `if (!owner.getPassengers().isEmpty()) continue` prevented creation when another plugin had already attached an entity. The user's plugin list includes DecentHolograms, ProtocolLib, Skript and SeasonalUtilities, but the list alone does not establish which supplies the stats display. No specific plugin conflict is assumed.

## Full path and fix

1. `TitleCommands` validates the stable ID and centralized access policy. `Store.equip` persists the UUID/selection on the SQLite worker. `Database` publishes a snapshot before completing the request. “Saved.” confirms persistence; it is not evidence of client rendering.
2. The renderer's normal server tick chooses the preview or persisted selection, checks catalog/access and owner state, and creates at most one nonpersistent TextDisplay. Operators bypass unlocks but not concealment.
3. The display starts hidden by default, with nonempty Adventure text, unit scale, CENTER billboard, configured translation (default `0, 0.55, 0`), 32-block view range and depth-tested text. Zero interpolation/teleport duration is retained. No positional workaround was added.
4. `Player.addPassenger` attaches the display. It now appends alongside existing passengers, preserving their order; it never calls `eject`, replaces a passenger list, or modifies other entities. Paper 1.21.11's `Entity.getDefaultPassengerAttachmentPoint` uses the passenger index and clamped attachment points. Appending a display does not renumber existing passengers. Plugins that replace the whole passenger list at packet level still require integration.
5. `visibility.show-self` defaults to **true**, including for old config files without the new key. The normal title is eligible for its owner, just like a preview. An opt-out hides normal self-view but still permits a temporary preview.
6. Self-view retains invisibility, vanish metadata/hook, spectator, sneak/pose, `canSee`, default visibility gates. Scoreboard-name rules are informational by default; explicit enforcement applies only to observers. It skips eye-to-own-body ray tracing: that is not a meaningful F5 camera visibility test. Other viewers retain body and title-height line-of-sight checks. The client's depth test remains enabled for everyone.
7. `showEntity` makes Paper pair the TextDisplay with the permitted client. Paper's `ServerEntity.sendPairingData` includes spawn, metadata and a passenger packet for the vehicle. A mount packet received before the display spawn alone is insufficient; the regression verifies that another mount packet follows the spawn. `getTrackedBy` and `canSee(display)` provide server-side diagnostics, not proof of pixels on screen.
8. The official 1.21.11 client mappings/bytecode were inspected: `LevelRenderer.extractVisibleEntities` applies the first-person camera-entity exclusion to the camera entity itself, not all its passengers. `DisplayRenderer.submit` applies billboard orientation and the display transformation; `TextDisplayRenderer` uses text/opacity/flags. There is no general ban on rendering a local player's TextDisplay passenger. Normal entity view-range/frustum/text rules still apply.

Creative/permission-based flying uses STANDING pose and remains supported. Elytra flight, crawling/swimming, riding and crouching remain intentionally suppressed. Sneak, invisibility and spectator changes remove the title, then recreate it when permitted. Teleport cleanup remains event-driven. No per-tick teleport loop or delayed spawn retries were introduced.

The transform is applied in billboard space; the `offset` is not a replacement for a world-space teleport. This patch retains the existing geometry instead of guessing at new heights. Verify spacing from different camera angles and with resource packs. Packet checks cannot establish exact pixel placement or motion quality.

## Diagnostics

Run `/titles debug` while equipped, or `/titles debug <online-player>` as an administrator. Console can specify an online player; its report uses that player's self-view. The report includes:

- Persisted selection, active preview, catalog and access result.
- Owner pose, flying status and suppression reason.
- All passenger types/IDs, our display's validity and attachment.
- Translation, scale, billboard, unsigned opacity and see-through state.
- Scoreboard team/name rule, vanilla label visibility, informational/observer-opt-in mode, whether enforcement applies to this viewer, and unknown writer/intent. This is separate from title visibility.
- Viewer policy, requested visibility, `canSee(display)` and actual tracking.

This command is read-only and emits no background/per-tick logs. “visible” is the viewer policy only: selection/access, entity existence and tracking must also be checked. If it reports `tracked=true` and the F5 title still cannot be seen, capture this output with the camera angle and installed versions; investigate the client or packet-modifying plugins next. It is not evidence that the full plugin stack is visually compatible.

## Verification performed

- 35 automated tests pass, including self-view without preview, old-config defaults, custom-label team rules, explicit observer enforcement, self opt-out/preview behavior, concealment, body/title-height line-of-sight preservation, and administrator-only read-only diagnostics.
- Reproduced the reported `team-name-hidden` failure with the 1.0.1 JAR and a `NEVER` custom-label team on real Paper. With 1.0.2, the same fixture sends the owner spawn/attachment packets and reports `vanilla-name-visible=false`, `mode=informational`, `policy=visible`, `tracked=true`. Crouching/invisibility still remove the display while that team remains active.
- Reproduced the original failure on Paper 1.21.11 build 132 / LuckPerms 5.5.87 with **one** loopback protocol client: a passenger ID existed, but the owner received no corresponding TextDisplay spawn.
- The same client with 1.0.1 received spawn, nonempty title text, configured translation, valid/default unit scale, CENTER billboard, depth-test flags and a subsequent attachment packet. `/titles debug` reported the owner tracked.
- Verified creative flight retains the attachment; vanilla teleport succeeds/recreates it; crouching, invisibility, spectator and the external concealment hook remove it; standing/reveal restores it.
- A separate test-only plugin attached an unrelated marker armor stand and assigned a prefix team with `NAME_TAG_VISIBILITY=NEVER` plus below-name objective. The title coexisted; equip/unequip preserved the other passenger, its order, prefix, membership, name-visibility rule, objective and score.
- No interactive graphical client was run. F5 pixels, smoothness under real latency, and the user's actual 46-plugin combination remain unverified. The disposable server's Mojang public-key network warning is unrelated to these offline-client tests.

## Reproduce the protocol regression

Use an isolated Paper 1.21.11 server bound to `127.0.0.1:25585` in offline mode with an accepted EULA. Never expose an offline-mode test server publicly. Install LuckPerms and the production SeasonalTitles JAR. Build `./gradlew integrationFixtureJar` and install **only in that test server** `build/integration/seasonaltitles-rendering-fixture.jar`. It is excluded from the production JAR and normal `build`.

Install `minecraft-protocol@1.68.0` separately and run:

```sh
NODE_PATH=/path/to/node_modules JAVA_HOME=/path/to/jdk21 \
  ST_TEST_TITLE=2026-winter-legend \
  node scripts/render-regression.cjs /path/to/disposable-server
```

The script starts/stops the server, uses synthetic player `TitleOwner`, creates/clears the fixture, and writes packet evidence and a server log into the test directory. It modifies that test player's equipped selection, operator state and server test-world state. For the original 1.0.0 reproduction, use the old JAR and `--expect-hidden`; the fixture is not needed for that mode. For the 1.0.1 scoreboard reproduction, use its JAR, the current fixture and `--expect-team-hidden`. The normal 1.0.2 run includes a hidden vanilla-label team and checks title visibility plus concealment. Proxy JVM options in the script match the managed development environment and may be removed for a normal local network.
