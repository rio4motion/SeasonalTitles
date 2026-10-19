# SeasonalTitles progress

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
- Suppress nonstanding/crouched/riding players and competing player passengers. Passenger positioning, actual motion smoothness, own-preview appearance and the real SeasonalFFA stats plugin still need the in-game checklist.
- Arbitrary packet-only hidden-player rules require the suppression hook. Eventless third-party visibility changes are checked every server tick; no perfect/zero-lag claim.
- Ownership is indexed in memory; large-server load testing remains necessary.
- No project license supplied; none invented.

## External blocker / next steps
- GitHub `gh repo create SeasonalTitles --private --add-readme --gitignore Gradle --clone` failed with **HTTP 403: Resource not accessible by integration** (`POST /user/repos`). Lookup confirmed `rio4motion/SeasonalTitles` does not exist.
- Implementation is committed locally. Repository creation/push and a hosted Actions run require a GitHub connection with repository-creation permission. No user action is needed to recover the local work.
- Complete TESTING.md with real clients and production plugins before treating rendering as production verified.
