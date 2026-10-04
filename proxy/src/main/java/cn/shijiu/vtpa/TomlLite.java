package cn.shijiu.vtpa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 极简 TOML 解析 —— 只认本插件 config.toml 里用到的那点语法：
 * <pre>
 *   [section]            # 分节，支持 [a.b] 这种写法
 *   key = "值"           # 引号可有可无
 *   key = ["a", "b"]     # 单行数组
 *   # 注释
 * </pre>
 *
 * <p>为什么自己写：第三方 TOML 库要额外依赖，而 Velocity 插件的 jar 里默认不打依赖，
 * 也没有 maven-shade 可用（离线环境装不上插件），带出去的类在运行时会
 * NoClassDefFoundError。这个 parser 零依赖，格式错了也不会炸，顶多取到默认值。
 *
 * <p>结果是一张扁平表：分节名用点拼到键前面，例如 {@code [general] cooldown-seconds = 5}
 * 变成 {@code "general.cooldown-seconds" = 5L}。
 */
public final class TomlLite {

    private TomlLite() {
    }

    /** 读文件并按 UTF-8 解析；顺手干掉 Windows 记事本爱加的 BOM。 */
    public static Map<String, Object> parse(final Path file) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return parse(text);
    }

    public static Map<String, Object> parse(final String text) {
        final Map<String, Object> out = new LinkedHashMap<>();
        String prefix = "";
        for (final String rawLine : text.split("\n", -1)) {
            final String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[")) {
                final int end = line.lastIndexOf(']');
                if (end > 1) {
                    final String section = unquote(line.substring(1, end).trim());
                    prefix = section.isEmpty() ? "" : section + ".";
                }
                continue;
            }
            final int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            final String key = unquote(line.substring(0, eq).trim());
            if (key.isEmpty()) {
                continue;
            }
            out.put(prefix + key, parseValue(line.substring(eq + 1).trim()));
        }
        return out;
    }

    private static Object parseValue(final String value) {
        if (value.isEmpty()) {
            return "";
        }
        final char first = value.charAt(0);
        if (first == '"' || first == '\'') {
            final int end = endOfQuoted(value, first);
            final String inner = end < 0 ? value.substring(1) : value.substring(1, end);
            return unescape(inner);
        }
        if (first == '[') {
            return parseArray(value);
        }
        return coerce(value);
    }

    /** 找收尾的引号（跳过被反斜杠转义的那个）。没找到就返回 -1，调用方容错到行尾。 */
    private static int endOfQuoted(final String value, final char quote) {
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i) == quote && value.charAt(i - 1) != '\\') {
                return i;
            }
        }
        return -1;
    }

    /** 解析单行数组：方括号后面还跟了 # 注释，就到收尾括号为止。 */
    private static List<String> parseArray(final String value) {
        final List<String> list = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 1; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (c == ']') {
                break;
            }
            if (c == ',') {
                add(list, current);
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        add(list, current);
        return list;
    }

    private static void add(final List<String> list, final StringBuilder s) {
        final String item = s.toString().trim();
        if (!item.isEmpty()) {
            list.add(item);
        }
    }

    private static Object coerce(final String value) {
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        try {
            return Long.valueOf(value);
        } catch (final NumberFormatException ignored) {
            // 不是整数，接着试小数
        }
        try {
            return Double.valueOf(value);
        } catch (final NumberFormatException ignored) {
            // 都不是那就当字符串
        }
        return unescape(value);
    }

    private static String unquote(final String s) {
        if (s.length() >= 2) {
            final char first = s.charAt(0);
            final char last = s.charAt(s.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private static String unescape(final String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        final StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            final char next = s.charAt(++i);
            switch (next) {
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case '\\': out.append('\\'); break;
                case '"': out.append('"'); break;
                case '\'': out.append('\''); break;
                default: out.append('\\').append(next); break;
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // 取值的小工具（类型不对就退回默认值，绝不因为配错了把插件搞挂）
    // ------------------------------------------------------------------

    public static String string(final Map<String, Object> map, final String key, final String def) {
        final Object o = map.get(key);
        return o == null ? def : String.valueOf(o);
    }

    public static boolean bool(final Map<String, Object> map, final String key, final boolean def) {
        final Object o = map.get(key);
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o != null) {
            return Boolean.parseBoolean(String.valueOf(o).trim());
        }
        return def;
    }

    public static long integer(final Map<String, Object> map, final String key, final long def) {
        final Object o = map.get(key);
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        if (o != null) {
            try {
                return Long.parseLong(String.valueOf(o).trim());
            } catch (final NumberFormatException ignored) {
                // 写歪了走默认
            }
        }
        return def;
    }

    public static double decimal(final Map<String, Object> map, final String key, final double def) {
        final Object o = map.get(key);
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        if (o != null) {
            try {
                return Double.parseDouble(String.valueOf(o).trim());
            } catch (final NumberFormatException ignored) {
                // 写歪了走默认
            }
        }
        return def;
    }

    /** 取字符串列表：配置里写成数组；只写了一个字符串也认。 */
    @SuppressWarnings("unchecked")
    public static List<String> list(final Map<String, Object> map, final String key, final List<String> def) {
        final Object o = map.get(key);
        if (o instanceof List) {
            return new ArrayList<>((List<String>) o);
        }
        if (o instanceof String && !((String) o).isEmpty()) {
            final List<String> one = new ArrayList<>();
            one.add((String) o);
            return one;
        }
        return def == null ? new ArrayList<>() : new ArrayList<>(def);
    }

    /** 把数组里的字符串全部转小写，用于服名这类不区分大小写的比较。 */
    public static List<String> lowercase(final List<String> in) {
        final List<String> out = new ArrayList<>(in.size());
        for (final String s : in) {
            out.add(s.toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
