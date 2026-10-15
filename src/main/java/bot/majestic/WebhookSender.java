package bot.majestic;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import bot.majestic.utils.Json;

/**
 * Posts events straight to a Discord webhook (one-way, no Majestic account needed).
 *
 * <p>The messages use Discord's Components V2 layout, the same card the Majestic bot posts: a
 * coloured container with a {@code ###} heading, detail rows and the player's head as thumbnail.
 * Player chat is posted under the player's own name and head, like a native chat line. A webhook
 * cannot use the bot's custom emoji, so headings use unicode ones. Deliveries run one at a time on
 * a single thread, keeping their order and honouring a 429's {@code retry_after} once.
 */
public class WebhookSender {

  private static final int IS_COMPONENTS_V2 = 1 << 15;
  private static final int SUPPRESS_NOTIFICATIONS = 1 << 12;
  private static final int TYPE_SECTION = 9;
  private static final int TYPE_TEXT = 10;
  private static final int TYPE_THUMBNAIL = 11;
  private static final int TYPE_SEPARATOR = 14;
  private static final int TYPE_CONTAINER = 17;

  private static final Pattern WEBHOOK_URL =
      Pattern.compile(
          "^https://(?:canary\\.|ptb\\.)?discord(?:app)?\\.com/api/(?:v\\d+/)?webhooks/\\d+/[\\w-]+$");
  private static final Pattern ONLINE_UUID =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}$");
  private static final Pattern MENTION = Pattern.compile("<@[!&]?\\d+>");
  private static final Pattern BLOCK_MARKER = Pattern.compile("^(#|-#|>|[-*+]\\s|\\d+[.)]\\s)");

  private static final Map<String, Integer> DEFAULT_COLORS =
      Map.of(
          "ServerStart", 0x22C55E,
          "ServerStop", 0xF87171,
          "PlayerChat", 0x3B9EFF,
          "PlayerJoin", 0x22C55E,
          "PlayerLeave", 0xF87171,
          "PlayerDeath", 0xA1A1AA,
          "PlayerAdvancement", 0x22C55E);

  private final MajesticBot plugin;
  private final String webhookUrl;
  private final HttpClient http;
  private final ExecutorService worker;

  public WebhookSender(MajesticBot plugin, String webhookUrl) {
    this.plugin = plugin;
    this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    this.worker =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "majestic-webhook");
              t.setDaemon(true);
              return t;
            });
    if (!this.webhookUrl.isEmpty()
        && !this.webhookUrl.equals("YOUR_WEBHOOK_URL_HERE")
        && !WEBHOOK_URL.matcher(this.webhookUrl).matches()) {
      plugin.getLogger().warning("majestic.webhook_url does not look like a Discord webhook URL.");
    }
  }

  public boolean isConfigured() {
    return WEBHOOK_URL.matcher(webhookUrl).matches();
  }

  public void send(Map<String, Object> frame) {
    if (!isConfigured()) return;
    String body = Json.write(buildPayload(frame));
    worker.execute(() -> post(body, true));
  }

  public void sendSync(Map<String, Object> frame) {
    if (!isConfigured()) return;
    String body = Json.write(buildPayload(frame));
    try {
      Future<?> pending = worker.submit(() -> post(body, false));
      pending.get(8, TimeUnit.SECONDS);
    } catch (Exception e) {
      plugin.getLogger().warning("Webhook delivery failed: " + e.getMessage());
    }
  }

  public void shutdown() {
    worker.shutdown();
  }

  // ─── Payload ──────────────────────────────────────────────────────────────

  static Map<String, Object> buildPayload(Map<String, Object> frame) {
    String eventType = Json.string(frame, "event_type");
    String player = Json.string(frame, "player_name");
    String server = Json.string(frame, "server_name");
    int color = color(Json.string(frame, "color"), eventType, Json.string(frame, "advancement_frame"));
    String head = headUrl(player, Json.string(frame, "player_uuid"));

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("allowed_mentions", Map.of("parse", List.of()));

    if (eventType.equals("PlayerChat")) {
      payload.put("username", truncate(player.isEmpty() ? server : player, 80));
      if (head != null) payload.put("avatar_url", head);
      payload.put("flags", IS_COMPONENTS_V2 | SUPPRESS_NOTIFICATIONS);
      String text = line(sanitize(Json.string(frame, "message")));
      payload.put("components", List.of(text(truncate(text.isEmpty() ? "*No text*" : text, 1900))));
      return payload;
    }

    payload.put("username", truncate(server.isEmpty() ? "Minecraft" : server, 80));
    payload.put("flags", IS_COMPONENTS_V2);

    List<String> rows = new ArrayList<>();
    rows.add("### " + icon(eventType, Json.string(frame, "advancement_frame")) + " " + title(frame));
    long online = Json.number(frame, "online_players", -1);
    long slots = Json.number(frame, "max_players", 0);
    String playersRow =
        online < 0 ? null : "**Players online** " + online + (slots > 0 ? "/" + slots : "");
    switch (eventType) {
      case "ServerStart" -> {
        String version = Json.string(frame, "minecraft_version");
        if (!version.isEmpty()) rows.add("**Version** " + escape(version));
        if (slots > 0) rows.add("**Slots** " + slots);
      }
      case "PlayerJoin" -> {
        if (Json.bool(frame, "first_join")) rows.add("First time on this server");
        if (playersRow != null) rows.add(playersRow);
      }
      case "PlayerLeave" -> {
        long session = Json.number(frame, "session_seconds", 0);
        if (session > 0) rows.add("**Session** " + duration(session));
        if (playersRow != null) rows.add(playersRow);
      }
      case "PlayerAdvancement" -> {
        String advancement = Json.string(frame, "advancement_title");
        String label =
            switch (Json.string(frame, "advancement_frame")) {
              case "goal" -> "Goal";
              case "challenge" -> "Challenge";
              default -> "Advancement";
            };
        if (!advancement.isEmpty()) rows.add("**" + label + "** " + escape(advancement));
        String description = Json.string(frame, "advancement_description");
        if (!description.isEmpty()) rows.add("> " + escape(truncate(description, 200)));
      }
      default -> {}
    }

    Map<String, Object> headItem = text(String.join("\n", rows));
    if (head != null && eventType.startsWith("Player")) {
      Map<String, Object> section = new LinkedHashMap<>();
      section.put("type", TYPE_SECTION);
      section.put("components", List.of(headItem));
      section.put("accessory", Map.of("type", TYPE_THUMBNAIL, "media", Map.of("url", head)));
      headItem = section;
    }

    List<Object> items = new ArrayList<>();
    items.add(headItem);
    List<String> footer = new ArrayList<>();
    if (!server.isEmpty()) footer.add(escape(server));
    if (eventType.equals("ServerStop") && online > 0) {
      footer.add(online + " player" + (online == 1 ? "" : "s") + " online at shutdown");
    }
    if (!footer.isEmpty()) {
      items.add(Map.of("type", TYPE_SEPARATOR, "spacing", 1));
      items.add(text("-# " + String.join(" · ", footer)));
    }

    Map<String, Object> container = new LinkedHashMap<>();
    container.put("type", TYPE_CONTAINER);
    container.put("accent_color", color);
    container.put("components", items);
    payload.put("components", List.of(container));
    return payload;
  }

  private static Map<String, Object> text(String content) {
    return Map.of("type", TYPE_TEXT, "content", content);
  }

  private static String title(Map<String, Object> frame) {
    String message = line(escape(sanitize(Json.string(frame, "message"))));
    if (!message.isEmpty()) return truncate(message, 256);
    String player = escape(Json.string(frame, "player_name"));
    return switch (Json.string(frame, "event_type")) {
      case "ServerStart" -> "The server is online";
      case "ServerStop" -> "The server has stopped";
      case "PlayerJoin" -> player + " joined the server";
      case "PlayerLeave" -> player + " left the server";
      case "PlayerDeath" -> player + " died";
      case "PlayerAdvancement" -> player + " made an advancement";
      default -> "Minecraft";
    };
  }

  private static String icon(String eventType, String frame) {
    return switch (eventType) {
      case "ServerStart" -> "🟢";
      case "ServerStop" -> "🔴";
      case "PlayerJoin" -> "📥";
      case "PlayerLeave" -> "📤";
      case "PlayerDeath" -> "💀";
      case "PlayerAdvancement" ->
          switch (frame) {
            case "goal" -> "🎯";
            case "challenge" -> "🏆";
            default -> "🏅";
          };
      default -> "🖥️";
    };
  }

  private static int color(String configured, String eventType, String frame) {
    String hex = configured.startsWith("#") ? configured.substring(1) : configured;
    if (hex.length() == 3) {
      hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
    }
    if (hex.matches("[0-9a-fA-F]{6}")) return Integer.parseInt(hex, 16);
    if (eventType.equals("PlayerAdvancement")) {
      if (frame.equals("goal")) return 0x3B9EFF;
      if (frame.equals("challenge")) return 0xA78BFA;
    }
    return DEFAULT_COLORS.getOrDefault(eventType, 0x3B9EFF);
  }

  /** The player's head render; an offline-mode UUID is unknown to skin services, so the name is used. */
  static String headUrl(String name, String uuid) {
    if (uuid != null && ONLINE_UUID.matcher(uuid.toLowerCase()).matches()) {
      return "https://mc-heads.net/head/" + uuid.toLowerCase() + "/128";
    }
    if (name != null && name.matches("[A-Za-z0-9_.\\-*]{1,32}")) {
      return "https://mc-heads.net/head/" + name + "/128";
    }
    return null;
  }

  private static String duration(long seconds) {
    if (seconds < 60) return seconds + "s";
    long minutes = seconds / 60;
    long hours = minutes / 60;
    long days = hours / 24;
    StringBuilder sb = new StringBuilder();
    if (days > 0) sb.append(days).append("d ");
    if (hours % 24 > 0) sb.append(hours % 24).append("h ");
    if (minutes % 60 > 0) sb.append(minutes % 60).append("m");
    return sb.toString().trim();
  }

  private static String sanitize(String text) {
    return MENTION.matcher(text).replaceAll("[mention]");
  }

  private static String line(String text) {
    String collapsed = text.replaceAll("\\s+", " ").trim();
    return BLOCK_MARKER.matcher(collapsed).find() ? "\\" + collapsed : collapsed;
  }

  private static String escape(String text) {
    return text.replaceAll("([\\\\*_~`|])", "\\\\$1").replaceAll("\\s+", " ").trim();
  }

  private static String truncate(String text, int limit) {
    return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
  }

  // ─── HTTP ─────────────────────────────────────────────────────────────────

  private void post(String body, boolean retryOnRateLimit) {
    String separator = webhookUrl.contains("?") ? "&" : "?";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(webhookUrl + separator + "with_components=true"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status == 429 && retryOnRateLimit) {
        double retryAfter =
            Json.parseObject(response.body()).get("retry_after") instanceof Number n
                ? n.doubleValue()
                : 1.0;
        Thread.sleep((long) (Math.min(retryAfter, 10.0) * 1000));
        post(body, false);
      } else if (status == 404 || status == 401) {
        plugin.getLogger().warning("The webhook no longer exists. Create a new one and update majestic.webhook_url.");
      } else if (status < 200 || status >= 300) {
        plugin.getLogger().warning("Webhook returned HTTP " + status + ": " + truncate(response.body(), 300));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      plugin.getLogger().warning("Webhook delivery failed: " + e.getMessage());
    }
  }
}
