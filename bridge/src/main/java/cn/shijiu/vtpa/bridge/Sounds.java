package cn.shijiu.vtpa.bridge;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传送提示音。格式跟 CMI 的 {@code Sounds:} 段一模一样：{@code 名字:音量:音调}。
 *
 * <p>例（这几个就是线上 CMI 现在用的）：
 * <pre>
 *   TpaRequest:   block_anvil_land:0.5:2
 *   CommandWarmup: blockrespawnanchorcharge:1:1
 *   TeleportUp:   entity_enderman_teleport:2:1
 *   TeleportDown: entity_enderman_teleport:0.2:1
 * </pre>
 *
 * <p>🔴 声音名跟粒子名一样<b>运行时解析</b>：1.20.5 之后 Mojang 把一声音也改了名
 * （{@code BLOCK_ANVIL_LAND} → {@code block.anvil.land}），写死枚举就只能在某一个版本上跑。
 * 这里按「注册表 → 老枚举名 → 原样字符串」三级回退，认不出来就<b>不播并记一条警告</b>，
 * 绝不因为一个音效把传送搞挂。
 */
public final class Sounds {

    /** 认不出来的名字只警告一次，免得每秒刷屏。 */
    private static final Map<String, Boolean> WARNED = new ConcurrentHashMap<>();

    private Sounds() {
    }

    /**
     * 给这个玩家播一段声音。
     *
     * @param spec 形如 {@code block_anvil_land:0.5:2}；空串 / null 表示不播
     * @return 真的播了吗（false = 跳过；调用方不用管，也不该因此中断流程）
     */
    public static boolean play(final JavaPlugin plugin, final Player player, final String spec) {
        if (player == null || !player.isOnline() || spec == null || spec.isBlank()) {
            return false;
        }
        final String[] parts = spec.trim().split(":");
        final String rawName = parts[0].trim();
        if (rawName.isEmpty()) {
            return false;
        }
        final float volume = parts.length > 1 ? number(parts[1], 1F) : 1F;
        final float pitch = parts.length > 2 ? number(parts[2], 1F) : 1F;

        final Location at = player.getLocation();
        final Sound resolved = resolve(rawName);
        try {
            if (resolved != null) {
                player.playSound(at, resolved, volume, pitch);
            } else {
                // 认不出枚举也没关系 —— Bukkit 的字符串版本会自己去查注册表
                player.playSound(at, key(rawName), volume, pitch);
            }
            return true;
        } catch (final Exception e) {
            warn(plugin, rawName, String.valueOf(e));
            return false;
        }
    }

    /** 认不出来就返回 null（调用方会退回用字符串播）。 */
    static Sound resolve(final String rawName) {
        final String name = rawName.trim();
        // 1) 现代注册表：block_anvil_land / block.anvil.land / minecraft:block.anvil.land
        final Sound byRegistry = fromRegistry(name);
        if (byRegistry != null) {
            return byRegistry;
        }
        // 2) 老枚举名：BLOCK_ANVIL_LAND
        try {
            return Sound.valueOf(name.toUpperCase(Locale.ROOT).replace('.', '_'));
        } catch (final Exception ignored) {
            return null;
        }
    }

    private static Sound fromRegistry(final String name) {
        try {
            final String dotted = name.replace('_', '.');
            NamespacedKey key = NamespacedKey.fromString(dotted);
            if (key == null) {
                key = NamespacedKey.minecraft(dotted);
            }
            return Registry.SOUNDS.get(key);
        } catch (final Throwable ignored) {
            // 老版本 Bukkit 没有 Registry.SOUNDS —— 走下面的回退就行
            return null;
        }
    }

    /** 给字符串版 playSound 用的 key：保证是 {@code minecraft:xxx} 这种能查到的形式。 */
    private static String key(final String rawName) {
        final String dotted = rawName.trim().replace('_', '.');
        return dotted.contains(":") ? dotted : "minecraft:" + dotted;
    }

    private static float number(final String text, final float fallback) {
        try {
            return Float.parseFloat(text.trim());
        } catch (final Exception e) {
            return fallback;
        }
    }

    private static void warn(final JavaPlugin plugin, final String name, final String detail) {
        if (WARNED.putIfAbsent(name, Boolean.TRUE) != null) {
            return;
        }
        final String message = "[VTpaBridge] 声音「" + name + "」播不出来（" + detail
                + "），这段音效跳过。可写的名字参考 Bukkit Sound 枚举，"
                + "比如 block_anvil_land / entity_enderman_teleport / blockrespawnanchorcharge。";
        if (plugin != null && plugin.getLogger() != null) {
            plugin.getLogger().warning(message);
        } else {
            Bukkit.getLogger().warning(message);
        }
    }
}
