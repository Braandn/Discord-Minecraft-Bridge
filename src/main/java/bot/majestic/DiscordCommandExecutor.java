package bot.majestic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import bot.majestic.utils.Json;
import bot.majestic.utils.ServerInfo;
import net.kyori.adventure.text.Component;

/**
 * Runs Discord-originated commands ({@code ban}, {@code unban}, {@code kick}, {@code whitelist},
 * {@code info}) on the main thread and answers each with a {@code CommandResult} frame.
 *
 * <p>Permissions are checked again here even though the bot already did, since this server's
 * config.yml is the authority. Bans, pardons and whitelist changes go through the vanilla commands,
 * which resolve the player's profile the same way on every server version, and the result reports
 * the state read back afterwards rather than assuming the command worked.
 */
public class DiscordCommandExecutor {

  // Java names, plus the "." or "*" prefix Floodgate gives Bedrock players.
  private static final Pattern PLAYER_NAME = Pattern.compile("^[.*]?[A-Za-z0-9_]{1,16}$");
  private static final int REASON_LIMIT = 200;

  private final MajesticBot plugin;
  private final WebSocketManager wsManager;

  public DiscordCommandExecutor(MajesticBot plugin, WebSocketManager wsManager) {
    this.plugin = plugin;
    this.wsManager = wsManager;
  }

  /** Entry point from the socket thread: validates, then hands off to the main thread. */
  public void handle(
      String command,
      List<String> args,
      String discordUser,
      String discordUserId,
      List<String> discordRoleIds) {
    String cmd = command == null ? "" : command.toLowerCase();
    if (!plugin.isCommandEnabled(cmd)) {
      fail(cmd, "The `" + cmd + "` command is disabled on this server.", discordUser);
      return;
    }
    if (!plugin.isCommandAuthorized(cmd, discordUserId, discordRoleIds)) {
      fail(cmd, "You don't have permission to use `" + cmd + "` on this server.", discordUser);
      return;
    }
    Bukkit.getScheduler().runTask(plugin, () -> dispatch(cmd, args, discordUser));
  }

  private void dispatch(String command, List<String> args, String discordUser) {
    try {
      switch (command) {
        case "ban" -> ban(args, discordUser);
        case "unban" -> unban(args, discordUser);
        case "kick" -> kick(args, discordUser);
        case "whitelist" -> whitelist(args, discordUser);
        case "info" -> info(discordUser);
        default -> fail(command, "Unknown command `" + command + "`.", discordUser);
      }
    } catch (RuntimeException e) {
      plugin.getLogger().warning("Discord command " + command + " failed: " + e.getMessage());
      fail(command, "The server hit an error running that command.", discordUser);
    }
  }

  // ─── Commands ─────────────────────────────────────────────────────────────

  private void ban(List<String> args, String discordUser) {
    String name = playerArg(args, 0);
    if (name == null) {
      fail("ban", "Give a valid Minecraft player name.", discordUser);
      return;
    }
    String reason = reasonArg(args, "Banned via Discord by " + discordUser);
    runConsole("minecraft:ban " + name + " " + reason);
    OfflinePlayer target = offline(name);
    if (!target.isBanned()) {
      fail("ban", "**" + name + "** could not be banned. Check that the name is correct.", discordUser);
      return;
    }
    Map<String, Object> data = playerData(target, name);
    data.put("reason", reason);
    ok("ban", "**" + name + "** has been banned.", discordUser, data);
  }

  private void unban(List<String> args, String discordUser) {
    String name = playerArg(args, 0);
    if (name == null) {
      fail("unban", "Give a valid Minecraft player name.", discordUser);
      return;
    }
    if (!offline(name).isBanned()) {
      fail("unban", "**" + name + "** is not banned.", discordUser);
      return;
    }
    runConsole("minecraft:pardon " + name);
    OfflinePlayer target = offline(name);
    if (target.isBanned()) {
      fail("unban", "**" + name + "** could not be unbanned.", discordUser);
      return;
    }
    ok("unban", "**" + name + "** has been unbanned.", discordUser, playerData(target, name));
  }

  private void kick(List<String> args, String discordUser) {
    String name = playerArg(args, 0);
    Player player = name == null ? null : Bukkit.getPlayerExact(name);
    if (player == null) {
      fail("kick", "**" + (name == null ? "That player" : name) + "** is not online.", discordUser);
      return;
    }
    String reason = reasonArg(args, "Kicked via Discord by " + discordUser);
    player.kick(Component.text(reason));
    Map<String, Object> data = playerData(player, player.getName());
    data.put("reason", reason);
    ok("kick", "**" + player.getName() + "** has been kicked.", discordUser, data);
  }

  private void whitelist(List<String> args, String discordUser) {
    String action = args.isEmpty() ? "" : args.get(0).toLowerCase();
    switch (action) {
      case "on", "off" -> {
        boolean enable = action.equals("on");
        Bukkit.setWhitelist(enable);
        Map<String, Object> data = baseData();
        data.put("whitelist", enable);
        ok("whitelist", "Whitelist " + (enable ? "enabled" : "disabled") + ".", discordUser, data);
      }
      case "add", "remove" -> {
        String name = playerArg(args, 1);
        if (name == null) {
          fail("whitelist", "Give a valid Minecraft player name.", discordUser);
          return;
        }
        boolean add = action.equals("add");
        runConsole("minecraft:whitelist " + action + " " + name);
        OfflinePlayer target = offline(name);
        if (target.isWhitelisted() != add) {
          fail("whitelist", "**" + name + "** could not be " + (add ? "added." : "removed."), discordUser);
          return;
        }
        String message = "**" + name + "** " + (add ? "added to" : "removed from") + " the whitelist.";
        ok("whitelist", message, discordUser, playerData(target, name));
      }
      default -> fail("whitelist", "Use `on`, `off`, `add` or `remove`.", discordUser);
    }
  }

  private void info(String discordUser) {
    Map<String, Object> data = ServerInfo.snapshot(plugin.getServerName());
    String message =
        "**Players:** "
            + data.get("online_players")
            + "/"
            + data.get("max_players")
            + "\n**Version:** "
            + data.get("version");
    ok("info", message, discordUser, data);
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  private static String playerArg(List<String> args, int index) {
    if (args.size() <= index) return null;
    String name = args.get(index).trim();
    return PLAYER_NAME.matcher(name).matches() ? name : null;
  }

  private static String reasonArg(List<String> args, String fallback) {
    if (args.size() < 2) return fallback;
    String reason = String.join(" ", args.subList(1, args.size()));
    reason = reason.replaceAll("[\\p{Cntrl}§]", " ").replaceAll("\\s+", " ").trim();
    if (reason.isEmpty()) return fallback;
    return reason.length() > REASON_LIMIT ? reason.substring(0, REASON_LIMIT) : reason;
  }

  @SuppressWarnings("deprecation")
  private static OfflinePlayer offline(String name) {
    Player online = Bukkit.getPlayerExact(name);
    return online != null ? online : Bukkit.getOfflinePlayer(name);
  }

  private static void runConsole(String command) {
    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
  }

  private Map<String, Object> baseData() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("server_name", plugin.getServerName());
    return data;
  }

  private Map<String, Object> playerData(OfflinePlayer target, String fallbackName) {
    Map<String, Object> data = baseData();
    String name = target.getName();
    data.put("player", name != null ? name : fallbackName);
    if (target.getUniqueId() != null) data.put("player_uuid", target.getUniqueId().toString());
    return data;
  }

  private void ok(String command, String message, String discordUser, Map<String, Object> data) {
    sendResult(command, true, message, discordUser, data);
  }

  private void fail(String command, String message, String discordUser) {
    sendResult(command, false, message, discordUser, null);
  }

  private void sendResult(
      String command, boolean success, String message, String discordUser, Map<String, Object> data) {
    Map<String, Object> frame = new LinkedHashMap<>();
    frame.put("event_type", "CommandResult");
    frame.put("protocol", MajesticBot.PROTOCOL);
    frame.put("command", command);
    frame.put("success", success);
    frame.put("message", message);
    frame.put("discord_user", discordUser);
    if (data != null) frame.put("data", data);
    wsManager.send(Json.write(frame));
  }
}
