package org.modeljars.cli;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Terminal-aware rendering shared by the CLI commands. */
final class CliOutput {
  private static final String RESET = "\u001B[0m";
  private static final String BOLD_CYAN = "\u001B[1;36m";
  private static final String GREEN = "\u001B[32m";
  private static final String YELLOW = "\u001B[33m";
  private static final String CYAN = "\u001B[36m";
  private static final String DIM = "\u001B[2m";

  enum Format {
    TABLE,
    JSON,
    PLAIN;

    @Override
    public String toString() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  enum ColorMode {
    AUTO,
    ALWAYS,
    NEVER;

    @Override
    public String toString() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  enum Tone {
    NORMAL,
    SUCCESS,
    WARNING,
    INFO,
    MUTED
  }

  enum Alignment {
    LEFT,
    RIGHT
  }

  record Cell(String value, Tone tone) {
    Cell {
      value = Objects.requireNonNullElse(value, "");
      tone = Objects.requireNonNull(tone, "tone");
    }

    static Cell text(String value) {
      return new Cell(value, Tone.NORMAL);
    }
  }

  record Detail(String heading, Cell cell) {
    Detail {
      if (heading == null || heading.isBlank()) {
        throw new IllegalArgumentException("detail heading must not be blank");
      }
      cell = Objects.requireNonNull(cell, "cell");
    }

    static Detail text(String heading, String value) {
      return new Detail(heading, Cell.text(value));
    }
  }

  record Column(String heading, int minimumWidth, int maximumWidth, Alignment alignment) {
    Column {
      if (heading == null || heading.isBlank()) {
        throw new IllegalArgumentException("heading must not be blank");
      }
      if (minimumWidth < 1 || maximumWidth < minimumWidth) {
        throw new IllegalArgumentException("invalid column widths");
      }
      alignment = Objects.requireNonNull(alignment, "alignment");
    }

    static Column left(String heading, int minimumWidth, int maximumWidth) {
      return new Column(heading, minimumWidth, maximumWidth, Alignment.LEFT);
    }

    static Column right(String heading, int minimumWidth, int maximumWidth) {
      return new Column(heading, minimumWidth, maximumWidth, Alignment.RIGHT);
    }
  }

  private final PrintStream stream;
  private final Format format;
  private final boolean color;
  private final int width;

  CliOutput(PrintStream stream, Format format, ColorMode colorMode, int width) {
    this.stream = Objects.requireNonNull(stream, "stream");
    this.format = Objects.requireNonNull(format, "format");
    this.color = format == Format.TABLE && colorEnabled(colorMode);
    this.width = Math.max(40, width);
  }

  Format format() {
    return format;
  }

  int width() {
    return width;
  }

  void line(String text) {
    stream.println(text);
  }

  void success(String text) {
    stream.println(style("✓ " + text, Tone.SUCCESS));
  }

  void hint(String text) {
    stream.println(style(text, Tone.MUTED));
  }

  void section(String heading) {
    stream.println();
    stream.println(color ? BOLD_CYAN + heading.toUpperCase(Locale.ROOT) + RESET : heading.toUpperCase(Locale.ROOT));
  }

  void properties(Map<String, ?> values) {
    int labelWidth =
        values.keySet().stream().mapToInt(String::length).max().orElse(0);
    values.forEach(
        (label, value) -> {
          String paddedLabel = pad(label, labelWidth, Alignment.LEFT);
          stream.printf(
              Locale.ROOT,
              "  %s  %s%n",
              style(paddedLabel, Tone.MUTED),
              Objects.toString(value, ""));
        });
  }

  void table(List<Column> columns, List<List<Cell>> rows) {
    table(columns, rows, List.of());
  }

  void table(
      List<Column> columns,
      List<List<Cell>> rows,
      List<List<Detail>> details) {
    if (columns.isEmpty()) {
      return;
    }
    if (!details.isEmpty() && details.size() != rows.size()) {
      throw new IllegalArgumentException("table details do not match the row count");
    }
    rows.forEach(
        row -> {
          if (row.size() != columns.size()) {
            throw new IllegalArgumentException("table row does not match its column count");
          }
        });

    int[] widths = columnWidths(columns, rows);
    List<Cell> headings =
        columns.stream().map(column -> new Cell(column.heading(), Tone.INFO)).toList();
    printRow(columns, widths, headings, true);
    for (int index = 0; index < rows.size(); index++) {
      printRow(columns, widths, rows.get(index), false);
      if (!details.isEmpty()) {
        details.get(index).forEach(this::printDetail);
      }
    }
  }

  void json(Object value) {
    stream.println(Json.write(value));
  }

  private int[] columnWidths(List<Column> columns, List<List<Cell>> rows) {
    int[] widths = new int[columns.size()];
    for (int index = 0; index < columns.size(); index++) {
      Column column = columns.get(index);
      int natural = column.heading().length();
      for (List<Cell> row : rows) {
        natural = Math.max(natural, row.get(index).value().length());
      }
      widths[index] = Math.min(column.maximumWidth(), Math.max(column.minimumWidth(), natural));
    }

    int separators = Math.max(0, columns.size() - 1) * 2;
    int overflow = total(widths) + separators - width;
    while (overflow > 0) {
      int candidate = -1;
      int reducible = 0;
      for (int index = 0; index < columns.size(); index++) {
        int available = widths[index] - columns.get(index).minimumWidth();
        if (available > reducible) {
          candidate = index;
          reducible = available;
        }
      }
      if (candidate < 0) {
        break;
      }
      int reduction = Math.min(overflow, reducible);
      widths[candidate] -= reduction;
      overflow -= reduction;
    }
    return widths;
  }

  private void printRow(
      List<Column> columns, int[] widths, List<Cell> cells, boolean heading) {
    StringBuilder line = new StringBuilder();
    for (int index = 0; index < columns.size(); index++) {
      if (index > 0) {
        line.append("  ");
      }
      Column column = columns.get(index);
      Cell cell = cells.get(index);
      String value = truncate(cell.value(), widths[index]);
      String padded = pad(value, widths[index], column.alignment());
      line.append(heading ? style(padded, Tone.INFO) : style(padded, cell.tone()));
    }
    stream.println(stripTrailingSpaces(line.toString()));
  }

  private void printDetail(Detail detail) {
    stream.printf(
        Locale.ROOT,
        "  %s  %s%n",
        style(detail.heading(), Tone.MUTED),
        style(detail.cell().value(), detail.cell().tone()));
  }

  private String style(String value, Tone tone) {
    if (!color) {
      return value;
    }
    String prefix =
        switch (tone) {
          case NORMAL -> "";
          case SUCCESS -> GREEN;
          case WARNING -> YELLOW;
          case INFO -> CYAN;
          case MUTED -> DIM;
        };
    return prefix.isEmpty() ? value : prefix + value + RESET;
  }

  private static String truncate(String value, int width) {
    if (value.length() <= width) {
      return value;
    }
    if (width == 1) {
      return "…";
    }
    return value.substring(0, width - 1) + "…";
  }

  private static String pad(String value, int width, Alignment alignment) {
    int missing = Math.max(0, width - value.length());
    String spaces = " ".repeat(missing);
    return alignment == Alignment.RIGHT ? spaces + value : value + spaces;
  }

  private static int total(int[] values) {
    int total = 0;
    for (int value : values) {
      total += value;
    }
    return total;
  }

  private static String stripTrailingSpaces(String value) {
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == ' ') {
      end--;
    }
    return value.substring(0, end);
  }

  private static boolean colorEnabled(ColorMode mode) {
    return switch (mode) {
      case ALWAYS -> true;
      case NEVER -> false;
      case AUTO ->
          System.console() != null
              && System.getenv("NO_COLOR") == null
              && !"dumb".equalsIgnoreCase(System.getenv("TERM"));
    };
  }

  static String humanBytes(long bytes) {
    if (bytes < 0) {
      return "unknown";
    }
    String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
    double value = bytes;
    int unit = 0;
    while (value >= 1024.0 && unit < units.length - 1) {
      value /= 1024.0;
      unit++;
    }
    if (unit == 0) {
      return bytes + " B";
    }
    return String.format(Locale.ROOT, value >= 10.0 ? "%.1f %s" : "%.2f %s", value, units[unit]);
  }

  /** Minimal deterministic JSON writer for CLI output. */
  private static final class Json {
    private Json() {}

    static String write(Object value) {
      StringBuilder result = new StringBuilder();
      append(result, normalize(value), 0);
      return result.toString();
    }

    private static Object normalize(Object value) {
      if (value == null
          || value instanceof String
          || value instanceof Number
          || value instanceof Boolean) {
        return value;
      }
      if (value instanceof Map<?, ?> values) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        values.forEach((key, item) -> normalized.put(Objects.toString(key), normalize(item)));
        return normalized;
      }
      if (value instanceof Collection<?> values) {
        List<Object> normalized = new ArrayList<>();
        values.forEach(item -> normalized.add(normalize(item)));
        return normalized;
      }
      return value.toString();
    }

    private static void append(StringBuilder target, Object value, int indent) {
      if (value == null) {
        target.append("null");
      } else if (value instanceof String text) {
        quote(target, text);
      } else if (value instanceof Number || value instanceof Boolean) {
        target.append(value);
      } else if (value instanceof Map<?, ?> values) {
        appendObject(target, values, indent);
      } else if (value instanceof Collection<?> values) {
        appendArray(target, values, indent);
      } else {
        quote(target, value.toString());
      }
    }

    private static void appendObject(StringBuilder target, Map<?, ?> values, int indent) {
      target.append('{');
      if (!values.isEmpty()) {
        target.append('\n');
        int index = 0;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
          target.append(" ".repeat(indent + 2));
          quote(target, Objects.toString(entry.getKey()));
          target.append(": ");
          append(target, entry.getValue(), indent + 2);
          if (++index < values.size()) {
            target.append(',');
          }
          target.append('\n');
        }
        target.append(" ".repeat(indent));
      }
      target.append('}');
    }

    private static void appendArray(StringBuilder target, Collection<?> values, int indent) {
      target.append('[');
      if (!values.isEmpty()) {
        target.append('\n');
        int index = 0;
        for (Object value : values) {
          target.append(" ".repeat(indent + 2));
          append(target, value, indent + 2);
          if (++index < values.size()) {
            target.append(',');
          }
          target.append('\n');
        }
        target.append(" ".repeat(indent));
      }
      target.append(']');
    }

    private static void quote(StringBuilder target, String value) {
      target.append('"');
      for (int index = 0; index < value.length(); index++) {
        char character = value.charAt(index);
        switch (character) {
          case '"' -> target.append("\\\"");
          case '\\' -> target.append("\\\\");
          case '\b' -> target.append("\\b");
          case '\f' -> target.append("\\f");
          case '\n' -> target.append("\\n");
          case '\r' -> target.append("\\r");
          case '\t' -> target.append("\\t");
          default -> {
            if (character < 0x20) {
              target.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
            } else {
              target.append(character);
            }
          }
        }
      }
      target.append('"');
    }
  }
}
