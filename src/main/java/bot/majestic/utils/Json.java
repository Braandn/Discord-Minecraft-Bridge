package bot.majestic.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader and writer for the bridge's frames, so the plugin ships without a JSON
 * dependency and works on every server version.
 *
 * <p>{@link #parse} returns {@link Map}, {@link List}, {@link String}, {@link Double} or {@link
 * Long}, {@link Boolean} and {@code null}; {@link #write} accepts the same types plus any {@link
 * Number}.
 */
public final class Json {

  private Json() {}

  // ── Writing ────────────────────────────────────────────────────────────────

  public static String write(Object value) {
    StringBuilder sb = new StringBuilder();
    writeValue(sb, value);
    return sb.toString();
  }

  private static void writeValue(StringBuilder sb, Object value) {
    if (value == null) {
      sb.append("null");
    } else if (value instanceof String s) {
      writeString(sb, s);
    } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
      sb.append(value);
    } else if (value instanceof Number n) {
      double d = n.doubleValue();
      sb.append(Double.isFinite(d) ? String.valueOf(d) : "null");
    } else if (value instanceof Map<?, ?> map) {
      sb.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : map.entrySet()) {
        if (!first) sb.append(',');
        first = false;
        writeString(sb, String.valueOf(e.getKey()));
        sb.append(':');
        writeValue(sb, e.getValue());
      }
      sb.append('}');
    } else if (value instanceof Iterable<?> list) {
      sb.append('[');
      boolean first = true;
      for (Object item : list) {
        if (!first) sb.append(',');
        first = false;
        writeValue(sb, item);
      }
      sb.append(']');
    } else {
      writeString(sb, value.toString());
    }
  }

  private static void writeString(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }

  // ── Reading ────────────────────────────────────────────────────────────────

  /** Parses one JSON document, throwing {@link IllegalArgumentException} on malformed input. */
  public static Object parse(String text) {
    Reader reader = new Reader(text);
    reader.skipWhitespace();
    Object value = reader.readValue();
    reader.skipWhitespace();
    if (!reader.atEnd()) throw reader.error("trailing data");
    return value;
  }

  /** Parses a JSON object, or returns an empty map for anything else. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> parseObject(String text) {
    try {
      Object value = parse(text);
      return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    } catch (IllegalArgumentException e) {
      return new LinkedHashMap<>();
    }
  }

  public static String string(Map<String, Object> obj, String key) {
    Object value = obj.get(key);
    return value instanceof String s ? s : value == null ? "" : String.valueOf(value);
  }

  public static long number(Map<String, Object> obj, String key, long fallback) {
    Object value = obj.get(key);
    return value instanceof Number n ? n.longValue() : fallback;
  }

  public static boolean bool(Map<String, Object> obj, String key) {
    return Boolean.TRUE.equals(obj.get(key));
  }

  public static List<String> strings(Map<String, Object> obj, String key) {
    List<String> out = new ArrayList<>();
    if (obj.get(key) instanceof List<?> list) {
      for (Object item : list) {
        if (item != null) out.add(String.valueOf(item));
      }
    }
    return out;
  }

  private static final class Reader {
    private static final int MAX_DEPTH = 32;

    private final String text;
    private int pos;
    private int depth;

    Reader(String text) {
      this.text = text == null ? "" : text;
    }

    boolean atEnd() {
      return pos >= text.length();
    }

    IllegalArgumentException error(String message) {
      return new IllegalArgumentException(message + " at " + pos);
    }

    void skipWhitespace() {
      while (!atEnd() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    Object readValue() {
      if (atEnd()) throw error("unexpected end");
      char c = text.charAt(pos);
      return switch (c) {
        case '{' -> readObject();
        case '[' -> readArray();
        case '"' -> readString();
        case 't' -> literal("true", Boolean.TRUE);
        case 'f' -> literal("false", Boolean.FALSE);
        case 'n' -> literal("null", null);
        default -> readNumber();
      };
    }

    private Object literal(String word, Object value) {
      if (!text.startsWith(word, pos)) throw error("unexpected token");
      pos += word.length();
      return value;
    }

    private Map<String, Object> readObject() {
      if (++depth > MAX_DEPTH) throw error("too deep");
      Map<String, Object> out = new LinkedHashMap<>();
      pos++;
      skipWhitespace();
      if (!atEnd() && text.charAt(pos) == '}') {
        pos++;
        depth--;
        return out;
      }
      while (true) {
        skipWhitespace();
        if (atEnd() || text.charAt(pos) != '"') throw error("expected key");
        String key = readString();
        skipWhitespace();
        expect(':');
        skipWhitespace();
        out.put(key, readValue());
        skipWhitespace();
        if (atEnd()) throw error("unterminated object");
        char c = text.charAt(pos++);
        if (c == '}') break;
        if (c != ',') throw error("expected , or }");
      }
      depth--;
      return out;
    }

    private List<Object> readArray() {
      if (++depth > MAX_DEPTH) throw error("too deep");
      List<Object> out = new ArrayList<>();
      pos++;
      skipWhitespace();
      if (!atEnd() && text.charAt(pos) == ']') {
        pos++;
        depth--;
        return out;
      }
      while (true) {
        skipWhitespace();
        out.add(readValue());
        skipWhitespace();
        if (atEnd()) throw error("unterminated array");
        char c = text.charAt(pos++);
        if (c == ']') break;
        if (c != ',') throw error("expected , or ]");
      }
      depth--;
      return out;
    }

    private void expect(char c) {
      if (atEnd() || text.charAt(pos) != c) throw error("expected " + c);
      pos++;
    }

    private String readString() {
      pos++;
      StringBuilder sb = new StringBuilder();
      while (true) {
        if (atEnd()) throw error("unterminated string");
        char c = text.charAt(pos++);
        if (c == '"') return sb.toString();
        if (c != '\\') {
          sb.append(c);
          continue;
        }
        if (atEnd()) throw error("bad escape");
        char e = text.charAt(pos++);
        switch (e) {
          case '"', '\\', '/' -> sb.append(e);
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          case 'u' -> {
            if (pos + 4 > text.length()) throw error("bad unicode escape");
            try {
              sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
            } catch (NumberFormatException ex) {
              throw error("bad unicode escape");
            }
            pos += 4;
          }
          default -> throw error("bad escape");
        }
      }
    }

    private Object readNumber() {
      int start = pos;
      while (!atEnd() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
      String raw = text.substring(start, pos);
      if (raw.isEmpty()) throw error("unexpected character");
      try {
        if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
          return Double.parseDouble(raw);
        }
        return Long.parseLong(raw);
      } catch (NumberFormatException ex) {
        throw error("bad number");
      }
    }
  }
}
