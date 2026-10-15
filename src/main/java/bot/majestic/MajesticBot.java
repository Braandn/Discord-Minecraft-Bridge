package bot.majestic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import bot.majestic.utils.Json;
import bot.majestic.utils.MessageFormatter;
import bot.majestic.utils.ServerInfo;
import bot.majestic.utils.VersionChecker;

public class MajesticBot extends JavaPlugin {

  /**
   * Version of the frames this plugin sends. Protocol 2 adds structured fields (player counts,
   * advancement details, command result data) on top of the protocol 1 fields, which the API still
   * reads unchanged.
   */
  public static final int PROTOCOL = 2;

  public static final String[] EVENT_KEYS = {
    "server_start",
    "server_stop",
    "player_chat",
    "player_join",
    "player_leave",
    "player_death",
    "player_advancement"
  };

  public static final String[] COMMAND_KEYS = {"ban", "unban", "kick", "whitelist", "info"};

  private static final Map<String, String> EVENT_TYPES =
      Map.of(
          "server_start", "ServerStart",
          "server_stop", "ServerStop",
          "player_chat", "PlayerChat",
          "player_join", "PlayerJoin",
          "player_leave", "PlayerLeave",
          "player_death", "PlayerDeath",
          "player_advancement", "PlayerAdvancement");

  private static MajesticBot instance;

  private String serverName = "Minecraft";
  private String mode = "websocket";

  private final Map<String, Boolean> eventEnabled = new HashMap<>();
  private final Map<String, String> eventColors = new HashMap<>();
  private final Map<String, String> eventMessages = new HashMap<>();

  private boolean discordChatEnabled = true;
  private String discordChatFormat = "&9[Discord] &f%discord_user%&7: &f%message%";

  private final Map<String, Boolean> commandEnabled = new HashMap<>();
  private final Map<String, List<String>> commandRoleIds = new HashMap<>();
  private final Map<String, List<String>> commandUserIds = new HashMap<>();

  private WebSocketManager wsManager;
  private WebhookSender webhookSender;
  private VersionChecker versionChecker;

  public static MajesticBot get() {
    return instance;
  }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  @Override
  public void onEnable() {
    instance = this;
    saveDefaultConfig();
    loadConfigData();

    Bukkit.getPluginManager().registerEvents(new Events(this), this);

    MajesticCommand command = new MajesticCommand(this);
    PluginCommand pluginCommand = getCommand("majestic");
    if (pluginCommand != null) {
      pluginCommand.setExecutor(command);
      pluginCommand.setTabCompleter(command);
    }

    versionChecker = new VersionChecker(this);
    if (getConfig().getBoolean("update_checker", true)) {
      versionChecker.start();
    }
  }

  @Override
  public void onDisable() {
    if (isEventEnabled("server_stop")) {
      Map<String, Object> frame = serverFrame("server_stop", serverVars());
      frame.put("online_players", Bukkit.getOnlinePlayers().size());
      dispatchSync(frame);
    }
    shutdownSenders();
  }

  /** Reads config.yml and rebuilds the active connection with the new values. */
  public void loadConfigData() {
    reloadConfig();
    shutdownSenders();

    serverName = getConfig().getString("server_name", "");
    if (serverName == null || serverName.isBlank()) serverName = "Minecraft";

    eventEnabled.clear();
    eventColors.clear();
    eventMessages.clear();
    for (String key : EVENT_KEYS) {
      eventEnabled.put(key, getConfig().getBoolean("events." + key + ".enabled", false));
      eventColors.put(key, getConfig().getString("events." + key + ".color", ""));
      eventMessages.put(key, getConfig().getString("events." + key + ".message", ""));
    }

    mode = getConfig().getString("majestic.mode", "websocket");
    if ("webhook".equalsIgnoreCase(mode)) {
      mode = "webhook";
      webhookSender = new WebhookSender(this, getConfig().getString("majestic.webhook_url", ""));
      getLogger()
          .info(
              "Mode: webhook ("
                  + (webhookSender.isConfigured() ? "configured URL" : "webhook_url not set")
                  + ")");
      return;
    }

    mode = "websocket";
    discordChatEnabled = getConfig().getBoolean("discord_chat.enabled", true);
    discordChatFormat = getConfig().getString("discord_chat.format", discordChatFormat);
    loadCommandConfig();

    wsManager =
        new WebSocketManager(
            this,
            getConfig().getString("majestic.ws_url", ""),
            getConfig().getString("majestic.api_key", ""),
            getConfig().getString("majestic.api_secret", ""));
    wsManager.connect();
    getLogger().info("Mode: websocket (two-way bridge)");
  }

  private void loadCommandConfig() {
    commandEnabled.clear();
    commandRoleIds.clear();
    commandUserIds.clear();
    for (String cmd : COMMAND_KEYS) {
      String path = "discord_commands." + cmd;
      commandEnabled.put(cmd, getConfig().getBoolean(path + ".enabled", false));
      commandRoleIds.put(cmd, snowflakes(getConfig().getList(path + ".allowed_role_ids")));
      commandUserIds.put(cmd, snowflakes(getConfig().getList(path + ".allowed_user_ids")));
    }
  }

  /**
   * Reads a list of Discord IDs whether they were written quoted or bare. A bare 18-digit number
   * is read by YAML as a long, which {@code getStringList} silently drops.
   */
  private static List<String> snowflakes(List<?> raw) {
    if (raw == null) return Collections.emptyList();
    List<String> out = new ArrayList<>();
    for (Object item : raw) {
      if (item == null) continue;
      String id = String.valueOf(item).trim();
      if (id.matches("\\d{15,21}")) out.add(id);
    }
    return out;
  }

  private void shutdownSenders() {
    if (wsManager != null) {
      wsManager.disconnect();
      wsManager = null;
    }
    if (webhookSender != null) {
      webhookSender.shutdown();
      webhookSender = null;
    }
  }

  // ── Accessors ──────────────────────────────────────────────────────────────

  public VersionChecker getVersionChecker() {
    return versionChecker;
  }

  public String getServerName() {
    return serverName;
  }

  public String getMode() {
    return mode;
  }

  public WebSocketManager getWebSocketManager() {
    return wsManager;
  }

  public boolean isDiscordChatEnabled() {
    return discordChatEnabled;
  }

  public String getDiscordChatFormat() {
    return discordChatFormat;
  }

  public boolean isEventEnabled(String eventKey) {
    return eventEnabled.getOrDefault(eventKey, false);
  }

  public boolean isCommandEnabled(String command) {
    return commandEnabled.getOrDefault(command.toLowerCase(), false);
  }

  /**
   * Whether the Discord user, by ID or by any of their role IDs, may run the command. Both lists
   * empty means the command is open to everyone who can see it in Discord.
   */
  public boolean isCommandAuthorized(String command, String userId, List<String> roleIds) {
    String key = command.toLowerCase();
    List<String> users = commandUserIds.getOrDefault(key, Collections.emptyList());
    List<String> roles = commandRoleIds.getOrDefault(key, Collections.emptyList());
    if (users.isEmpty() && roles.isEmpty()) return true;
    if (userId != null && !userId.isEmpty() && users.contains(userId)) return true;
    if (roleIds != null) {
      for (String roleId : roleIds) {
        if (roles.contains(roleId)) return true;
      }
    }
    return false;
  }

  /**
   * The {@code RegisterCommands} frame sent on every WebSocket open. The API stores it so the bot
   * enforces the same permissions before a command ever reaches this server.
   */
  public String buildCommandsJson() {
    Map<String, Object> commands = new LinkedHashMap<>();
    for (String cmd : COMMAND_KEYS) {
      Map<String, Object> spec = new LinkedHashMap<>();
      spec.put("enabled", commandEnabled.getOrDefault(cmd, false));
      spec.put("allowed_role_ids", commandRoleIds.getOrDefault(cmd, Collections.emptyList()));
      spec.put("allowed_user_ids", commandUserIds.getOrDefault(cmd, Collections.emptyList()));
      commands.put(cmd, spec);
    }
    Map<String, Object> frame = new LinkedHashMap<>();
    frame.put("event_type", "RegisterCommands");
    frame.put("protocol", PROTOCOL);
    frame.put("plugin_version", ServerInfo.pluginVersion());
    frame.put("commands", commands);
    return Json.write(frame);
  }

  // ── Outbound events ───────────────────────────────────────────────────────

  /** Variables every message template can use. */
  public Map<String, String> serverVars() {
    Map<String, String> vars = new HashMap<>();
    vars.put("server_name", serverName);
    vars.put("online_players", String.valueOf(Bukkit.getOnlinePlayers().size()));
    vars.put("max_players", String.valueOf(Bukkit.getMaxPlayers()));
    return vars;
  }

  /** A frame for a server event, with its formatted template message and configured colour. */
  public Map<String, Object> serverFrame(String eventKey, Map<String, String> vars) {
    Map<String, Object> frame = new LinkedHashMap<>();
    frame.put("event_type", EVENT_TYPES.get(eventKey));
    frame.put("protocol", PROTOCOL);
    frame.put("server_name", serverName);
    frame.put("message", MessageFormatter.format(eventMessages.getOrDefault(eventKey, ""), vars));
    String color = eventColors.getOrDefault(eventKey, "");
    if (color != null && !color.isBlank()) frame.put("color", color.trim());
    frame.put("max_players", Bukkit.getMaxPlayers());
    return frame;
  }

  /** {@link #serverFrame} plus the player's name and UUID. */
  public Map<String, Object> playerFrame(
      String eventKey, String playerName, String playerUuid, Map<String, String> vars) {
    vars.put("player_name", playerName);
    vars.put("player_uuid", playerUuid);
    Map<String, Object> frame = serverFrame(eventKey, vars);
    frame.put("player_name", playerName);
    frame.put("player_uuid", playerUuid);
    return frame;
  }

  /** Sends a frame through the active mode without blocking the caller. */
  public void dispatch(Map<String, Object> frame) {
    if (webhookSender != null) {
      webhookSender.send(frame);
    } else if (wsManager != null) {
      wsManager.send(Json.write(frame));
    }
  }

  /** Sends a frame and waits for it to leave, for the shutdown notice. */
  public void dispatchSync(Map<String, Object> frame) {
    if (webhookSender != null) {
      webhookSender.sendSync(frame);
    } else if (wsManager != null) {
      wsManager.sendSync(Json.write(frame));
    }
  }

  /** Builds and sends the server-start notice once the server has finished loading. */
  void announceStart() {
    if (!isEventEnabled("server_start")) return;
    Map<String, Object> frame = serverFrame("server_start", serverVars());
    frame.put("online_players", Bukkit.getOnlinePlayers().size());
    frame.put("minecraft_version", ServerInfo.minecraftVersion());
    dispatch(frame);
  }
}
