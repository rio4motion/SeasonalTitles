# SeasonalTitles 2.1

Permanent SeasonalFFA title collectibles for **Paper 1.21.11 / Java 21**, with required LuckPerms integration. Seasonal awards, role access, operator access, custom grants and previews remain separate. No rank prefixes, scoreboard teams, below-name objectives or unrelated plugin data are modified.

## Install and upgrade

Build `./gradlew build` on Java 21, install `build/libs/SeasonalTitles-2.1.0.jar` alongside LuckPerms, and restart. No client mod, resource pack or ProtocolLib is required. Keep the existing `plugins/SeasonalTitles` folder and `titles.db`.

On upgrade, missing split files are created from existing values. Legacy `ranks` and color overrides migrate to `tiers.yml`; customized messages migrate to `messages.yml`. The original config stays unchanged and receives a one-time `config.yml.pre-2.0.bak` backup. Existing split files take precedence. The old 10-tick animation default migrates once to 1 tick, with `animations.yml.pre-2.1.bak`; custom speeds, palettes and other values are retained. Other intervals and later administrator changes are respected. Missing message/effect defaults are filled in memory. Invalid YAML/settings reject reload while the current settings stay active.

SQLite schema 3 adds permanent animation unlocks and single-use voucher records alongside preferences; awards, timestamps, UUIDs, equipment and active season remain intact. Existing database custom titles are exported into missing definitions in `titles.yml`. Removing a YAML definition does not silently erase ownership: use confirmed administrator deletion. Definition edits update text under the same ID and preserve grants. Keep a database backup when upgrading/downgrading schema versions.

The repository also contains the separately uploaded `Downloads/SeasonalTitles-main/SeasonalTitles-main` project. Both receive this update. That copy retains its prior billboard/crouching edits and 1000-completion limit. Build it to retain those edits; the repository-root renderer retains its own original safety behavior. **This upgrade does not change either copy's spacing, offset, nametag-top, gap, scale, or billboard.**

## Configuration

| File | Purpose |
| --- | --- |
| `config.yml` | First-run season, seasonal format/symbols, advanced rendering, visibility, reconciliation, admin node, diagnostics |
| `tiers.yml` | Stable tier IDs, current LuckPerms group names, tier colors and optional per-tier format |
| `titles.yml` | Custom display definitions, default effects and restrictions |
| `animations.yml` | Animation cadence, presets, speed/colors, enable/disable rules |
| `messages.yml` | Common player feedback in MiniMessage |

The **active season is always SQLite state**. `initial-season` is only for an empty database; reload and rank/config migration cannot move it backward or reset it.

```yaml
# tiers.yml — keep stable IDs; rename groups whenever the server's ranks change.
tiers:
  bronze:
    groups: [speed, winter_speed]
    color: '#CD7F32'
  legend:
    groups: [santa, summer_hero]
    color: '#FF5555'
    # Optional; otherwise uses config.yml's global format.
    format: '<color><symbol> <season> <year> • <tier></color>'
```

The seven existing stable IDs are `bronze`, `silver`, `gold`, `platinum`, `diamond`, `master`, `legend`, in their existing eligibility progression. Existing database/title IDs do not change. Removing/renaming groups or tier mappings never deletes historical seasonal definitions, awards or equipped selections. Owned seasonal titles remain equippable even when the original groups no longer exist. A tier may have zero or multiple groups. Effective inherited LuckPerms groups determine the highest tier; a stale primary-group name is not purchase evidence. A group mapped to different tiers is rejected. Unknown group names produce warnings and may be configured before the groups are created.

Suggestion ordering follows configured tier order and optional `config.yml` → `suggestions.title-order`; remaining titles in each tier are chronological, with custom titles after ranked ones alphabetically. Paper's final suggestion event preserves this ordering after Brigadier's sorting. Client prefix filtering and access filtering remain enabled.

```yaml
# titles.yml
custom-titles:
  beta_supporter:
    display: '<gradient:#00AAFF:#AA00FF><bold>✦ Beta Supporter</bold></gradient>'
    default-animation: static
    # Optional: restrict equipment even for an owner; operators bypass this node.
    permission: seasonaltitles.custom.beta
    # Optional: only these effects are allowed for this title. Static is always allowed.
    allowed-animations: [shimmer, pulse]
```

Custom titles support MiniMessage named/HEX colors, gradients, bold, italic, underline, strikethrough and Unicode. Seasonal placeholders are unparsed values; ordinary players cannot submit custom MiniMessage definitions. Explicit colors/templates are retained. Uncolored custom text uses white. Equipment and previews share the same formatter. Title preview permission deliberately allows viewing any existing title without ownership.

```yaml
# animations.yml
animations:
  enabled: true
  default: static
  interval-ticks: 1
  timing-version: 2 # prevents repeating the legacy 10 → 1 migration
  visibility-distance: 32
  static-only-titles: [] # e.g. [2026-winter-legend, custom:beta_supporter]
  effects:
    gradient:
      enabled: true
      speed: 1
      colors: ['#55FFFF', '#AA55FF']
```

Effects: `static`, `rainbow`, `gradient` (smooth left-to-right flow), `pulse`, `shimmer`, `sparkle`, `fire`, `glitch`, `flicker`, `typewriter`. Speed is >0–10 and uses elapsed server ticks (speed 1 is a six-second cycle), independent of update cadence; palettes accept named/HEX colors. Fire uses continuous warm color waves, pulse breathes gently, shimmer/sparkle brighten without harsh jumps, legacy glitch is a readable neon scan without character corruption, flicker is a gentle glow, and typewriter reveals then holds the full title. Presets are included. Static is the default, including migrated users. A `default` player selection uses the title's default effect, or the global seasonal default, subject to permission/restrictions. Explicit `static` always stays static.

## Player commands

`/titles` is the main entry point. `/title`, `/seasonaltitles:title` and `/seasonaltitles:titles` remain aliases.

| Command | Behavior |
| --- | --- |
| `/titles` or `/titles list [page]` | Browse formatted titles with owned/not-owned, available/locked and equipped status. Internal IDs are hidden from labels; click an entry to prepare equip or preview |
| `/titles equip <id>` | Equip an owned or otherwise authorized title |
| `/titles unequip` | Clear equipment |
| `/titles preview <id>` | Ten-second preview, then restore current equipment; no ownership/equipment writes |
| `/titles animation [effect]` | Show unlocks/current preference, or select an unlocked effect (`default` resets to configured behavior) |
| `/titles animations on\|off` | Animate other players' titles, or show their original static formatting |
| `/titles on` / `/titles off` | Show/hide other players' titles entirely |
| `/titles vouchers` | Collect pending voucher deliveries after freeing inventory space |
| `/titles help` | Contextual command help |

Preferences persist independently of title ownership. Without a permanent voucher unlock, losing an animation permission renders static without deleting the selection; regaining it restores the effect. One viewer's settings cannot change another viewer's display. Your own animation is unaffected by your *other-player* viewing preferences. `visibility.show-self` remains configurable; previews still include their owner. Overlapping previews are rejected and disconnect clears them.

## Administrator commands

| Command | Behavior |
| --- | --- |
| `/titles admin create <id> [MiniMessage display]` | Create a definition; omitted text uses the ID as a readable label |
| `/titles admin preview <id>` | Preview using administrator access |
| `/titles admin edit <id> [MiniMessage display]` | Change text without losing grants; with no text, show editing guidance |
| `/titles admin delete <id> [confirm]` | Confirmed deletion of definition and associated grants/equipment |
| `/titles admin give <player-or-uuid> <custom-id>` | Permanent custom grant |
| `/titles admin revoke <player-or-uuid> <custom-id> [confirm]` | Confirmed ownership revocation; other owners/definition are preserved |
| `/titles admin voucher <effect> [online-player]` | Issue one tradable animation voucher; omit player to give it to yourself |
| `/titles admin rank set\|add\|remove <tier> <group>` | Atomically persist a tier's group mapping; set replaces its group list |
| `/titles admin rank list` | List current mappings |
| `/titles admin season` | Show active season |
| `/titles admin season preview\|update <season> <year> [confirm]` | Preview/confirm a forward seasonal transition |
| `/titles admin reload` | Validate and apply config/definitions safely |
| `/titles debug [online-player]` | Rendering and command registration/effective-permission diagnostics |

Legacy `/sti create|delete|give|revoke|custom|addrole|removerole|roles|enroll|voucher`, `/titles update`, seasonal `/titles preview <season> <year>`, and `/titles reload` remain supported. Role tiers stack independently: `/sti addrole servermanager diamond` grants exactly Diamond access, not lower tiers. Use `/sti enroll <player-or-uuid>` for pre-install donors whose eligibility was not verified through a new purchase event. Role removal preserves independently owned titles.

Custom definitions/default animations/restrictions can also be edited in `titles.yml`, then reloaded. Configuration writes use atomic replacement; definition synchronization is serialized through the database worker. If a database synchronization fails after a config write, fix the reported error and reload; existing ownership is retained.

## Physical animation vouchers

Give yourself a voucher with `/titles admin voucher rainbow`, `/titles admin voucher fire` or `/titles admin voucher gradient`. Add an online player's name to deliver directly. Each command creates one unique paper voucher; trade it normally, hold it in the main hand and right-click to permanently unlock that effect. Then select it with `/titles animation <effect>`. Unlocks are independent of title ownership and do not change LuckPerms groups or permissions.

Static is already free, so it has no voucher. Redemption does not automatically change the selected animation. A permanent unlock remains available when ranks/permissions change, including an LP denial: it is a separate entitlement. Global effect switches, per-title allowlists and static-only restrictions still apply. A player who already permanently unlocked an effect keeps the unspent voucher to trade. Disabled effects cannot be newly issued; existing vouchers keep their entitlement if redeemed while disabled, but the effect remains unusable until enabled.

Tokens and their effects are registered in SQLite. Renaming paper cannot make a valid voucher, changing its effect invalidates it, and copying an issued item cannot redeem the token twice. Unlock and spent-token status commit in one transaction **before** the item is consumed. Moving/dropping/disconnecting during that short operation can leave a spent physical copy, which grants nothing further. Failed redemption keeps the item. Full-inventory deliveries remain pending for `/titles vouchers` or the next login; pending deliveries and unlocks survive restart. As with other Minecraft item systems, player-inventory saving and SQLite are not one atomic transaction across a hard process crash; a recovered delivery may repeat the same token, never mint a second entitlement. Back up the database and world/player inventories together.

## Permissions and awards

- `seasonaltitles.use` — basic management and viewing preferences; default true.
- `seasonaltitles.preview` — title previews; default operator.
- `seasonaltitles.admin` — administrative actions; configurable in `config.yml`.
- `seasonaltitles.animation.<effect>` — unlock an enabled effect; non-static effects default false.
- `seasonaltitles.animation.*` — unlock all enabled effects; default operator.
- Static is always available. Operators can equip all existing titles without permanent grants; configured animation disable/allowlist rules still apply.

The shared root accepts use **or** preview **or** configured admin permission. Subcommands retain their individual checks; `/sti` is admin-only. Bukkit resolves LuckPerms inheritance and active contexts. Root gates and client command trees refresh when the admin node is reloaded.

Season names are Winter → Spring → Summer → Autumn. Years are server-controlled, independent of today's date; future-year transitions are allowed. Duplicate/backward updates are rejected and forward updates require confirmation. Seasonal awards are permanent and idempotent, keyed by UUID/season/year/tier. Verified purchases, upgrades, inherited/temporary ranks, joins, offline LP changes, restart and reconciliation remain supported. No historical seasons are inferred from a player's current rank. Offline verification uses static contexts and verifies rank when queued work is processed; unobserved past purchases cannot be reconstructed.

## Rendering and performance

The existing TextDisplay passenger geometry is retained, including other plugins' passenger order, rank prefix and below-name stats. Static titles use one nonpersistent display. Animated titles use that display; **one additional static display is created only while a viewer needs a different static version** (animations off or outside animation distance). Each viewer is shown only its permitted variant using Paper's native entity tracking. No mandatory packet library is needed. Removed/disconnected entities and tagged leftovers are cleaned up.

One shared animation task updates active animated displays at the configured interval; unchanged frames are skipped. The default 1-tick interval samples at up to 20 updates per second at 20 TPS. Raising it to 10 samples twice per second and looks stepped; elapsed-time speed is unchanged. One-tick animation can increase CPU and metadata traffic, so profile your player count. Per-frame work uses immutable title/preference snapshots, no database operations, and bounded component/glyph processing. Static text is not repeatedly sent. The separate safety task still checks every tick: invisibility, vanish/`canSee`, spectator, owner state, range, team opt-in and observer body/title line-of-sight. Per-viewer settings add restrictions, never bypass these checks. No per-tick teleport loop is added.

Team name visibility remains informational by default because custom nametag plugins may hide the vanilla label. Optional `visibility.respect-team-nametag-visibility` applies to observers only. Packet-only vanish integrations must call `SeasonalTitlesPlugin#setTitleHidden(uuid, true)` on the server thread before concealment, then false on reveal.

Two-client Paper packet checks verify original static formatting and absence of animated text metadata at an animation-disabled client, plus unchanged static text. These measurements are specific to the tested setup; passenger/visibility packets and the extra entity still have costs. Real graphical smoothness, final dropdown order, custom nametag spacing/resource packs, the full SeasonalFFA plugin stack and realistic player-count profiling remain in-game checks. Typewriter uses Java character segmentation, which may split some complex emoji sequences; surrogate/codepoint integrity is preserved. Extremely large/deep component content beyond the processing budget stays unchanged.

For a production command denial, run `/titles debug <affected-player>` as OP. Temporarily enable `diagnostics.command-trace`, reload, and retry: the console logs root ownership/permission, rewrites and preprocess cancellation without command arguments. A cancellation stops the executor even when LuckPerms grants use. The supplied plugin list includes CommandWhitelist; its actual rules must be checked separately. Diagnostics do not bypass it.

See [TESTING.md](TESTING.md), [docs/RENDERING.md](docs/RENDERING.md) and [PROGRESS.md](PROGRESS.md). No license was supplied; none is invented.
