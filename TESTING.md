# Verification

## Automated

Run `./gradlew clean build` using Java 21. Tests cover season/year ordering, invalid input, inherited LuckPerms groups and primary-group handling, exact stacked role tiers, role removal versus ownership, operator and custom access, permissions, command validation and completions, confirmation expiry, preview overlap/expiration/restoration, first verified purchases, pre-install enrollment, upgrades/downgrades/expiry, SQLite persistence, duplicate prevention, custom revoke/delete, durable offline work, stale season reads, injected award/transition failures, and serialized snapshot publication/shutdown.

A real Paper 1.21.11 build 132 with LuckPerms 5.5.87 was used for console smoke testing. See PROGRESS.md for final results. This tests API linkage and durable command/event handling, not visual rendering. The disposable server logged unrelated flat-world preset and Mojang public-key network errors; authenticated player login was not exercised. Two local offline protocol clients verified passenger spawn packets, successful vanilla teleport with an attached title, sneak-triggered removal and standing restoration, invisibility removal, and a preview that left the equipped database record unchanged. These are packet/API checks, not visual assertions. The optional `scripts/protocol-smoke.cjs` observer uses `minecraft-protocol` and `minecraft-data` installed separately; it is not part of the plugin or automated Gradle suite.

## Self-view regression (1.0.1)

See [docs/RENDERING.md](docs/RENDERING.md) for the complete trace, one-client before/after evidence, diagnostics and optional live-server fixture. The owner now receives normal equipped titles; the old preview-only exclusion is removed. Thirty automated tests and live protocol checks passed. The fixture is not part of the production JAR. Actual F5 appearance still needs the checklist below.

## In-game release checklist

Use two real 1.21.11 clients, the production LuckPerms contexts, and the exact SeasonalFFA stats/vanish/nametag plugins. Observe both normal and elevated permissions.

- [ ] With only one client online, equip a seasonal and custom title; inspect in F5 while standing and creative flying. Run `/titles debug` and confirm selection, attachment, and self tracking. Then use a second observer when available; verify a separate line above the existing rank/name and stats remaining beneath it. Adjust `render.offset` and scale. Verify long titles do not wrap.
- [ ] Walk, sprint, jump, fall, turn rapidly and test high latency. Look for trailing, jitter or incorrect passenger offsets from both clients.
- [ ] Crouch behind a wall and in the open: title must disappear immediately for everyone. Restore standing visibility. Confirm text is occluded by walls and cannot reveal a body hidden below cover.
- [ ] Swim, crawl, glide, sleep, spin attack and ride boats/minecarts: title must remain hidden, then restore after returning to a safe standing pose.
- [ ] Teleport through vanilla `/tp`, plugin teleports and portals; switch dimensions. Confirm the attachment does not block the teleport, and only one display returns.
- [ ] Test invisibility potion, `setInvisible`, vanish/unvanish, spectator, and each scoreboard name-visibility setting. Check both team relationships. Packet-only visibility integrations need the documented suppression hook.
- [ ] Kill/respawn, disconnect/rejoin, restart, unload/reload chunks; confirm no orphan or duplicated display.
- [ ] With the real stats plugin active, verify its display remains unchanged. If it uses player passengers, confirm both displays coexist and other passenger order remains unchanged; packet-only replacement requires integration.
- [ ] Preview in third person and from the observer; overlap must fail. At ten seconds restore prior selection, or no display if prior selection is absent/inaccessible. Disconnect during preview and verify it does not return.
- [ ] Remove a role during preview and after equip. Exclusive role access disappears; independently owned titles persist. De-op an operator and check the same behavior.
- [ ] Add Santa to an offline player, then join. Verify only the active season award. Test temporary expiry, inherited rank, primary-group differences, world/server contexts and purchases while stopped.
- [ ] Enroll an old donor; confirm no historical backfill. Activate a future-year season with confirmation; restart while many UUIDs are pending and verify exact-once awards.
- [ ] Test grants/revocations/deletion for an offline UUID and verify deletion confirmation removes associated rows safely.
- [ ] Load-test realistic player counts using spark. Watch tick cost of viewer pairs/ray traces, SQLite queue latency and ownership cache memory; confirm no unexpected entity packets during steady movement.

Do not deploy as visually verified until these checks pass. Keep a database backup and do not use `/reload` to deploy JAR updates.
