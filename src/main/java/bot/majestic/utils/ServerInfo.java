package bot.majestic.utils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import bot.majestic.MajesticBot;

/** Read-only facts about the running server, resolved the same way on every supported version. */
public final class ServerInfo {

  private static final Pattern FORMATTING_CODES = Pattern.compile("(?i)§[0-9A-FK-ORX]");

  private ServerInfo() {}

  /** The plugin version from the jar manifest, which the build fills in from the release tag. */
  public static String pluginVersion() {
    String version = MajesticBot.class.getPackage().getImplementationVersion();
    return version != null ? version : "unknown";
  }

  /**
   * The Minecraft version, e.g. {@code 26.3} or {@code 1.21.11}. Paper's {@code
   * getMinecraftVersion()} is preferred; the Bukkit version string is the fallback, minus its
   * {@code -R0.1-SNAPSHOT} suffix on servers that still add one.
   */
  public static String minecraftVersion() {
    try {
      Method method = Bukkit.getServer().getClass().getMethod("getMinecraftVersion");
      Object value = method.invoke(Bukkit.getServer());
      if (value instanceof String s && !s.isBlank()) return s;
    } catch (ReflectiveOperationException | RuntimeException ignored) {
    }
    String bukkit = Bukkit.getBukkitVersion();
    int dash = bukkit.indexOf('-');
    return dash > 0 ? bukkit.substring(0, dash) : bukkit;
  }

  /** Paper's 1m/5m/15m TPS averages, or an empty list on a server without them. */
  public static List<Double> tps() {
    List<Double> out = new ArrayList<>();
    try {
      for (double value : Bukkit.getServer().getTPS()) {
        out.add(Math.round(Math.min(value, 20.0) * 100.0) / 100.0);
      }
    } catch (NoSuchMethodError ignored) {
    }
    return out;
  }

  /** The MOTD as plain text without formatting codes, collapsed to one line. */
  @SuppressWarnings("deprecation")
  public static String motd() {
    String motd = Bukkit.getMotd();
    if (motd == null) return "";
    return FORMATTING_CODES.matcher(motd).replaceAll("").replaceAll("\\s+", " ").trim();
  }

  /** The structured snapshot the {@code info} command returns to Discord. */
  public static Map<String, Object> snapshot(String serverName) {
    List<String> players = new ArrayList<>();
    for (Player player : Bukkit.getOnlinePlayers()) {
      players.add(player.getName());
    }
    players.sort(String.CASE_INSENSITIVE_ORDER);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("server_name", serverName);
    data.put("online_players", players.size());
    data.put("max_players", Bukkit.getMaxPlayers());
    data.put("version", minecraftVersion());
    data.put("whitelist", Bukkit.hasWhitelist());
    List<Double> tps = tps();
    if (!tps.isEmpty()) data.put("tps", tps);
    String motd = motd();
    if (!motd.isEmpty()) data.put("motd", motd);
    data.put("players", players.size() > 100 ? players.subList(0, 100) : players);
    return data;
  }
}
