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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传送提示音。格式跟 CMI 的 {@code Sounds:} 段一模一样：{@code 名字:音量:音调}。
 *
 * <p>音量 / 音调是从<b>名字之后</b>开始认的，所以名字可以带命名空间：
 * {@code minecraft:block.anvil.land:0.5:2}、{@code block.anvil.land:0.5:2}、
 * {@code block_anvil_land:0.5:2} 三种写法等价。
 *
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
    /** 名字 → 解析结果（省得每播一次就遍历一遍注册表）。 */
    private static final Map<String, Sound> CACHE = new ConcurrentHashMap<>();
    /** 解析失败的名字也记一下，别每次都白遍历。 */
    private static final Set<String> MISSED = ConcurrentHashMap.newKeySet();

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
        // ⚠️ 只在前两段之间切「命名空间:名字」—— 后面剩下的才是音量 / 音调。
        //    直接按 ':' 全切开的话，minecraft:block.anvil.land:1:1 会被切成
        //    rawName = "minecraft"（一个不存在的声音 → 静默无声）。
        //    判断依据很简单：第二段能解析成数字，那它是音量；否则它是名字的一部分。
        final String[] parts = spec.trim().split(":", 3);
        String rawName = parts[0].trim();
        String rest = "";
        if (parts.length > 1) {
            if (isNumber(parts[1])) {
                rest = parts.length > 2 ? parts[1] + ":" + parts[2] : parts[1];
            } else {
                rawName = parts[0].trim() + ":" + parts[1].trim();
                rest = parts.length > 2 ? parts[2] : "";
            }
        }
        if (rawName.isEmpty()) {
            return false;
        }
        final String[] volumeAndPitch = rest.isEmpty() ? new String[0] : rest.split(":");
        final float volume = volumeAndPitch.length > 0 ? number(volumeAndPitch[0], 1F) : 1F;
        final float pitch = volumeAndPitch.length > 1 ? number(volumeAndPitch[1], 1F) : 1F;

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
        final String key = name.toLowerCase(Locale.ROOT);
        final Sound cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (MISSED.contains(key)) {
            return null;
        }
        // 1) 现代注册表：block_anvil_land / block.anvil.land / minecraft:block.anvil.land
        Sound hit = fromRegistry(name);
        // 2) CMI 那种连一起的老写法：blockrespawnanchorcharge
        //    （去掉所有分隔符再比：block.respawn_anchor.charge → blockrespawnanchorcharge）
        if (hit == null) {
            hit = fuzzy(name);
        }
        // 3) 老枚举名：BLOCK_ANVIL_LAND
        if (hit == null) {
            try {
                hit = Sound.valueOf(name.toUpperCase(Locale.ROOT).replace('.', '_'));
            } catch (final Exception ignored) {
                hit = null;
            }
        }
        if (hit == null) {
            MISSED.add(key);
        } else {
            CACHE.put(key, hit);
        }
        return hit;
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

    /**
     * 「把分隔符全去掉再比」的模糊匹配。
     *
     * <p>线上 CMI 里存的是 Bukkit 1.8 时代的老枚举名，还常常把下划线省掉，
     * 比如 {@code blockrespawnanchorcharge} —— 现代注册表里叫
     * {@code block.respawn_anchor.charge}，直接查是查不到的（这正是之前音效全哑的原因）。
     */
    private static Sound fuzzy(final String name) {
        final String flat = flatten(name);
        if (flat.isEmpty()) {
            return null;
        }
        try {
            for (final Sound sound : Registry.SOUNDS) {
                if (flatten(sound.getKey().getKey()).equals(flat)) {
                    return sound;
                }
            }
        } catch (final Throwable ignored) {
            // 没有 Registry.SOUNDS（老版本）—— 退回去遍历老枚举
        }
        try {
            for (final Sound sound : Sound.values()) {
                if (flatten(sound.name()).equals(flat)) {
                    return sound;
                }
            }
        } catch (final Throwable ignored) {
            return null;
        }
        return null;
    }

    /** 只留小写字母和数字：block.respawn_anchor.charge → blockrespawnanchorcharge */
    static String flatten(final String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
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

    /** 这一段是不是数字 —— 用来分清「名字的第二段」和「音量」。 */
    private static boolean isNumber(final String text) {
        try {
            Float.parseFloat(text.trim());
            return true;
        } catch (final Exception e) {
            return false;
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
