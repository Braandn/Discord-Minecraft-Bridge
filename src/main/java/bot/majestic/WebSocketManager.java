package bot.majestic;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import bot.majestic.utils.Json;
import bot.majestic.utils.ServerInfo;

/**
 * The persistent connection to the Majestic API.
 *
 * <p>Outbound, Minecraft events go out as JSON frames and {@code RegisterCommands} is sent on every
 * open. Inbound, {@code DiscordMessage} frames are shown in chat and {@code DiscordCommand} frames
 * run through {@link DiscordCommandExecutor}. Credentials travel as handshake headers only.
 *
 * <p>Java's WebSocket allows one outstanding send at a time, so every send is chained onto the
 * previous one rather than issued from whichever thread produced it.
 */
public final class WebSocketManager {

  private static final long MIN_BACKOFF_SECONDS = 5;
  private static final long MAX_BACKOFF_SECONDS = 120;
  private static final long AUTH_BACKOFF_SECONDS = 300;
  private static final long PING_INTERVAL_SECONDS = 30;
  private static final int SYNC_WAIT_MS = 5000;
  private static final int MAX_QUEUED = 500;

  private final MajesticBot plugin;
  private final String wsUrl;
  private final String apiKey;
  private final String apiSecret;

  private final HttpClient httpClient;
  private final ScheduledExecutorService scheduler;
  private final DiscordCommandExecutor commandExecutor;
  private final ChatRelay chatRelay;

  private final AtomicBoolean connected = new AtomicBoolean(false);
  private final AtomicBoolean shutdown = new AtomicBoolean(false);
  private final ConcurrentLinkedDeque<String> pendingQueue = new ConcurrentLinkedDeque<>();

  private volatile WebSocket webSocket;
  private volatile long connectedSince;
  private volatile String lastError = "";
  private long backoffSeconds = MIN_BACKOFF_SECONDS;
  private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
  private ScheduledFuture<?> pingTask;
  private boolean warnedQueueFull;

  public WebSocketManager(MajesticBot plugin, String wsUrl, String apiKey, String apiSecret) {
    this.plugin = plugin;
    this.wsUrl = wsUrl == null ? "" : wsUrl.trim();
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.apiSecret = apiSecret == null ? "" : apiSecret.trim();
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    this.commandExecutor = new DiscordCommandExecutor(plugin, this);
    this.chatRelay = new ChatRelay(plugin);
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "majestic-ws");
              t.setDaemon(true);
              return t;
            });
  }

  /** Whether config.yml holds real, non-placeholder credentials. */
  public boolean isConfigured() {
    return !wsUrl.isEmpty()
        && !apiKey.isEmpty()
        && !apiSecret.isEmpty()
        && !apiKey.equals("YOUR_API_KEY_HERE")
        && !apiSecret.equals("YOUR_API_SECRET_HERE");
  }

  public boolean isConnected() {
    return connected.get();
  }

  public long getConnectedSince() {
    return connectedSince;
  }

  public String getLastError() {
    return lastError;
  }

  public int getQueuedCount() {
    return pendingQueue.size();
  }

  public void connect() {
    if (!isConfigured()) {
      lastError = "API credentials are not configured";
      plugin
          .getLogger()
          .warning(
              "WebSocket not started: set majestic.api_key and majestic.api_secret in config.yml.");
      return;
    }
    plugin.getLogger().info("Connecting to " + wsUrl);
    scheduler.execute(this::doConnect);
  }

  private void doConnect() {
    if (shutdown.get()) return;
    try {
      httpClient
          .newWebSocketBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .header("X-Api-Key", apiKey)
          .header("X-Api-Secret", apiSecret)
          .header("User-Agent", "MajesticBot-Minecraft/" + ServerInfo.pluginVersion())
          .buildAsync(URI.create(wsUrl), new Listener())
          .whenComplete(
              (ws, ex) -> {
                if (ex != null) handleConnectFailure(ex);
              });
    } catch (RuntimeException e) {
      handleConnectFailure(e);
    }
  }

  private void handleConnectFailure(Throwable ex) {
    Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
    long delay = nextBackoff();
    if (cause instanceof WebSocketHandshakeException hs) {
      int status = hs.getResponse().statusCode();
      String body = String.valueOf(hs.getResponse().body());
      String message = Json.string(Json.parseObject(body), "message");
      if (status == 401 || status == 403) {
        lastError = "credentials rejected (HTTP " + status + ")";
        plugin
            .getLogger()
            .warning(
                "The Majestic API rejected the API key or secret. Regenerate them in the"
                    + " dashboard and run /majestic reload.");
        delay = AUTH_BACKOFF_SECONDS;
      } else {
        lastError = message.isEmpty() ? "HTTP " + status : message;
        plugin.getLogger().warning("WebSocket handshake failed: " + lastError);
      }
    } else {
      lastError = String.valueOf(cause.getMessage());
      plugin.getLogger().warning("WebSocket connection failed: " + lastError);
    }
    scheduleReconnect(delay);
  }

  private synchronized long nextBackoff() {
    long current = backoffSeconds;
    backoffSeconds = Math.min(backoffSeconds * 2, MAX_BACKOFF_SECONDS);
    return current + ThreadLocalRandom.current().nextLong(0, Math.max(1, current / 2));
  }

  private void scheduleReconnect(long delaySeconds) {
    if (shutdown.get()) return;
    plugin.getLogger().info("Reconnecting in " + delaySeconds + " seconds.");
    try {
      scheduler.schedule(this::doConnect, delaySeconds, TimeUnit.SECONDS);
    } catch (RuntimeException ignored) {
      // Scheduler already shut down by a reload.
    }
  }

  /** Sends a frame, queueing it while the socket is down; the oldest frames go first if full. */
  public void send(String json) {
    if (!isConfigured() || shutdown.get()) return;
    WebSocket ws = webSocket;
    if (connected.get() && ws != null) {
      enqueueSend(ws, json);
      return;
    }
    pendingQueue.offerLast(json);
    while (pendingQueue.size() > MAX_QUEUED) {
      pendingQueue.pollFirst();
      if (!warnedQueueFull) {
        warnedQueueFull = true;
        plugin
            .getLogger()
            .warning("Not connected and " + MAX_QUEUED + " events are queued; dropping the oldest.");
      }
    }
  }

  private synchronized CompletableFuture<?> enqueueSend(WebSocket ws, String json) {
    sendChain =
        sendChain
            .handle((ok, err) -> null)
            .thenCompose(ignored -> ws.sendText(json, true))
            .whenComplete(
                (ok, err) -> {
                  if (err != null) {
                    plugin.getLogger().warning("WebSocket send failed: " + err.getMessage());
                  }
                });
    return sendChain;
  }

  /** Sends a frame and waits until it has been written, for the shutdown notice. */
  public void sendSync(String json) {
    if (!isConfigured()) return;
    long deadline = System.currentTimeMillis() + SYNC_WAIT_MS;
    while (!connected.get() && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    WebSocket ws = webSocket;
    if (!connected.get() || ws == null) return;
    try {
      enqueueSend(ws, json).get(SYNC_WAIT_MS, TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      plugin.getLogger().warning("Failed to send the shutdown notice: " + e.getMessage());
    }
  }

  /** Closes the connection and stops reconnecting. */
  public void disconnect() {
    shutdown.set(true);
    connected.set(false);
    WebSocket ws = webSocket;
    if (ws != null) {
      try {
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "Plugin disabled").get(2, TimeUnit.SECONDS);
      } catch (Exception ignored) {
        ws.abort();
      }
    }
    scheduler.shutdownNow();
  }

  // ─── Listener ─────────────────────────────────────────────────────────────

  private class Listener implements WebSocket.Listener {

    private final StringBuilder buffer = new StringBuilder();

    @Override
    public void onOpen(WebSocket ws) {
      webSocket = ws;
      connected.set(true);
      connectedSince = System.currentTimeMillis();
      lastError = "";
      warnedQueueFull = false;
      synchronized (WebSocketManager.this) {
        backoffSeconds = MIN_BACKOFF_SECONDS;
        sendChain = CompletableFuture.completedFuture(null);
      }
      plugin.getLogger().info("Connected to the Majestic API.");

      enqueueSend(ws, plugin.buildCommandsJson());
      String pending;
      while ((pending = pendingQueue.pollFirst()) != null) {
        enqueueSend(ws, pending);
      }

      pingTask =
          scheduler.scheduleAtFixedRate(
              () -> {
                if (connected.get()) ws.sendPing(ByteBuffer.allocate(0));
              },
              PING_INTERVAL_SECONDS,
              PING_INTERVAL_SECONDS,
              TimeUnit.SECONDS);
      ws.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
      buffer.append(data);
      if (buffer.length() > 1_000_000) {
        buffer.setLength(0);
        ws.abort();
        return null;
      }
      if (last) {
        String frame = buffer.toString();
        buffer.setLength(0);
        try {
          handleIncoming(frame);
        } catch (RuntimeException e) {
          plugin.getLogger().warning("Could not handle a frame from the API: " + e.getMessage());
        }
      }
      ws.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
      plugin.getLogger().info("Connection closed (" + statusCode + "): " + reason);
      lost();
      return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
      lastError = String.valueOf(error.getMessage());
      plugin.getLogger().warning("WebSocket error: " + lastError);
      lost();
    }

    private void lost() {
      if (pingTask != null) pingTask.cancel(false);
      if (connected.getAndSet(false)) {
        scheduleReconnect(nextBackoff());
      }
    }
  }

  // ─── Inbound ──────────────────────────────────────────────────────────────

  private void handleIncoming(String json) {
    Map<String, Object> frame = Json.parseObject(json);
    String eventType = Json.string(frame, "event_type");
    switch (eventType) {
      case "DiscordMessage" -> chatRelay.show(frame);
      case "DiscordCommand" ->
          commandExecutor.handle(
              Json.string(frame, "command"),
              Json.strings(frame, "args"),
              Json.string(frame, "discord_user"),
              Json.string(frame, "discord_user_id"),
              Json.strings(frame, "discord_role_ids"));
      case "" -> {
        // An acknowledgement; only a rejection is worth surfacing.
        if (frame.containsKey("success") && !Json.bool(frame, "success")) {
          String message = Json.string(frame, "message");
          if (!message.equals(lastError)) {
            lastError = message;
            plugin.getLogger().warning("Majestic API: " + message);
          }
        }
      }
      default -> {}
    }
  }
}
