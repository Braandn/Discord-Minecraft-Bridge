package bot.majestic;

import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import bot.majestic.utils.ServerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** {@code /majestic reload} and {@code /majestic status}. */
public class MajesticCommand implements TabExecutor {

  private static final List<String> SUBCOMMANDS = List.of("reload", "status");

  private final MajesticBot plugin;

  public MajesticCommand(MajesticBot plugin) {
    this.plugin = plugin;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    String sub = args.length == 1 ? args[0].toLowerCase() : "";
    switch (sub) {
      case "reload" -> {
        if (!sender.hasPermission("majestic.reload")) return denied(sender);
        plugin.loadConfigData();
        sender.sendMessage(Component.text("MajesticBot configuration reloaded.", NamedTextColor.GREEN));
      }
      case "status" -> {
        if (!sender.hasPermission("majestic.status")) return denied(sender);
        status(sender);
      }
      default ->
          sender.sendMessage(Component.text("Usage: /" + label + " <reload|status>", NamedTextColor.RED));
    }
    return true;
  }

  private void status(CommandSender sender) {
    sender.sendMessage(
        Component.text("MajesticBot " + ServerInfo.pluginVersion(), NamedTextColor.GOLD)
            .append(Component.text(" · mode " + plugin.getMode(), NamedTextColor.GRAY)));
    WebSocketManager ws = plugin.getWebSocketManager();
    if (ws == null) return;
    if (ws.isConnected()) {
      long minutes = (System.currentTimeMillis() - ws.getConnectedSince()) / 60000;
      sender.sendMessage(
          Component.text("Connected", NamedTextColor.GREEN)
              .append(Component.text(" for " + minutes + " min", NamedTextColor.GRAY)));
    } else {
      String error = ws.getLastError();
      sender.sendMessage(
          Component.text("Not connected", NamedTextColor.RED)
              .append(
                  Component.text(
                      (error.isEmpty() ? "" : ": " + error)
                          + (ws.getQueuedCount() > 0 ? " (" + ws.getQueuedCount() + " queued)" : ""),
                      NamedTextColor.GRAY)));
    }
  }

  private static boolean denied(CommandSender sender) {
    sender.sendMessage(Component.text("You do not have permission to do that.", NamedTextColor.RED));
    return true;
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    if (args.length != 1) return List.of();
    String prefix = args[0].toLowerCase();
    return SUBCOMMANDS.stream()
        .filter(s -> s.startsWith(prefix) && sender.hasPermission("majestic." + s))
        .toList();
  }
}
