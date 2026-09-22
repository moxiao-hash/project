package com.studypilot.automation.idea.protocol;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Minimal, deliberately strict JSON reader/writer for the plugin protocol.
 *
 * Only a flat object of scalar members is accepted. Nested objects, arrays, booleans and
 * floating point numbers are rejected outright, duplicate member names are rejected, and
 * anything after the closing brace is rejected. This is smaller and stricter than a
 * general-purpose parser, which is exactly what a fixed-shape protocol needs.
 */
public final class Json {

  public enum Type {
    STRING,
    INTEGER,
    NULL,
    INVALID
  }

  public static final class Field {
    public final String name;
    public final Type type;
    public final String value;

    Field(String name, Type type, String value) {
      this.name = name;
      this.type = type;
      this.value = value;
    }
  }

  public enum Reason {
    OK,
    MALFORMED,
    DUPLICATE_KEY,
    UNSUPPORTED_VALUE
  }

  public static final class Result {
    public final Reason reason;
    public final List<Field> fields;

    Result(Reason reason, List<Field> fields) {
      this.reason = reason;
      this.fields = fields;
    }
  }

  private Json() {}

  /** Parses a single flat JSON object. Never throws. */
  public static Result parseFlatObject(String text) {
    if (text == null) {
      return new Result(Reason.MALFORMED, null);
    }
    int i = 0;
    int n = text.length();
    i = skipWs(text, i);
    if (i >= n || text.charAt(i) != '{') {
      return new Result(Reason.MALFORMED, null);
    }
    i++;
    List<Field> fields = new ArrayList<>();
    Set<String> names = new LinkedHashSet<>();

    i = skipWs(text, i);
    if (i < n && text.charAt(i) == '}') {
      i++;
      i = skipWs(text, i);
      return i == n ? new Result(Reason.OK, fields) : new Result(Reason.MALFORMED, null);
    }

    while (true) {
      i = skipWs(text, i);
      if (i >= n || text.charAt(i) != '"') {
        return new Result(Reason.MALFORMED, null);
      }
      StringBuilder name = new StringBuilder();
      int[] cursor = new int[] {i};
      if (!readString(text, cursor, name)) {
        return new Result(Reason.MALFORMED, null);
      }
      i = cursor[0];
      i = skipWs(text, i);
      if (i >= n || text.charAt(i) != ':') {
        return new Result(Reason.MALFORMED, null);
      }
      i++;
      i = skipWs(text, i);
      if (i >= n) {
        return new Result(Reason.MALFORMED, null);
      }

      String key = name.toString();
      if (!names.add(key)) {
        return new Result(Reason.DUPLICATE_KEY, null);
      }

      char c = text.charAt(i);
      if (c == '"') {
        StringBuilder value = new StringBuilder();
        cursor = new int[] {i};
        if (!readString(text, cursor, value)) {
          return new Result(Reason.MALFORMED, null);
        }
        i = cursor[0];
        fields.add(new Field(key, Type.STRING, value.toString()));
      } else if (c == 'n') {
        if (!text.startsWith("null", i)) {
          return new Result(Reason.UNSUPPORTED_VALUE, null);
        }
        i += 4;
        fields.add(new Field(key, Type.NULL, null));
      } else if (c == '-' || (c >= '0' && c <= '9')) {
        int start = i;
        if (c == '-') {
          i++;
        }
        int digits = 0;
        while (i < n && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
          i++;
          digits++;
        }
        if (digits == 0) {
          return new Result(Reason.MALFORMED, null);
        }
        fields.add(new Field(key, Type.INTEGER, text.substring(start, i)));
      } else {
        // Booleans, arrays, nested objects and floats are not part of this protocol.
        return new Result(Reason.UNSUPPORTED_VALUE, null);
      }

      i = skipWs(text, i);
      if (i >= n) {
        return new Result(Reason.MALFORMED, null);
      }
      char separator = text.charAt(i);
      if (separator == ',') {
        i++;
        continue;
      }
      if (separator == '}') {
        i++;
        i = skipWs(text, i);
        return i == n ? new Result(Reason.OK, fields) : new Result(Reason.MALFORMED, null);
      }
      return new Result(Reason.MALFORMED, null);
    }
  }

  private static int skipWs(String text, int i) {
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == ' ' || c == '\t') {
        i++;
      } else {
        break;
      }
    }
    return i;
  }

  private static boolean readString(String text, int[] cursor, StringBuilder out) {
    int i = cursor[0];
    if (i >= text.length() || text.charAt(i) != '"') {
      return false;
    }
    i++;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '"') {
        cursor[0] = i + 1;
        return true;
      }
      if (c == '\\') {
        i++;
        if (i >= text.length()) {
          return false;
        }
        char esc = text.charAt(i);
        switch (esc) {
          case '"':
          case '\\':
          case '/':
            out.append(esc);
            break;
          case 'b':
            out.append('\b');
            break;
          case 'f':
            out.append('\f');
            break;
          case 'n':
            out.append('\n');
            break;
          case 'r':
            out.append('\r');
            break;
          case 't':
            out.append('\t');
            break;
          case 'u':
            if (i + 4 >= text.length()) {
              return false;
            }
            try {
              out.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
            } catch (NumberFormatException e) {
              return false;
            }
            i += 4;
            break;
          default:
            return false;
        }
        i++;
        continue;
      }
      if (c < 0x20) {
        return false;
      }
      out.append(c);
      i++;
    }
    return false;
  }

  /** Escapes a value for inclusion in a single-line JSON response. */
  public static String escape(String raw) {
    if (raw == null) {
      return "null";
    }
    StringBuilder sb = new StringBuilder(raw.length() + 8);
    sb.append('"');
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      switch (c) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        default:
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    sb.append('"');
    return sb.toString();
  }
}
