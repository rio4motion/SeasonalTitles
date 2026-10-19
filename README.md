# SeasonalTitles

Permanent SeasonalFFA cosmetic collectibles for **Paper 1.21.11**, **Java 21**, and **LuckPerms 5.4+**. SQLite and its native libraries are included in the plugin JAR. No ProtocolLib is required.

Seasonal titles, individually granted custom titles, temporary role access, operator access, and previews are separate concepts. No rank prefixes, scoreboard teams, or below-name objectives are changed.

**Release status:** builds and automated tests pass; a real Paper/LuckPerms console smoke test was run. Rendering has not been visually verified with Minecraft clients or SeasonalFFA's stats plugin. Complete [TESTING.md](TESTING.md) before production deployment. Titles deliberately disappear during unsupported poses rather than using unverified offsets.

## Installation and build

1. Run `./gradlew clean build` with a Java 21 JDK.
2. Install LuckPerms, then copy `build/libs/SeasonalTitles-1.0.0.jar` to `plugins/`.
3. Before the first start, optionally place/edit `plugins/SeasonalTitles/config.yml`. The initial season defaults to **Winter 2026**.
4. Start Paper. Back up `plugins/SeasonalTitles/` before changes; stop the server for a simple consistent SQLite backup. A live backup must include SQLite WAL state or use SQLite's backup API.
5. Enroll existing qualifying players explicitly with `/sti enroll <player-or-uuid>`.

Use `/titles reload` for configuration. Bukkit `/reload` and third-party hot plugin loaders are not a supported deployment method; restart for JAR updates. Normal disable/enable closes the database, removes displays, and reloads durable state.

No project license has been selected because none was supplied. Bundled SQLite JDBC is Apache-2.0; its license resources are retained in the JAR. This repository does not grant a new license to the plugin source.

## Earning titles

| Effective LuckPerms group | Tier |
|---|---|
| speed | Bronze |
| spear | Silver |
| turtle | Gold |
| legend | Platinum |
| elf | Diamond |
| dove | Master |
| santa | Legend |

The highest effective inherited group wins, using LuckPerms query options and contexts. The primary group does not override a higher inherited rank. A stale primary-group string without effective membership does not grant eligibility. Mappings are configurable; temporary membership stops qualifying after expiry. Existing ownership never expires.

Eligibility is verified **in the active server season**, never inferred for earlier seasons:

- A live LuckPerms normal-data membership-add event for a rank-bearing group can enroll a new purchaser, including an offline player. The qualifying branch must contribute a mapped rank; adding an unrelated role does not enroll an existing donor.
- A recorded rankless observation followed by an eligible observation can enroll a player on login, including after a purchase during downtime.
- A player first observed already holding a rank requires `/sti enroll`. This includes old donors, previously unknown offline purchasers whose purchase was not observed, and unverified changes from remote servers. It is impossible to reconstruct purchase timing from current rank data alone.
- Enrollment grants only the currently verified highest tier for the active season. Upgrades add the new tier and preserve all previous awards. Downgrades never remove awards.
- Login, LuckPerms recalculation/mutation events, startup, seasonal transitions, and periodic known-player sweeps reconcile eligibility. Offline LuckPerms loads run asynchronously in bounded batches. Offline users use LuckPerms static contexts; world-specific eligibility can only be verified when those contexts apply.
- No seasons skipped while the plugin was offline are backfilled. Temporary ranks that both begin and expire while unobserved cannot be reconstructed.

Example: Santa verified in Spring 2027 earns `2027-spring-legend`; retaining Santa into Summer earns `2027-summer-legend`. Nothing grants Winter 2027 retroactively.

Seasonal IDs are `YEAR-season-tier`, e.g. `2027-winter-legend`. Custom IDs are `custom:founder`. Every activated season has all seven catalog entries, so operators and assigned roles can access those entries without an ownership row. Arbitrary never-activated historical seasons are not fabricated.

## Commands

`/title` is an alias of `/titles`. IDs, not colored display text, identify titles.

| Command | Purpose |
|---|---|
| `/titles list [page]` | Accessible titles, marked `[owned]` or `[access]`; ten per page |
| `/titles equip <title-id>` | Select one owned or currently accessible title |
| `/titles unequip` | Clear selection and any active preview |
| `/title preview <title-id>` | Preview any existing catalog title for 200 ticks (10 seconds at 20 TPS) |
| `/titles preview <season> <year>` | Read-only transition summary; enrolled count is not an award guarantee |
| `/titles update <season> <year>` | Request transition; repeat with `confirm` within 30 seconds |
| `/titles reload` | Validate and reload presentation, mapping, performance and permission settings |
| `/sti enroll <player-or-uuid>` | Explicitly verify/enroll an existing donor for current and future seasons |
| `/sti addrole <group> <tier>` | Add one exact tier to a role |
| `/sti removerole <group> <tier>` | Remove one assignment |
| `/sti roles` | List assignments |
| `/sti create <id> <display text>` | Create an independent MiniMessage custom title |
| `/sti delete <id>` | Show impact; repeat with `confirm` to delete title, grants and selections atomically |
| `/sti give <player-or-uuid> <id>` | Grant a custom title permanently |
| `/sti revoke <player-or-uuid> <id>` | Revoke a custom title grant |
| `/sti custom` | List custom titles |

Custom administration accepts `founder` or `custom:founder`; creation takes the bare ID. Names resolve through LuckPerms' cache without blocking Bukkit; supply a UUID if unknown. Examples:

```text
/sti addrole servermanager diamond
/sti addrole servermanager legend
/sti addrole servermanager bronze
/sti create founder <gold>★ <bold>Founder</bold>
/sti give PlayerName founder
/titles equip custom:founder
/title preview 2026-winter-legend
/titles update Spring 2027
/titles update Spring 2027 confirm
```

Role assignments stack independently: Diamond does **not** imply Gold, Silver or Bronze. Inherited role membership counts. A role never changes a LuckPerms group or grants a custom title. Removing a role or operator status immediately removes exclusive access on the next rendering check; independent ownership survives. A revoked but selected title remains stored as a dormant preference and is hidden unless another access source still applies.

Operators can equip all existing titles without permanently owning them. Preview requires `seasonaltitles.preview` (default op), bypasses normal equip access, rejects overlaps, and does not write ownership or equipment. On expiration the current durable selection is rendered again, subject to current access. If the player deliberately changes equipment during a preview, the new selection takes effect afterward. Disconnect clears previews. First-person Minecraft does not show your own nametag; use third person or a second observer to inspect a preview.

| Permission | Default | Scope |
|---|---|---|
| `seasonaltitles.use` | everyone | list/equip/unequip |
| `seasonaltitles.preview` | operators | any-title display preview |
| `seasonaltitles.admin` | operators | updates, seasonal preview, reload, all `/sti` commands |

The administrator permission node is configurable. Operators bypass the use and title-preview command permission checks; administrator actions still use the configured administrator permission.

## Seasonal state and durability

Server seasons use **Winter → Spring → Summer → Autumn** within each numbered year. Years do not depend on the real calendar. Spring 2027 may follow Winter 2026; Winter 2028 may follow Autumn 2027. Duplicate and backward transitions are rejected. Tab completion offers the other three seasons and years from the active year through ten years ahead; any valid future year through 9999 can be typed.

SQLite `titles.db` is authoritative after initialization. Reloading `initial-season` cannot roll back the active season. Do not edit the database while the plugin is running.

A seasonal transition atomically creates catalog entries, changes active state, records actor/time, and queues enrolled UUIDs. The durable queue resumes after a crash. Each award and queue removal commit together; uniqueness on `(uuid,title)` prevents duplicates. Rank reads tied to a superseded season cannot award a newer season. Further transitions wait until the current queue is processed. If LuckPerms is unavailable, work remains pending for retry. Rank eligibility is determined when each queued player is processed, not from a historical boundary snapshot.

SQLite work runs on one worker thread with WAL, full synchronous durability, foreign keys, coalesced immutable snapshots and no-op suppression. Bukkit calls stay on the server thread. All title metadata, award timestamps, enrollment evidence, role assignments, custom grants and selections survive restart. The complete ownership index is cached in memory; very large installations should load-test memory and reconciliation costs.

## Rendering and compatibility

The renderer uses **one nonpersistent TextDisplay passenger per displayed player**. The Minecraft client attaches movement through its passenger graph; there is no repeating teleport loop. Translation is relative to the player's attachment point. Text is depth-tested (`seeThrough=false`), billboarded, and hidden by default until explicitly shown to a permitted viewer. Existing scoreboard teams are read only.

- Default range: 32 blocks. Viewer checks run every tick; only visibility changes send show/hide calls. Text components are cached and metadata is changed only when text changes.
- Visibility checks include `Player.canSee`, the viewer's scoreboard name visibility, invisibility, spectator mode, `vanished` metadata, world/range, body line of sight and a ray trace toward title height. Hide-entity events immediately hide the associated display for that viewer.
- Titles are **suppressed while crouching, swimming/crawling, gliding, sleeping, spin attacking, riding, or otherwise nonstanding**. This is deliberate conservative behavior, not verified pose-aware positioning. Sneak/pose/potion/game-mode events remove displays early.
- Displays are removed around teleport, death, respawn, world changes, disconnect and shutdown. Passenger recreation after teleport can briefly hide a title. Tagged stale entities are removed on world/chunk load; displays are nonpersistent.
- If a player already has another passenger, SeasonalTitles does not add a display. This prevents silently competing with an attachment-based stats/nametag plugin. Verify SeasonalFFA's stats implementation before deployment.
- Packet-only vanish and packet-only scoreboard plugins may hide information without changing Bukkit state. Such integrations must call `SeasonalTitlesPlugin#setTitleHidden(uuid, true)` **on the main thread before hiding the player**, then `false` when revealed. Arbitrary packet-only visibility cannot be inferred reliably. Third-party visibility changes without events can take up to one server tick to be reflected.

An invisible marker armor stand would still need viewer visibility and attachment validation, with additional hitbox/equipment concerns. Packet-only virtual passengers offer per-viewer control but require version-sensitive packet metadata and safe merging of other plugins' passenger lists. Neither is assumed to solve attachment offsets automatically. This implementation uses Paper's attachment API with conservative suppression; visual smoothness, exact spacing, own-preview behavior, portals and stats compatibility remain release gates. No zero-lag or perfect-visibility claim is made.

## Configuration

See the commented [config.yml](src/main/resources/config.yml). It includes first-run season/year, rank mappings, MiniMessage format, tier colors, seasonal symbols, attachment offset/scale/range, optional additional line-of-sight checking, reconciliation batch size/interval, message templates and admin permission. Critical invisibility/sneak checks and the one-tick visibility interval cannot be disabled. Formatting uses plain-text substitutions for season, year, tier and symbol. Custom display text supports MiniMessage styles and symbols, but must render as one nonempty line.

Reload validates new settings before swapping the runtime settings and recreates displays for new offsets. Rank changes in configuration affect future reconciliation, never erase ownership or infer historical purchases.

## Repository status

GitHub creation was attempted for `rio4motion/SeasonalTitles`, but the connected integration returned **HTTP 403: Resource not accessible by integration** for `POST /user/repos`. Work and commits are preserved locally. After granting repository-creation access, create the repository and push this Git history. GitHub Actions is provided, but no hosted Actions run has occurred.
