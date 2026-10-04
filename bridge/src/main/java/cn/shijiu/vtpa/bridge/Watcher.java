package cn.shijiu.vtpa.bridge;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 倒计时期间的移动监视 —— <b>在子服做</b>，跟 CMI 一个路子。
 *
 * <p>为什么不在代理端做：代理只能每隔几百毫秒问一次坐标（还有一次网络往返），
 * 慢半拍，玩家走一小段它甚至不知道。子服这边是 {@code PlayerMoveEvent}，
 * 每动一下立刻就知道，判定准、反应快。
 *
 * <p>流程：代理在开始倒计时时发 {@code WATCH}（带容差和 flags），这里把玩家
 * <b>当时</b>的位置记成基准；之后他一动超过容差就回调一次「他动了」，并自动解除监视
 * （一次倒计时只报一次，不会刷屏）。
 *
 * <p><b>默认容差是 0</b> —— 也就是「动一下就取消」，跟线上 CMI 的 {@code Tpa.Move: false}
 * 一个体感（CMI 的判据其实是「前一帧和这一帧的方块格不同了」，0 比它还干脆一点：
 * 格子里挪一丁点、被水冲一下都算动）。只转视角不算动 —— 坐标没变，CMI 也不算。
 */
public final class Watcher implements Listener {

    /** 浮点噪声的兜底：1e-9 格，玩家再怎么“没动”也动不出这么点距离。 */
    private static final double EPSILON = 1e-9D;

    /** 谁动了就回调一次：参数是玩家 UUID 和他当前位置。 */
    public interface OnMove {
        void moved(UUID uuid, Wire.Loc loc);
    }

    private final Map<UUID, Watch> watching = new ConcurrentHashMap<>();
    private final OnMove onMove;

    private static final class Watch {
        /** 基准位置（倒计时开始那一瞬间站的地方）。 */
        final Location base;
        final double tolerance;
        final boolean ignoreY;

        Watch(final Location base, final double tolerance, final boolean ignoreY) {
            this.base = base;
            this.tolerance = tolerance;
            this.ignoreY = ignoreY;
        }
    }

    public Watcher(final OnMove onMove) {
        this.onMove = onMove;
    }

    /** 开始盯。玩家不在线 / 还没进服就直接放弃（代理那边会退回轮询或干脆不检测）。 */
    public void watch(final UUID uuid, final double tolerance, final int flags) {
        final Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        watching.put(uuid, new Watch(player.getLocation(),
                Math.max(0D, tolerance), (flags & Wire.WATCH_IGNORE_Y) != 0));
    }

    public void unwatch(final UUID uuid) {
        watching.remove(uuid);
    }

    public void stopAll() {
        watching.clear();
    }

    public boolean isWatching(final UUID uuid) {
        return watching.containsKey(uuid);
    }

    /**
     * 只在有人被盯的时候才干活 —— 平时这个监听器几乎零开销。
     *
     * <p>⚠️ 用 {@code MONITOR} 优先级且 {@code ignoreCancelled}：别的插件（比如区域保护、
     * 反作弊）把这次移动取消了的话，我们也不该算他动了。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        if (watching.isEmpty()) {
            return;
        }
        final UUID uuid = event.getPlayer().getUniqueId();
        final Watch watch = watching.get(uuid);
        if (watch == null) {
            return;
        }
        final Location to = event.getTo();
        if (to == null) {
            return;
        }
        // 换世界了 —— 不管动了多远都算动（代理那边 cancel-on-world-change 也是这个意思）
        final boolean worldChanged = watch.base.getWorld() != null
                && to.getWorld() != null
                && !watch.base.getWorld().equals(to.getWorld());
        if (!worldChanged && distance(watch, to) <= watch.tolerance + EPSILON) {
            return;
        }
        // 一次倒计时只报一次，报完就解除
        watching.remove(uuid);
        try {
            onMove.moved(uuid, locOf(to));
        } catch (final Exception e) {
            Bukkit.getLogger().warning("[VTpaBridge] 上报移动时出错（忽略）：" + e);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        watching.remove(event.getPlayer().getUniqueId());
    }

    private static double distance(final Watch watch, final Location to) {
        final double dx = to.getX() - watch.base.getX();
        final double dz = to.getZ() - watch.base.getZ();
        if (watch.ignoreY) {
            return Math.sqrt(dx * dx + dz * dz);
        }
        final double dy = to.getY() - watch.base.getY();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 把 Bukkit 的 Location 转成协议里的 Loc。 */
    private static Wire.Loc locOf(final Location location) {
        return new Wire.Loc(
                location.getWorld() == null ? "" : location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }
}
