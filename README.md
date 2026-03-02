![Minecraft](https://img.shields.io/badge/Minecraft-1.17.1_--_1.21.11-brightgreen)
![Java](https://img.shields.io/badge/Java-17%2F21-orange)
![Releases](https://img.shields.io/github/v/release/Braandn/Majestic-Bot-Minecraft-Integration)
![License](https://img.shields.io/github/license/Braandn/Majestic-Bot-Minecraft-Integration)

# MajesticBot — Minecraft Discord Integration Plugin

MajesticBot is a lightweight Bukkit/Spigot/Paper plugin that integrates your Minecraft server with Discord by sending real-time server and player events to a configured webhook endpoint.
It uses asynchronous HTTP requests to ensure minimal impact on server performance.
The plugin is designed for easy setup and full customization, with support for multiple Minecraft versions via GitHub Actions builds.

## Features

- **Server Events**: Server start and stop notifications sent to Discord.
- **Player Events**: Captures and forwards the following player events:
  - Player chat messages
  - Player joins
  - Player leaves
  - Player deaths (including death messages)
  - Player advancements
- **Custom Messages**: Fully configurable message templates for every event with variable placeholders (see [Variables](#variables) below).
- **Configurable Webhook**: Send events to a custom API endpoint with API key and secret authentication.
- **Custom Colors**: Define Discord embed colors for each event type in the config.
- **Server Naming**: Include a custom server name in event payloads for multi-server setups.
- **Reload Command**: Use `/majestic reload` to reload the configuration without restarting the server.
  **Required permission**: `majestic.reload`
- **Asynchronous Sending**: Player events are sent in a separate thread to avoid blocking the main server thread.
- **Multi-Version Support**: Built for Minecraft versions 1.17.1 to 1.21.11 using Java 17/21.
- **Lightweight**: Minimal dependencies; relies on Bukkit/Paper API only.

## Advancement Display Names

The plugin automatically resolves human-readable advancement names using the best method available for your server:

| Server Platform | Resolution Method | Example Output |
|---|---|---|
| Paper / Folia | Adventure `displayName()` | "Diamonds!" |
| Spigot 1.19+ | Bukkit `getDisplay().getTitle()` | "Diamonds!" |
| Spigot 1.17–1.18 | Formatted key fallback | "Mine Diamond" |

Recipe unlocks and hidden advancements are automatically filtered out on all platforms.

## Installation

1. **Download the Plugin**:
   Grab the latest release from the [Releases page](https://github.com/Braandn/Majestic-Bot-Minecraft-Integration/releases). Choose the JAR file matching your Minecraft version.

2. **Place in Plugins Folder**:
   Drop the JAR file into your server's `plugins/` directory.

3. **Configure**:
   Start your server to generate the default `config.yml` in `plugins/MajesticBot/`.
   Edit the config with your API details (see [Configuration](#configuration) below).

4. **Restart or Reload**:
   Restart the server or use `/majestic reload` to apply changes.

**Dependencies**:
- Bukkit, Spigot, or PaperMC server (tested on 1.17.1+).
- Java 17 or 21 (depending on the Minecraft version).
- No additional plugins required.

## Configuration

The plugin uses a `config.yml` for setup. Here is the default configuration:

```yaml
# Display name for your server (shown in embed footers)
server_name: 'Minecraft'

# API credentials — generate at:
# https://majestic.bot/server/YOUR_DISCORD_SERVER_ID/minecraft
majestic:
  api_url: 'https://api.majestic.bot/v1/minecraft/webhook'
  api_key: 'YOUR_API_KEY_HERE'
  api_secret: 'YOUR_API_SECRET_HERE'

events:

  server_start:
    enabled: true
    color: '#FFFFFF'
    message: 'The server is now online!'

  server_stop:
    enabled: true
    color: '#FF0000'
    message: 'The server has been stopped.'

  player_chat:
    enabled: true
    color: '#0080FF'
    message: '%message%'

  player_join:
    enabled: true
    color: '#00FF00'
    message: '%player_name% joined the server'

  player_leave:
    enabled: true
    color: '#FF0000'
    message: '%player_name% left the server'

  player_death:
    enabled: true
    color: '#757575'
    message: '%death_message%'

  player_advancement:
    enabled: true
    color: '#FF9AFF'
    message: '%player_name% has made the advancement %advancement%'
```

Generate your API credentials at:
`https://majestic.bot/server/YOUR_DISCORD_SERVER_ID/minecraft`

### Variables

Use these placeholders in the `message` field of any event. Player-controlled values are automatically sanitized to prevent Discord mention abuse.

| Variable | Description | Available in |
|---|---|---|
| `%player_name%` | Player's in-game name | All player events |
| `%player_uuid%` | Player's UUID | All player events |
| `%message%` | Chat message text | `player_chat` |
| `%death_message%` | Vanilla death message | `player_death` |
| `%advancement%` | Advancement display name | `player_advancement` |
| `%server_name%` | Configured server name | All events |
| `%online_players%` | Current online player count | All events |
| `%max_players%` | Max player slots | All events |

**Examples:**
```yaml
# Simple
message: '%player_name% joined the server'

# With player count
message: '%player_name% joined! (%online_players%/%max_players%)'

# Custom death message
message: '%player_name% was eliminated'
```

### Upgrading from v1.x

The config format has changed in v2.0. Delete your old `plugins/MajesticBot/config.yml`, restart the server to generate the new format, then re-enter your API credentials and customize your event settings.

## Contributing

Contributions are welcome! Feel free to open issues or pull requests for bug fixes, features, or improvements.
Please ensure code follows Bukkit best practices and includes tests where possible.

## License

This project is licensed under the MIT License.