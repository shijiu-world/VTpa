package cn.shijiu.vtpa;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * 配置里那些带 {@code &} 颜色码的文本怎么变成组件。
 *
 * <p>支持 {@code &0}-{@code &f}、{@code &k}-{@code &o}、{@code &r}、
 * {@code &#RRGGBB}（十六进制），{@code §} 也认。
 * 配置里写 {@code \n} 会变成真正的换行（悬停提示要换行时用）。
 */
public final class Colors {

    /** 用 {@code &} 当颜色符的 legacy 序列化器，开 hex 支持。 */
    public static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private Colors() {
    }

    /** 把 {@code &c你好} 这种串变成组件；null 当空串处理。 */
    public static Component colorize(final String legacy) {
        return SERIALIZER.deserialize(legacy == null ? "" : legacy);
    }

    /**
     * 剥掉所有颜色 / 格式码，只剩看得见的字。
     *
     * <p>用来判断「这条消息是不是空的」：配置里写 {@code ""} 当然算空，
     * 但只写了个 {@code &a}（没有任何文字）发出去也就是一条看不见的消息，
     * 一样不该发 —— 所以按剥色之后的结果判断。
     */
    public static String plain(final String legacy) {
        if (legacy == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder(legacy.length());
        for (int i = 0; i < legacy.length(); i++) {
            final char c = legacy.charAt(i);
            if (c != '&' && c != '§') {
                out.append(c);
                continue;
            }
            // 跳过颜色符本身和它后面跟着的那一位（&#RRGGBB 要跳 7 位）
            i++;
            if (i >= legacy.length()) {
                break;
            }
            if (legacy.charAt(i) == '#') {
                i += 6;
            }
        }
        return out.toString();
    }

    /** 这条文本发出去玩家能不能看见字？空串 / 只有颜色码都算看不见。 */
    public static boolean isBlank(final String legacy) {
        return plain(legacy).isBlank();
    }
}
