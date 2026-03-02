package bot.majestic;

import bot.majestic.utils.MessageFormatter;
import bot.majestic.utils.VersionChecker;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public class MajesticBot extends JavaPlugin {

  private static String ENDPOINT = "";
  private static String API_KEY = "";
  private static String API_SECRET = "";
  private static String SERVER_NAME = "";

  private final Map<String, Boolean> eventEnabled = new HashMap<>();
  private final Map<String, String> eventColors = new HashMap<>();
  private final Map<String, String> eventMessages = new HashMap<>();

  private VersionChecker versionChecker;

  private static final String[] EVENT_KEYS = {
    "server_start",
    "server_stop",
    "player_chat",
    "player_join",
    "player_leave",
    "player_death",
    "player_advancement"
  };

  public VersionChecker getVersionChecker() {
    return versionChecker;
  }

  public static String getServerName() {
    return SERVER_NAME;
  }

  @Override
  public void onEnable() {
    saveDefaultConfig();
    loadConfigData();

    PluginManager pm = Bukkit.getPluginManager();
    pm.registerEvents(new Events(this), this);
    getCommand("majestic").setExecutor(new Reload(this));

    versionChecker = new VersionChecker(this);
    versionChecker.start();

    String key = "server_start";
    if (isEventEnabled(key)) {
      Map<String, String> vars = new HashMap<>();
      vars.put("server_name", SERVER_NAME);
      vars.put("online_players", String.valueOf(Bukkit.getOnlinePlayers().size()));
      vars.put("max_players", String.valueOf(Bukkit.getMaxPlayers()));
      String formatted = MessageFormatter.format(getMessage(key), vars);
      sendEvent("ServerStart", "", "", formatted, getEventColor(key));
    }
  }

  @Override
  public void onDisable() {
    String key = "server_stop";
    if (isEventEnabled(key)) {
      Map<String, String> vars = new HashMap<>();
      vars.put("server_name", SERVER_NAME);
      vars.put("online_players", String.valueOf(Bukkit.getOnlinePlayers().size()));
      vars.put("max_players", String.valueOf(Bukkit.getMaxPlayers()));
      String formatted = MessageFormatter.format(getMessage(key), vars);
      sendEventSync("ServerStop", "", "", formatted, getEventColor(key));
    }
  }

  public void loadConfigData() {
    reloadConfig();

    ENDPOINT = getConfig().getString("majestic.api_url", "");
    API_KEY = getConfig().getString("majestic.api_key", "");
    API_SECRET = getConfig().getString("majestic.api_secret", "");
    SERVER_NAME = getConfig().getString("server_name", "Minecraft");

    eventEnabled.clear();
    eventColors.clear();
    eventMessages.clear();

    for (String key : EVENT_KEYS) {
      boolean enabled = getConfig().getBoolean("events." + key + ".enabled", false);
      eventEnabled.put(key, enabled);

      String color = getConfig().getString("events." + key + ".color", "#FFFFFF");
      eventColors.put(key, color);

      String message = getConfig().getString("events." + key + ".message", "");
      eventMessages.put(key, message);
    }
  }

  public boolean isEventEnabled(String eventKey) {
    return eventEnabled.getOrDefault(eventKey, false);
  }

  public String getEventColor(String eventKey) {
    return eventColors.getOrDefault(eventKey, "#FFFFFF");
  }

  public String getMessage(String eventKey) {
    return eventMessages.getOrDefault(eventKey, "");
  }

  /**
   * Send a player/server event to the webhook asynchronously (fire-and-forget).
   *
   * @param eventType "PlayerChat" | "PlayerJoin" | "PlayerLeave" | "PlayerDeath" |
   *     "PlayerAdvancement" | "ServerStart" | "ServerStop"
   * @param playerName In-game display name (empty for server events)
   * @param playerUuid player UUID string (empty for server events)
   * @param message Formatted message with variables already replaced
   * @param color Hex color string (e.g. "#00FF00") or empty for default
   */
  public static void sendEvent(
      String eventType, String playerName, String playerUuid, String message, String color) {
    new Thread(
            () -> doSendEvent(eventType, playerName, playerUuid, message, color), "majestic-event")
        .start();
  }

  public static void sendEventSync(
      String eventType, String playerName, String playerUuid, String message, String color) {
    doSendEvent(eventType, playerName, playerUuid, message, color);
  }

  private static void doSendEvent(
      String eventType, String playerName, String playerUuid, String message, String color) {
    if (ENDPOINT.isEmpty()
        || API_KEY.isEmpty()
        || API_SECRET.isEmpty()
        || API_KEY.equals("YOUR_API_KEY_HERE")
        || API_SECRET.equals("YOUR_API_SECRET_HERE")) {
      return;
    }
    try {
      String body =
          "{"
              + "\"event_type\":\""
              + esc(eventType)
              + "\","
              + "\"player_name\":\""
              + esc(playerName)
              + "\","
              + "\"player_uuid\":\""
              + esc(playerUuid)
              + "\","
              + "\"message\":\""
              + esc(message)
              + "\","
              + "\"server_name\":\""
              + esc(SERVER_NAME)
              + "\","
              + "\"color\":\""
              + esc(color)
              + "\""
              + "}";

      HttpURLConnection con = (HttpURLConnection) new URI(ENDPOINT).toURL().openConnection();
      con.setRequestMethod("POST");
      con.setRequestProperty("Content-Type", "application/json");
      con.setRequestProperty("X-Api-Key", API_KEY);
      con.setRequestProperty("X-Api-Secret", API_SECRET);
      con.setDoOutput(true);
      con.setConnectTimeout(5000);
      con.setReadTimeout(5000);

      try (OutputStream os = con.getOutputStream()) {
        os.write(body.getBytes(StandardCharsets.UTF_8));
      }
      con.getResponseCode();
      con.disconnect();

    } catch (IOException | URISyntaxException e) {
      e.printStackTrace();
    }
  }

  private static String esc(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
  }
}
