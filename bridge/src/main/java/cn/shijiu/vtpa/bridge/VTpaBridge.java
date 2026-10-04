package cn.shijiu.vtpa.bridge;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VTpa 的子服配套组件。
 *
 * <p>代理（VTpa）拿不到坐标、也挪不动人，所以真正干活的是这里：
 * <ul>
 *   <li>{@code POS_REQ} → 回 {@code POS_RES}：把玩家坐标交给代理（问谁都行，不限于发消息的人）</li>
 *   <li>{@code TP} → 把玩家 teleport 到指定坐标</li>
 *   <li>{@code PING} → 回 {@code PONG}：告诉代理「这个服装了我」</li>
 * </ul>
 *
 * <p>⚠️ 两条铁律：
 * <ol>
 *   <li><b>插件消息是在 Netty 线程里收到的</b>，摸 Bukkit API 一律要
 *       {@code runTask} 切回主线程，否则会报「异步实体访问」一堆错。</li>
 *   <li><b>往代理发消息必须借一个在线玩家的通道</b> —— Bukkit 没有「服务器自己」的通道，
 *       没人在线时只能不发（这种时候代理那边本来也不会来问）。</li>
 * </ol>
 *
 * <p>传送时玩家可能还没完全进服（跨服切过来那一瞬间），所以落点会排队等最多约 10 秒，
 * 期间玩家一上线就补上，不会把落点丢掉。
 */
public final class VTpaBridge extends JavaPlugin implements PluginMessageListener, Listener {

    /** 跟代理那边 {@code bridge.channel} 保持一致。 */
    private static final String CHANNEL = "vtpa:main";

    /** 落点排队最多重试这么多次（每次 5 tick，约 10 秒）。 */
    private static final int MAX_ATTEMPTS = 40;

    private final Map<UUID, PendingTp> pending = new ConcurrentHashMap<>();
    private final Particles particles = new Particles(this);
    /** 倒计时期间的移动监视（代理发 WATCH 来开，这里负责实时判定）。 */
    /**
     * 移动监视。debug 那个 lambda 是<b>延迟求值</b>的 —— 在这儿（字段初始化时）
     * 插件还没初始化完，读不了 config.yml；等真有人被盯的时候才调，那时早 ready 了。
     */
    private final Watcher watcher = new Watcher((uuid, loc) -> reply(null, Wire.moved(uuid, loc)),
            () -> {
                try {
                    return getConfig().getBoolean("debug", false);
                } catch (final Exception ignored) {
                    return false;
                }
            });

    private static final class PendingTp {
        final Wire.Loc loc;
        int attempts;

        PendingTp(final Wire.Loc loc) {
            this.loc = loc;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();   // 生成 plugins/VTpaBridge/config.yml（debug 开关在那儿）
        Bukkit.getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(watcher, this);
        getLogger().info("[VTpaBridge] 已就绪，通道 " + CHANNEL
                + "（配合代理端 VTpa 使用，版本 " + getDescription().getVersion() + "）。");
    }

    @Override
    public void onDisable() {
        Bukkit.getMessenger().unregisterIncomingPluginChannel(this, CHANNEL, this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
        pending.clear();
        particles.stopAll();
        watcher.stopAll();
    }

    // ------------------------------------------------------------------
    // 收包
    // ------------------------------------------------------------------

    @Override
    public void onPluginMessageReceived(final String channel, final Player carrier, final byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        final Wire.Packet packet = Wire.read(message);
        if (packet == null) {
            return;
        }
        switch (packet.op()) {
            case Wire.OP_PING:
                reply(carrier, Wire.pong(getDescription().getVersion()));
                break;
            case Wire.OP_POS_REQ:
                handlePositionRequest(packet.uuid(), carrier);
                break;
            case Wire.OP_TP:
                pending.put(packet.uuid(), new PendingTp(packet.loc()));
                Bukkit.getScheduler().runTask(this, () -> applyTeleport(packet.uuid()));
                break;
            case Wire.OP_FX:
                handleEffect(packet);
                break;
            case Wire.OP_WATCH:
                // 记基准位置要在主线程
                Bukkit.getScheduler().runTask(this, () ->
                        watcher.watch(packet.uuid(), packet.amount(), packet.sub()));
                break;
            case Wire.OP_UNWATCH:
                watcher.unwatch(packet.uuid());
                break;
            case Wire.OP_SOUND:
                handleSound(packet);
                break;
            default:
                break;
        }
    }

    private void handlePositionRequest(final UUID uuid, final Player carrier) {
        // 取坐标必须回主线程
        Bukkit.getScheduler().runTask(this, () -> {
            final Player target = Bukkit.getPlayer(uuid);
            if (target == null || !target.isOnline()) {
                reply(carrier, Wire.positionNone(uuid));
                return;
            }
            final Location location = target.getLocation();
            final World world = location.getWorld();
            reply(carrier, Wire.positionResponse(uuid, new Wire.Loc(
                    world == null ? "" : world.getName(),
                    location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch())));
        });
    }

    /**
     * 粒子特效。三种：跟着人转（倒计时）、钉在坐标上（出发 / 落地）、停掉。
     *
     * <p>⚠️ 这一步失败（预设串写坏、粒子名不认识、世界不存在）都只是<b>没特效</b>，
     * 绝不影响传送 —— 特效是锦上添花，不能反过来把正事搞砸。
     */
    private void handleEffect(final Wire.Packet packet) {
        switch (packet.sub()) {
            case Wire.FX_FOLLOW:
                particles.playFollow(packet.uuid(), packet.text(), packet.ticks());
                break;
            case Wire.FX_STATIC:
                particles.playStatic(packet.loc(), packet.text(), packet.ticks());
                break;
            case Wire.FX_STOP:
                particles.stop(packet.uuid());
                break;
            default:
                break;
        }
    }

    /** 提示音。播不出来只是没声音，不影响任何流程。 */
    private void handleSound(final Wire.Packet packet) {
        final String spec = packet.text();
        if (spec == null || spec.isBlank()) {
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> {
            final Player target = Bukkit.getPlayer(packet.uuid());
            if (target != null) {
                Sounds.play(this, target, spec);
            }
        });
    }

    /** 借一个在线玩家的通道把消息发回代理。 */
    private void reply(final Player carrier, final byte[] payload) {
        Player sender = carrier;
        if (sender == null || !sender.isOnline()) {
            sender = firstOnlinePlayer();
        }
        if (sender == null) {
            return;
        }
        sender.sendPluginMessage(this, CHANNEL, payload);
    }

    private Player firstOnlinePlayer() {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOnline()) {
                return player;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 落地传送
    // ------------------------------------------------------------------

    private void applyTeleport(final UUID uuid) {
        final PendingTp entry = pending.get(uuid);
        if (entry == null) {
            return;
        }
        final Player target = Bukkit.getPlayer(uuid);
        if (target == null || !target.isOnline()) {
            // 还没进来 —— 再等等（上限 10 秒左右，之后放弃，别一直挂着）
            if (++entry.attempts >= MAX_ATTEMPTS) {
                pending.remove(uuid);
                getLogger().warning("[VTpaBridge] 等不到玩家 " + uuid + " 上线，落点丢弃。");
                return;
            }
            Bukkit.getScheduler().runTaskLater(this, () -> applyTeleport(uuid), 5L);
            return;
        }
        pending.remove(uuid);
        final Wire.Loc loc = entry.loc;
        World world = loc.world() == null || loc.world().isEmpty()
                ? null : Bukkit.getWorld(loc.world());
        if (world == null) {
            // 世界名对不上（比如子服改了世界名）就落在他当前的世界，坐标还是那个坐标
            getLogger().warning("[VTpaBridge] 找不到世界「" + loc.world() + "」，改用玩家当前世界。");
            world = target.getWorld();
        }
        target.teleport(new Location(world, loc.x(), loc.y(), loc.z(), loc.yaw(), loc.pitch()));
    }

    /** 玩家进服（跨服切过来也算）时，如果有排队的落点就立刻补上。 */
    @EventHandler
    public void onPlayerJoin(final PlayerJoinEvent event) {
        final UUID uuid = event.getPlayer().getUniqueId();
        if (!pending.containsKey(uuid)) {
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> applyTeleport(uuid));
    }
}
