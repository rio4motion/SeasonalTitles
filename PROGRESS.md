# SeasonalTitles progress

## Presentation and completion update — 1.0.3 (2026-10-19)
- Upright VERTICAL billboard and effective offset `max(render.offset, render.nametag-top + render.gap)` (default 0.9) separate title from the normal name line, including old 0.55 configs. No team/prefix/stats writes, teleport loops or extra entities. Custom plugin name heights cannot be measured through Bukkit; configure their top if needed. Sneaking remains suppressed for stealth.
- Automatic case-insensitive colors for the seven existing tiers; explicit color overrides retained. Unknown labels/uncolored custom text fall back to white; custom MiniMessage formatting preserved. Shared equip/preview formatter unchanged.
- Configured ranks insertion order defines suggestion tier progression only. Optional `suggestions.title-order` sets within-tier order; remaining seasons are chronological, custom IDs alphabetical and last. Existing access/prefix filtering preserved, missing equip-use permission now checked in completion too.
- `TitleSuggestions` restores order at Paper's final suggestion event after Brigadier sorts it. Only reorders existing entries, preserving ranges/tooltips; async path uses immutable volatile snapshots. Official 1.21.11 client bytecode preserves matching-prefix order.
- 41 tests pass, build/JAR verified. Real Paper 1.21.11 build 132 / LuckPerms 5.5.87: two local protocol clients verified owner/observer updates, shared preview formatting, color metadata, raised offset/upright billboard, metadata-only equip/preview/restoration, no repeated metadata during walking/armor, creative flight/teleport, concealment, stats/prefix preservation, and ordered suggestion packets for aliases/namespaced commands with prefix filtering.
- Artifact: `build/libs/SeasonalTitles-1.0.3.jar`. No graphical client available: exact spacing/angles, full plugin stack and final displayed dropdown remain in-game checks (TESTING.md). GitHub blocker unchanged.

## Custom nametag visibility fix — 1.0.2 (2026-10-19)
- Exact cause: `TitleRenderer.tick` → `visibilityReason` → `teamVisible`; vanilla label suppression became `team-name-hidden` and prevented `showEntity` despite a valid attached display.
- Team name visibility is now informational by default, including old configs. Explicit `visibility.respect-team-nametag-visibility` opt-in affects observers only; self-view remains eligible. Existing invisibility, vanish/canSee, crouch/pose, spectator and LOS checks remain active. No changes to team/prefix/stats or attachment geometry.
- Debug separates scoreboard rule/vanilla-label status/configured enforcement from actual policy/tracking. Bukkit does not expose rule writer/intent; actual plugin attribution remains unverified.
- 35 automated tests pass. Real Paper 1.21.11 build 132 / LuckPerms 5.5.87: reproduced the failure with 1.0.1, then verified 1.0.2 owner spawn/tracking under the same NEVER team, concealment removal/restoration, stats passenger/prefix/team-rule/below-name preservation, teleport and creative flight.
- Main changes: `TitleRenderer`, `Settings`, `config.yml`, visibility regression tests, optional rendering fixture/script. Trace and evidence: `docs/RENDERING.md`.
- Artifact: `build/libs/SeasonalTitles-1.0.2.jar`. Actual F5 pixels, spacing/smoothness and the full production plugin stack still require the in-game checklist. GitHub creation remains blocked as recorded below.

## Earlier rendering fix — 1.0.1 (2026-10-19)
- Root cause reproduced on real Paper with one protocol client: equipped display attached, but `(viewer != owner || preview)` excluded its spawn from the owner's client. Operators did not bypass that filter.
- Fixed default self-view; `visibility.show-self` defaults true for old/new configs. Self-view skips meaningless eye-to-own-body LOS, retaining concealment/team gates. Creative flight remains supported.
- Removed blanket competing-passenger suppression; append only our display, preserving other entities/order. No teleport loops or arbitrary spawn delays.
- Added admin-only `/titles debug [player]`, unit regressions and a separate optional live-server rendering fixture (never included in production JAR).
- 30 tests passed. Before/after single-client packets verify spawn/text/transform/attachment; creative flight, teleport, sneak, invisibility, spectator and external concealment. Test stats passenger/prefix/below-name score survived equip/unequip.
- Inspected actual Paper tracking/attachment code and official 1.21.11 client render paths. No fundamental local-player passenger rendering ban found.
- Full trace/evidence: docs/RENDERING.md. Actual F5 pixel placement, latency smoothness and user's complete plugin stack remain manual checks. Build artifact: build/libs/SeasonalTitles-1.0.1.jar.

## Completed
- Local repository initialized at `/workspace/SeasonalTitles`; Java 21, Gradle 8.14.3 wrapper with distribution checksum; Paper 1.21.11 and required LuckPerms dependency.
- Permanent SQLite awards keyed by UUID/title, award timestamps, equipped selection, enrollment evidence, custom titles/grants, independent stacked role assignments and season audit.
- Serialized asynchronous database worker, coalesced snapshots/no-op suppression, transactional season state plus durable reconciliation queue, exact-once awards, stale-read rejection and retry backoff.
- LuckPerms inherited/context-aware rank resolution, live offline membership additions, rankless-to-eligible verification, explicit `/sti enroll` migration; no backfill.
- Player/admin commands, permissions, exact-action confirmations, completion, role/operator access, MiniMessage customization and 200-tick previews without durable writes.
- Attached TextDisplays; viewer-specific visibility checks; lifecycle cleanup; safe pose suppression; packet-only vanish integration hook. No scoreboard/prefix/stat writes.
- README, TESTING.md, commented config, plugin metadata and GitHub Actions build/artifact workflow.

## Verification
- `./gradlew clean build` and final incremental `./gradlew build` succeeded with Java 21. Final suite: **23 tests, 0 failures/errors/skips**.
- Tests: hierarchy/inheritance, role/operator/custom access, permissions/completions, confirmations, preview restoration, no backfill, offline eligibility, persistence/deduplication, injected transactional failures/recovery and worker shutdown/publication.
- Packaged JAR inspected for resolved metadata, SQLite driver/native library and absence of bundled Paper/LuckPerms/SLF4J API classes.
- Real Paper **1.21.11 build 132** / LuckPerms **5.5.87** smoke: startup and shutdown, offline Santa membership award, stacked roles, custom creation/grant, future-year transition/confirmation, backward rejection, reload, durable restart with no duplicate awards. Final eligibility-path change was smoke-tested again.
- Two loopback-only protocol clients: TextDisplay passenger packet, attached `/tp` success and recreation, sneak removal/standing restoration, invisibility removal, preview spawn and unchanged durable equipped selection. Visual rendering was NOT verified.
- Test-server warnings: flat preset configuration and Mojang public-key network access; no SeasonalTitles enable/runtime exception observed. Offline-mode clients do not verify authenticated login.
- Optional packet observer: `scripts/protocol-smoke.cjs`. Disposable server, databases, dependencies and logs stay in ignored `run/`.

## Decisions and limits
- Existing eligible users need explicit enrollment unless a qualifying membership-add event or a prior durable rankless observation provides current eligibility evidence.
- Offline verification uses static LuckPerms contexts; unobserved historical/expired purchases cannot be reconstructed. Queue processing verifies rank when processed, not a historical boundary snapshot.
- Active season is database state. Reload cannot roll it back. A transition waits for prior queued work to finish.
- Suppress nonstanding/crouched/riding players; append alongside existing passengers as of 1.0.1. Passenger positioning, actual motion smoothness, own-preview appearance and the real SeasonalFFA stats plugin still need the in-game checklist.
- Arbitrary packet-only hidden-player rules require the suppression hook. Eventless third-party visibility changes are checked every server tick; no perfect/zero-lag claim.
- Ownership is indexed in memory; large-server load testing remains necessary.
- No project license supplied; none invented.

## External blocker / next steps
- GitHub `gh repo create SeasonalTitles --private --add-readme --gitignore Gradle --clone` failed with **HTTP 403: Resource not accessible by integration** (`POST /user/repos`). Lookup confirmed `rio4motion/SeasonalTitles` does not exist.
- Implementation is committed locally. Repository creation/push and a hosted Actions run require a GitHub connection with repository-creation permission. No user action is needed to recover the local work.
- Complete TESTING.md with real clients and production plugins before treating rendering as production verified.
