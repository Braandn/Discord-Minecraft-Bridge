package bot.majestic.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import bot.majestic.MajesticBot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public class VersionChecker {

  private static final String GITHUB_REPO = "Braandn/Discord-Minecraft-Bridge";
  private static final String API_URL =
      "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";

  private static final long CHECK_INTERVAL_TICKS = 20L * 60 * 60 * 24;

  private final MajesticBot plugin;

  private volatile String latestVersion = null;
  private volatile String downloadUrl = null;
  private volatile boolean updateAvailable = false;

  public VersionChecker(MajesticBot plugin) {
    this.plugin = plugin;
  }

  public void start() {
    Bukkit.getScheduler()
        .runTaskTimerAsynchronously(plugin, this::checkForUpdate, 0L, CHECK_INTERVAL_TICKS);
  }

  private void checkForUpdate() {
    try {
      HttpURLConnection con = (HttpURLConnection) new URI(API_URL).toURL().openConnection();
      con.setRequestMethod("GET");
      con.setRequestProperty("Accept", "application/vnd.github+json");
      con.setRequestProperty("User-Agent", "MajesticBot-Minecraft/" + ServerInfo.pluginVersion());
      con.setConnectTimeout(5000);
      con.setReadTimeout(5000);

      int status = con.getResponseCode();
      if (status != 200) {
        plugin.getLogger().warning("GitHub API returned HTTP " + status);
        return;
      }

      StringBuilder response = new StringBuilder();
      try (BufferedReader br =
          new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = br.readLine()) != null) {
          response.append(line);
        }
      }
      con.disconnect();

      Map<String, Object> json = Json.parseObject(response.toString());
      String tagName = Json.string(json, "tag_name");
      String htmlUrl = Json.string(json, "html_url");

      if (tagName.isEmpty() || htmlUrl.isEmpty()) {
        plugin.getLogger().warning("Could not parse GitHub release response.");
        return;
      }

      latestVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;
      downloadUrl = htmlUrl;

      String currentVersion = ServerInfo.pluginVersion();
      updateAvailable = isNewer(latestVersion, currentVersion);

      if (updateAvailable) {
        plugin
            .getLogger()
            .warning(
                "A new version is available: "
                    + latestVersion
                    + " (current: "
                    + currentVersion
                    + ") -> "
                    + downloadUrl);
      } else {
        plugin.getLogger().info("Plugin is up to date (" + currentVersion + ").");
      }

    } catch (IOException | URISyntaxException e) {
      plugin.getLogger().warning("Failed to check for updates: " + e.getMessage());
    }
  }

  public void notifyIfOutdated(Player player) {
    if (!updateAvailable) return;

    player.sendMessage(
        Component.text("", NamedTextColor.GOLD)
            .append(Component.text("A new version is available: ", NamedTextColor.YELLOW))
            .append(Component.text(latestVersion, NamedTextColor.GREEN))
            .append(Component.text(" - ", NamedTextColor.YELLOW))
            .append(
                Component.text("[Download]", NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(downloadUrl))));
  }

  private boolean isNewer(String candidate, String current) {
    int[] c = parseVersion(candidate);
    int[] v = parseVersion(current);
    for (int i = 0; i < Math.max(c.length, v.length); i++) {
      int ci = i < c.length ? c[i] : 0;
      int vi = i < v.length ? v[i] : 0;
      if (ci > vi) return true;
      if (ci < vi) return false;
    }
    return false;
  }

  private int[] parseVersion(String version) {
    String[] parts = version.split("[.\\-]");
    int[] nums = new int[parts.length];
    for (int i = 0; i < parts.length; i++) {
      try {
        nums[i] = Integer.parseInt(parts[i]);
      } catch (NumberFormatException e) {
        nums[i] = 0;
      }
    }
    return nums;
  }
}
