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
}
