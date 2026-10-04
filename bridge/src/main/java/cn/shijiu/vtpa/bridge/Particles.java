package cn.shijiu.vtpa.bridge;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 粒子特效播放器 —— 就是 CMI 那套 {@code tpaWarmup / TeleportEffects} 的翻版。
 *
 * <p>预设串的写法跟 CMI 的 {@code Settings/ParticleEffects.yml} 一致，解析在
 * {@link FxSpec} 里（纯逻辑，不碰 Bukkit）；这里只负责<b>按 tick 把粒子撒出去</b>。
 *
 * <p>两种播法：
 * <ul>
 *   <li>{@code FOLLOW}：跟着某个玩家走（倒计时期间一直在他身上转）。人在服上就播，
 *       人下线了自动收尾，不会漏调度。</li>
 *   <li>{@code STATIC}：钉在某个坐标上（出发地 / 落点那一下）。</li>
 * </ul>
 *
 * <p>⚠️ 两条：
 * <ol>
 *   <li><b>粒子名是运行时解析的</b>（反射 + 别名表），不是编译期写死的枚举 ——
 *       因为 1.20.5 之后 {@code ENCHANTMENT_TABLE→ENCHANT}、{@code REDSTONE→DUST}
 *       都改过名，写死就只能在某个版本上跑。</li>
 *   <li>只有需要颜色的粒子（DUST）才带 {@code DustOptions}；别的粒子传了会抛异常。</li>
 * </ol>
 */
final class Particles {

    /** 一个人身上同时最多挂几段特效，超了先掐最老的。 */
    private static final int MAX_PER_PLAYER = 6;

    /** 认不出来的粒子名只报一次，别刷屏。 */
    private static final Map<String, Boolean> WARNED = new ConcurrentHashMap<>();

    /**
     * CMI 里那些老粒子名 → 现在（1.20.5+）的枚举名。
     * 用 list 是因为改名是分批的，一个老名可能对应好几个候选，一个个试过去。
     */
    private static final Map<String, List<String>> ALIASES = new HashMap<>();

    static {
        put("flying_glyph", "ENCHANT", "ENCHANTMENT_TABLE");
        put("reddust", "DUST", "REDSTONE");
        put("redstone", "DUST", "REDSTONE");
        put("dust", "DUST", "REDSTONE");
        put("crit", "CRIT", "CRIT_MAGIC");
        put("magiccrit", "ENCHANTED_HIT", "CRIT_MAGIC");
        put("heart", "HEART");
        put("smoke", "SMOKE");
        put("largesmoke", "LARGE_SMOKE");
        put("flame", "FLAME");
        put("portal", "PORTAL");
        put("spell", "ENTITY_EFFECT", "SPELL");
        put("witch", "WITCH");
        put("note", "NOTE");
        put("cloud", "CLOUD");
        put("snowball", "ITEM_SNOWBALL", "SNOWBALL");
        put("slime", "ITEM_SLIME", "SLIME");
        put("lava", "LAVA");
        put("dripwater", "DRIPPING_WATER", "DRIP_WATER");
        put("water", "DRIPPING_WATER");
        put("driplava", "DRIPPING_LAVA", "DRIP_LAVA");
        put("explode", "EXPLOSION", "POOF");
        put("firework", "FIREWORK", "FIREWORKS_SPARK");
        put("fireworks", "FIREWORK", "FIREWORKS_SPARK");
        put("glow", "GLOW");
        put("totem", "TOTEM_OF_UNDYING", "TOTEM");
        put("happyvillager", "HAPPY_VILLAGER", "VILLAGER_HAPPY");
        put("angryvillager", "ANGRY_VILLAGER", "VILLAGER_ANGRY");
        put("fallingdust", "FALLING_DUST");
        put("soul", "SOUL");
        put("soulflame", "SOUL_FIRE_FLAME");
        put("ash", "ASH");
        put("crimsonspore", "CRIMSON_SPORE");
        put("warpedspore", "WARPED_SPORE");
        put("nautilus", "NAUTILUS");
        put("dolphin", "DOLPHIN");
        put("endrod", "END_ROD");
        put("damage", "DAMAGE_INDICATOR");
        put("sweep", "SWEEP_ATTACK");
        put("instanteffect", "INSTANT_EFFECT");
        put("dustcolortransition", "DUST_COLOR_TRANSITION");
        put("block", "BLOCK");
        put("item", "ITEM");
    }

    private static void put(final String cmi, final String... candidates) {
        final List<String> list = new ArrayList<>();
        for (final String candidate : candidates) {
            list.add(candidate);
        }
        ALIASES.put(cmi, list);
    }

    private final JavaPlugin plugin;
    /** 玩家 UUID → 他身上正在跑的特效任务（按开始顺序）。 */
    private final Map<UUID, List<Task>> running = new ConcurrentHashMap<>();

    Particles(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // 对外
    // ------------------------------------------------------------------

    /** 跟着玩家播一段。ticks ≤ 0 时用预设自带的 dur。 */
    void playFollow(final UUID uuid, final String preset, final int ticks) {
        final FxSpec spec = FxSpec.parse(preset);
        if (spec == null) {
            return;
        }
        final Particle particle = resolve(spec.particle());
        if (particle == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            final Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                return;
            }
            trim(uuid);
            start(new Task(this, plugin, spec, particle, lifetime(spec, ticks), uuid, null), uuid);
        });
    }

    /** 钉在坐标上播一段。 */
    void playStatic(final Wire.Loc loc, final String preset, final int ticks) {
        final FxSpec spec = FxSpec.parse(preset);
        if (spec == null) {
            return;
        }
        final Particle particle = resolve(spec.particle());
        if (particle == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            final World world = loc.world() == null || loc.world().isEmpty()
                    ? null : Bukkit.getWorld(loc.world());
            if (world == null) {
                return;
            }
            // ⚠️ 必须是同步调度：异步线程里碰世界数据（spawnParticle）会炸
            start(new Task(this, plugin, spec, particle, lifetime(spec, ticks), null,
                    new Location(world, loc.x(), loc.y(), loc.z())), null);
        });
    }

    /** 停掉某人身上所有特效（倒计时结束 / 被打断）。 */
    void stop(final UUID uuid) {
        final List<Task> tasks = running.remove(uuid);
        if (tasks != null) {
            for (final Task task : tasks) {
                task.cancel();
            }
        }
    }

    /** 插件关掉时全停。 */
    void stopAll() {
        for (final UUID uuid : new ArrayList<>(running.keySet())) {
            stop(uuid);
        }
    }

    private void trim(final UUID uuid) {
        final List<Task> tasks = running.computeIfAbsent(uuid, key -> new ArrayList<>());
        while (tasks.size() >= MAX_PER_PLAYER) {
            tasks.remove(0).cancel();
        }
    }

    private void start(final Task task, final UUID uuid) {
        if (uuid != null) {
            running.computeIfAbsent(uuid, key -> new ArrayList<>()).add(task);
        }
        task.runTaskTimer(plugin, 0L, 1L);
    }

    /** 某个任务自己跑完了：把它从账本里摘掉，别让 list 无限涨。 */
    private void done(final UUID uuid, final Task task) {
        if (uuid == null) {
            return;
        }
        final List<Task> tasks = running.get(uuid);
        if (tasks == null) {
            return;
        }
        tasks.remove(task);
        if (tasks.isEmpty()) {
            running.remove(uuid, tasks);
        }
    }

    /** 播多久：消息里给了就用消息里的，没给就用预设自带的 dur。 */
    private static int lifetime(final FxSpec spec, final int ticks) {
        if (ticks > 0) {
            return ticks;
        }
        return spec.durationTicks() > 0 ? spec.durationTicks() : 20;
    }

    // ------------------------------------------------------------------
    // 粒子名 → 枚举
    // ------------------------------------------------------------------

    /**
     * 解析不出来返回 null（调用方直接当「不播」处理，别炸服）。
     *
     * <p>⚠️ 用插件自己的 logger 而不是 {@code Bukkit.getLogger()} —— 后者要 {@code Bukkit.server}
     * 已经初始化，在极端时机（比如 reload 的边缘）调会 NPE。
     */
    Particle resolve(final String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        final String key = rawName.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        final Particle direct = tryValue(key);
        if (direct != null) {
            return direct;
        }
        final List<String> candidates = ALIASES.get(rawName.trim().toLowerCase(Locale.ROOT));
        if (candidates != null) {
            for (final String candidate : candidates) {
                final Particle particle = tryValue(candidate);
                if (particle != null) {
                    return particle;
                }
            }
        }
        // 最后再试一次：把下划线全去掉（flyingglyph → flying_glyph）
        final Particle squashed = tryValue(key.replace("_", ""));
        if (squashed != null) {
            return squashed;
        }
        if (WARNED.putIfAbsent(key, Boolean.TRUE) == null) {
            plugin.getLogger().warning("不认识的粒子名「" + rawName + "」，这段特效跳过。"
                    + "（可用的名字见 Bukkit Particle 枚举，比如 ENCHANT / DUST / HEART / CRIT / FLAME）");
        }
        return null;
    }

    private static Particle tryValue(final String name) {
        try {
            return Particle.valueOf(name);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 每 tick 撒粒子
    // ------------------------------------------------------------------

    private static final class Task extends BukkitRunnable {

        private final Particles owner;
        private final FxSpec spec;
        private final Particle particle;
        private final int ticks;
        /** FOLLOW 模式才有；STATIC 模式为 null。 */
        private final UUID uuid;
        /** STATIC 模式才有；FOLLOW 模式为 null。 */
        private final Location fixed;
        private final double[] point = new double[3];
        private int tick;

        Task(final Particles owner, final JavaPlugin plugin, final FxSpec spec, final Particle particle,
             final int ticks, final UUID uuid, final Location fixed) {
            this.owner = owner;
            this.spec = spec;
            this.particle = particle;
            this.ticks = ticks;
            this.uuid = uuid;
            this.fixed = fixed;
        }

        @Override
        public void run() {
            if (tick >= ticks) {
                finish();
                return;
            }
            final int current = tick++;
            if (current % spec.interval() != 0) {
                return;
            }
            final Player player = uuid == null ? null : Bukkit.getPlayer(uuid);
            if (uuid != null && (player == null || !player.isOnline())) {
                // 人跑了（下线 / 换服）—— 没有跟随对象，直接收尾
                finish();
                return;
            }
            if (player != null && spec.hideWhenVanished()
                    && player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                return;
            }
            final Location base = base(player);
            if (base == null || base.getWorld() == null) {
                return;
            }
            final double yaw = spec.followYaw() && player != null
                    ? spec.yaw() + player.getLocation().getYaw() + spec.yawChange() * current
                    : spec.yaw() + spec.yawChange() * current;
            final double pitch = spec.followPitch() && player != null
                    ? spec.pitch() + player.getLocation().getPitch() + spec.pitchChange() * current
                    : spec.pitch() + spec.pitchChange() * current;

            final World world = base.getWorld();
            final boolean colored = Particle.DustOptions.class.equals(particle.getDataType());
            final double size = 1.0F;
            for (int i = 0; i < spec.count(); i++) {
                spec.point(current, i, yaw, pitch, point);
                if (colored) {
                    final int rgb = spec.color(current, i);
                    world.spawnParticle(particle,
                            base.getX() + point[0], base.getY() + point[1], base.getZ() + point[2],
                            1, 0D, 0D, 0D, 0D,
                            new Particle.DustOptions(Color.fromRGB(rgb), (float) size));
                } else {
                    world.spawnParticle(particle,
                            base.getX() + point[0], base.getY() + point[1], base.getZ() + point[2],
                            1, 0D, 0D, 0D, 0D);
                }
            }
        }

        /** 圆心：跟随模式取玩家脚下（+ 预设里的 offset），固定模式取那个坐标。 */
        private Location base(final Player player) {
            final double[] offset = spec.offset();
            if (fixed != null) {
                return fixed.clone().add(offset[0], offset[1], offset[2]);
            }
            return player.getLocation().add(offset[0], offset[1], offset[2]);
        }

        private void finish() {
            cancel();
            owner.done(uuid, this);
        }
    }
}
