package bot.majestic;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerLoadEvent;

import bot.majestic.utils.AdvancementNameResolver;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Turns server events into frames. Every handler runs at MONITOR and skips cancelled events, so a
 * message a chat or mute plugin blocked never reaches Discord.
 */
public class Events implements Listener {

  private final MajesticBot plugin;
  private final Map<UUID, Long> joinedAt = new ConcurrentHashMap<>();

  public Events(MajesticBot plugin) {
    this.plugin = plugin;
  }

  private Map<String, Object> playerFrame(String key, Player player, Map<String, String> vars) {
    return plugin.playerFrame(key, player.getName(), player.getUniqueId().toString(), vars);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onServerLoad(ServerLoadEvent e) {
    if (e.getType() == ServerLoadEvent.LoadType.STARTUP) {
      plugin.announceStart();
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onChat(AsyncChatEvent e) {
    if (!plugin.isEventEnabled("player_chat")) return;
    String text = PlainTextComponentSerializer.plainText().serialize(e.message());
    if (text.isBlank()) return;

    Map<String, String> vars = plugin.serverVars();
    vars.put("message", text);
    plugin.dispatch(playerFrame("player_chat", e.getPlayer(), vars));
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onJoin(PlayerJoinEvent e) {
    Player player = e.getPlayer();
    joinedAt.put(player.getUniqueId(), System.currentTimeMillis());

    if (plugin.isEventEnabled("player_join")) {
      Map<String, Object> frame = playerFrame("player_join", player, plugin.serverVars());
      frame.put("online_players", Bukkit.getOnlinePlayers().size());
      frame.put("first_join", !player.hasPlayedBefore());
      plugin.dispatch(frame);
    }

    if (player.hasPermission("majestic.update")) {
      plugin.getVersionChecker().notifyIfOutdated(player);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent e) {
    Player player = e.getPlayer();
    Long joined = joinedAt.remove(player.getUniqueId());
    if (!plugin.isEventEnabled("player_leave")) return;

    // The quitting player is still counted online while this event runs.
    int online = Math.max(0, Bukkit.getOnlinePlayers().size() - 1);
    Map<String, String> vars = plugin.serverVars();
    vars.put("online_players", String.valueOf(online));

    Map<String, Object> frame = playerFrame("player_leave", player, vars);
    frame.put("online_players", online);
    if (joined != null) {
      frame.put("session_seconds", Math.max(0, (System.currentTimeMillis() - joined) / 1000));
    }
    plugin.dispatch(frame);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onDeath(PlayerDeathEvent e) {
    if (!plugin.isEventEnabled("player_death")) return;
    Player player = e.getEntity();
    Component deathMessage = e.deathMessage();
    String text =
        deathMessage != null
            ? PlainTextComponentSerializer.plainText().serialize(deathMessage)
            : player.getName() + " died";

    Map<String, String> vars = plugin.serverVars();
    vars.put("death_message", text);
    plugin.dispatch(playerFrame("player_death", player, vars));
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onAdvancement(PlayerAdvancementDoneEvent e) {
    if (!plugin.isEventEnabled("player_advancement")) return;
    if (AdvancementNameResolver.isHidden(e.getAdvancement())) return;

    String title = AdvancementNameResolver.resolve(e.getAdvancement());
    Map<String, String> vars = plugin.serverVars();
    vars.put("advancement", title);

    Map<String, Object> frame = playerFrame("player_advancement", e.getPlayer(), vars);
    frame.put("advancement_title", title);
    frame.put("advancement_description", AdvancementNameResolver.description(e.getAdvancement()));
    frame.put("advancement_frame", AdvancementNameResolver.frame(e.getAdvancement()));
    plugin.dispatch(frame);
  }
}
