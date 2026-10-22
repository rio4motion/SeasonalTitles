# Verification

## Automated

Run `./gradlew clean build` using Java 21. Tests cover season/year ordering, invalid input, inherited LuckPerms groups and primary-group handling, exact stacked role tiers, role removal versus ownership, operator and custom access, permissions, command validation and completions, confirmation expiry, preview overlap/expiration/restoration, first verified purchases, pre-install enrollment, upgrades/downgrades/expiry, SQLite persistence, duplicate prevention, custom revoke/delete, durable offline work, stale season reads, injected award/transition failures, and serialized snapshot publication/shutdown.

A real Paper 1.21.11 build 132 with LuckPerms 5.5.87 was used for console smoke testing. See PROGRESS.md for final results. This tests API linkage and durable command/event handling, not visual rendering. The disposable server logged unrelated flat-world preset and Mojang public-key network errors; authenticated player login was not exercised. Two local offline protocol clients verified passenger spawn packets, successful vanilla teleport with an attached title, sneak-triggered removal and standing restoration, invisibility removal, and a preview that left the equipped database record unchanged. These are packet/API checks, not visual assertions. The optional `scripts/protocol-smoke.cjs` observer uses `minecraft-protocol` and `minecraft-data` installed separately; it is not part of the plugin or automated Gradle suite.

## Rendering regressions (1.0.1–1.0.2)

See [docs/RENDERING.md](docs/RENDERING.md) for the complete trace, one-client before/after evidence, diagnostics and optional live-server fixture. The owner now receives normal equipped titles; the old preview-only exclusion is removed. Thirty-five automated tests and live protocol checks passed. The 1.0.1 `team-name-hidden` failure was reproduced; 1.0.2 sends the owner the title under the same `NEVER` team rule, while crouching/invisibility still suppress it and the prefix/stats remain unchanged. The fixture is not part of the production JAR. Actual F5 appearance still needs the checklist below.

## Presentation regression (1.0.3)

Forty-one automated tests and the two-client Paper protocol regression pass. New checks cover palette overrides/fallback, legacy height clamping, configured tier/within-tier order after reload, permissions and prefix filtering, final Paper suggestion ranges/tooltips/order, observer updates, armor/movement, and equip/preview updates without entity recreation. Packet results do not verify the actual dropdown or visual spacing; use the checklist below.

## In-game release checklist

Use two real 1.21.11 clients, the production LuckPerms contexts, and the exact SeasonalFFA stats/vanish/nametag plugins. Observe both normal and elevated permissions.

- [ ] With only one client online, equip a seasonal and custom title; inspect in F5 while standing and creative flying. Run `/titles debug` and confirm selection, attachment, and self tracking. Then use a second observer when available; verify a separate line above the existing rank/name and stats remaining beneath it. Check `render.nametag-top`, `render.gap`, effective offset and scale with the actual custom nametag; test armor/helmets and multiple camera pitches. Verify long titles do not wrap.
- [ ] Tab-complete `/title equip`, `/titles equip`, `/title preview` and admin custom commands in a real 1.21.11 client: verify configured tier order, within-tier order, custom group last, prefix filtering and access filtering. Reload with reordered rank mappings and confirm the displayed dropdown changes without changing rank eligibility.
- [ ] Remove color overrides to test automatic palette; restore custom overrides and test custom-title styling. Verify equip and preview colors match, and changing titles updates without flicker or new entities.
- [ ] Walk, sprint, jump, fall, turn rapidly and test high latency. Look for trailing, jitter or incorrect passenger offsets from both clients.
- [ ] Crouch behind a wall and in the open: title must disappear immediately for everyone. Restore standing visibility. Confirm text is occluded by walls and cannot reveal a body hidden below cover.
- [ ] Swim, crawl, glide, sleep, spin attack and ride boats/minecarts: title must remain hidden, then restore after returning to a safe standing pose.
- [ ] Teleport through vanilla `/tp`, plugin teleports and portals; switch dimensions. Confirm the attachment does not block the teleport, and only one display returns.
- [ ] Test invisibility potion, `setInvisible`, vanish/unvanish and spectator: titles must disappear regardless of team rules or operator status. Packet-only visibility integrations need the documented suppression hook.
- [ ] With custom nametags active and a `NEVER` team rule, confirm titles appear while the vanilla label stays hidden. Debug must distinguish `vanilla-name-visible=false`, `applied=false` from `policy=visible` and `tracked=true`. Test every team relationship; repeat with observer-only enforcement explicitly enabled if the server uses teams for stealth. Self-view must remain eligible even in that mode.
- [ ] Kill/respawn, disconnect/rejoin, restart, unload/reload chunks; confirm no orphan or duplicated display.
- [ ] With the real stats plugin active, verify its display remains unchanged. If it uses player passengers, confirm both displays coexist and other passenger order remains unchanged; packet-only replacement requires integration.
- [ ] Preview in third person and from the observer; overlap must fail. At ten seconds restore prior selection, or no display if prior selection is absent/inaccessible. Disconnect during preview and verify it does not return.
- [ ] Remove a role during preview and after equip. Exclusive role access disappears; independently owned titles persist. De-op an operator and check the same behavior.
- [ ] Add Santa to an offline player, then join. Verify only the active season award. Test temporary expiry, inherited rank, primary-group differences, world/server contexts and purchases while stopped.
- [ ] Enroll an old donor; confirm no historical backfill. Activate a future-year season with confirmation; restart while many UUIDs are pending and verify exact-once awards.
- [ ] Test grants/revocations/deletion for an offline UUID and verify deletion confirmation removes associated rows safely.
- [ ] Load-test realistic player counts using spark. Watch tick cost of viewer pairs/ray traces, SQLite queue latency and ownership cache memory; confirm no unexpected entity packets during steady movement.

Do not deploy as visually verified until these checks pass. Keep a database backup and do not use `/reload` to deploy JAR updates.

## Command permission regression (1.0.4)

Run `./gradlew build integrationFixtureJar`. The normal suite parses the real plugin.yml through Bukkit's PluginCommandYamlParser and tests root `testPermissionSilent`, alias execution, subcommand completion, preview-only access, effective LuckPerms-style cached permission data, denial, and custom administrator gate updates. `scripts/permission-regression.cjs` additionally runs two-way commands against an isolated Paper 1.21.11/LuckPerms server using one **non-OP** protocol client. It requires the same loopback-only server setup and optional test fixture as the rendering script, with a config that has no diagnostics section yet. It verifies the actual LP API sees a global default-group use node, inherited membership, no direct user override, and an effective Bukkit grant. It changes synthetic player/title state and default-group test permissions; use only a disposable server.

Manual production check: with the affected non-OP user, test `/title list`, `/titles list`, both namespaced aliases, owned equip/unequip, and preview only when authorized. Confirm admin roots/subcommands are hidden/denied. Use the debug/trace instructions in README to distinguish an internal permission decision from CommandWhitelist or another external rejection. A correct result on the isolated server does not establish the production server's blocking source.

## 2.0 customization and per-viewer regression

Run `./gradlew build integrationFixtureJar` on Java 21. The new suite tests split-file migration and strict YAML failures, unchanged custom values/backups, legacy schema migration, definition editing and protected deletion, tier group rename/removal and historical equip after restart, multiple mappings/conflicts, independent persisted preferences, permission loss/regain/wildcards/static restrictions, styled gradients/effects/Unicode, and modern command UX. Earlier season/offline/recovery/access/visibility tests remain in the same suite.

For live checks, install the optional fixture only in the loopback offline test server described above, then run:

```sh
NODE_PATH=/path/to/node_modules JAVA_HOME=/path/to/jdk21 \
  node scripts/customization-regression.cjs /path/to/disposable-server
```

This uses two protocol clients and restarts that test server. It changes synthetic clients, definitions, mappings, permissions and world blocks. It compares static text exactly, samples animated metadata by entity/viewer, checks no recurring static text updates, verifies toggles and owner independence, previews, permission restoration, rank conflicts and persistence, and compares previous award timestamps/season across restart. Add `--crouch-visible` only when testing the uploaded copy's pre-existing crouch behavior. Both variants preserve their original geometry. Graphical assertions are not made.

Before production release, use real vanilla clients to check: each effect at the chosen cadence/speed; static gradient restoration when another viewer opts out; own display with other-title settings off; long styled/Unicode titles; visibility through actual vanish/nametag integrations; sneak/pose/LOS from multiple angles; original spacing and stats; teleport/world changes/death/rejoin; and final completion ordering. Profile a realistic number of animated players, especially mixed static/animated audiences. One fallback entity per animated owner can increase entity count; two-client packet measurements do not substitute for load profiling. Java's character segmentation may split complex emoji clusters.
