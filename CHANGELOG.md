# Changelog

All notable changes to MajesticBot will be documented in this file.

## [2.0.0] - 2026-03-02

### ⚠️ Breaking Changes
- **Config restructured** - The `config.yml` format has changed. Delete your old `plugins/MajesticBot/config.yml` and restart the server to generate the new format, then re-enter your API credentials.

### Added
- **Server Start event** - sends a notification to Discord when the server finishes starting.
- **Server Stop event** - sends a notification to Discord when the server shuts down (sent synchronously to guarantee delivery).
- **Player Advancement event** - sends a notification when a player earns an advancement. Works on all server platforms (Paper, Spigot, CraftBukkit). The advancement display name is resolved automatically using the best method available for your server:
  - Paper/Folia → uses Adventure `displayName()` for full localized names
  - Spigot 1.19+ → uses Bukkit `getDisplay().getTitle()`
  - Older Spigot → falls back to a formatted version of the advancement key
- **Custom messages** - every event now supports a configurable message template with variable placeholders:
  - `%player_name%` - player's in-game name
  - `%player_uuid%` - player's UUID
  - `%message%` - chat message text (player_chat)
  - `%death_message%` - vanilla death message (player_death)
  - `%advancement%` - advancement display name (player_advancement)
  - `%server_name%` - configured server name
- **Discord mention sanitization** - player-controlled variable values are sanitized to prevent `@everyone`, `@here`, and `<@...>` injection from in-game chat.
- **Per-event configuration** - each event is now grouped with its own `enabled`, `color`, and `message` fields for clearer configuration.

### Changed
- **Config format** - events, colors, and messages are now grouped per-event instead of in separate top-level sections.

### Fixed
- **Webhook spam with default credentials** - The plugin no longer attempts to send HTTP requests when the API key or secret are still set to placeholder values (`YOUR_API_KEY_HERE` / `YOUR_API_SECRET_HERE`) or are empty.


## [1.0.2] - 2026-02-28

### Changed
- Updated default `api_url` in `config.yml` to the current production Majestic Bot endpoint (fixes webhook delivery after API changes or deployment updates).


## [1.0.1] - 2026-02-27

### Added
- **VersionChecker** - Automatic update checker that notifies server operators in-game and in console when a new version is available.

### Fixed
- **Reload command not working** - In v1.0.0 the `/majestic reload` command was registered but did not work; now fully implemented.


## [1.0.0] - 2026-02-26
- Player chat, join, leave, and death events.
- Configurable webhook endpoint with API key/secret authentication.
- Per-event hex color configuration.
- `/majestic reload` command.
- Multi-version builds (1.17.1 – 1.21.11) via GitHub Actions.