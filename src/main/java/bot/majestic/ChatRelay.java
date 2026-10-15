package bot.majestic;

import java.util.Map;

import org.bukkit.Bukkit;

import bot.majestic.utils.Json;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Shows a relayed Discord message in game chat.
 *
 * <p>Only the admin's {@code discord_chat.format} template is read for {@code &} colour codes. The
 * Discord name and message are inserted afterwards as plain text, so a Discord user cannot colour,
 * obfuscate or fake a server message by typing codes.
 */
final class ChatRelay {

  private final MajesticBot plugin;

  ChatRelay(MajesticBot plugin) {
    this.plugin = plugin;
  }

  void show(Map<String, Object> frame) {
    if (!plugin.isDiscordChatEnabled()) return;
    Component line = render(plugin.getDiscordChatFormat(), frame);
    Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getServer().broadcast(line));
  }

  static Component render(String format, Map<String, Object> frame) {
    String user = Json.string(frame, "discord_user");
    String tag = Json.string(frame, "discord_tag");
    String text = Json.string(frame, "message");
    String replyTo = Json.string(frame, "reply_to");
    long attachments = Json.number(frame, "attachments", 0);

    Component hover = Component.text("@" + tag, NamedTextColor.GRAY);
    if (!replyTo.isEmpty()) {
      hover = hover.append(Component.newline()).append(Component.text("Replying to " + replyTo));
    }
    Component name = Component.text(user).hoverEvent(HoverEvent.showText(hover));
    TextColor roleColor = TextColor.fromHexString(Json.string(frame, "discord_color"));
    if (roleColor != null) name = name.color(roleColor);

    Component message = Component.empty();
    if (!replyTo.isEmpty()) {
      message = message.append(Component.text("@" + replyTo + " ", NamedTextColor.GRAY));
    }
    message = message.append(Component.text(text));
    if (attachments > 0) {
      String label = attachments == 1 ? "[1 attachment]" : "[" + attachments + " attachments]";
      message =
          message.append(
              Component.text((text.isEmpty() ? "" : " ") + label, NamedTextColor.GRAY)
                  .decorate(TextDecoration.ITALIC));
    }

    Component base = LegacyComponentSerializer.legacyAmpersand().deserialize(format);
    return base.replaceText(literal("%discord_user%", name))
        .replaceText(literal("%discord_tag%", Component.text(tag)))
        .replaceText(literal("%message%", message));
  }

  private static TextReplacementConfig literal(String placeholder, Component value) {
    return TextReplacementConfig.builder().matchLiteral(placeholder).replacement(value).build();
  }
}
