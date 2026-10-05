package cn.shijiu.vtpa;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 代理 ↔ 子服配套组件（VTpaBridge）的通信层。
 *
 * <p>代理自己<b>拿不到坐标，也没法把人挪位置</b> —— 它能做的只有「把人从 A 服切到 B 服」。
 * 所以真正的落点传送全靠子服那个小插件帮忙：
 * <ol>
 *   <li>代理问：{@code POS_REQ} → 子服回 {@code POS_RES}</li>
 *   <li>代理发：{@code TP} → 子服把人 teleport 过去</li>
 * </ol>
 *
 * <p>子服没装桥接时这套全部失效，走 {@code bridge.missing} 配置的兜底策略
 * （见 {@link Configuration#bridgeMissing()}）。
 *
 * <p>「这个服装没装桥接」靠心跳判断：定期给有人的服发 {@code PING}，
 * 回 {@code PONG} 的就记进 {@link #ready}。这套是<b>失效安全</b>的 ——
 * 误判成「没装」最坏只是拒绝一次请求，不会把人传到莫名其妙的地方。
 * 记进 ready 的服还会定期过期（见 {@link #expireStale()}），子服重启 / 桥接卸载之后
 * 不会一直被当成「还在」。
 */
public final class Backend {

    private final VTpa plugin;
    private final Logger logger;
    private final MinecraftChannelIdentifier channel;

    /** 已知装了桥接的服务器名（小写）。 */
    private final Set<String> ready = ConcurrentHashMap.newKeySet();
    /** 各子服桥接的版本号（小写服名 → 版本串）。用来判断它支不支持新协议。 */
    private final Map<String, String> versions = new ConcurrentHashMap<>();
    /** 各子服最后一次回 PONG 的时间戳（小写服名 → 毫秒）。用来给 {@link #ready} 过期。 */
    private final Map<String, Long> lastPong = new ConcurrentHashMap<>();
    /**
     * 正在等坐标回包的查询（按玩家 UUID，<b>同一人可能同时挂着好几条</b>）。
     *
     * <p>为什么是队列不是单个：倒计时的轮询、落点锁定、{@code /tpaworld} 挨个问 ——
     * 这几条会同时向同一个人发问。覆盖式存放会把前一个 callback 永远丢下
     * （那个人就一直等不到回话），所以按 FIFO 排队，先问的先答。
     */
    private final Map<UUID, Deque<Pending>> pending = new ConcurrentHashMap<>();

    private final class Pending {
        final Consumer<Optional<Wire.Loc>> callback;
        /** 发起查询时那个人所在的服（小写）—— 回包不是这个服发的就不认。 */
        final String server;
        volatile ScheduledTask timeout;

        Pending(final Consumer<Optional<Wire.Loc>> callback, final String server) {
            this.callback = callback;
            this.server = server;
        }
    }

    public Backend(final VTpa plugin) {
        this.plugin = plugin;
        this.logger = plugin.logger();
        this.channel = parseChannel(plugin.configuration().bridgeChannel());
    }

    /**
     * 通道名在起服时注册一次，中途改 {@code bridge.channel} 不会生效（要重启代理）。
     * 格式 {@code 命名空间:名字}，写歪了退回 {@code vtpa:main}。
     */
    private static MinecraftChannelIdentifier parseChannel(final String raw) {
        String namespace = "vtpa";
        String name = "main";
        if (raw != null && !raw.isBlank()) {
            final int colon = raw.indexOf(':');
            if (colon > 0 && colon < raw.length() - 1) {
                namespace = raw.substring(0, colon);
                name = raw.substring(colon + 1);
            } else {
                namespace = "minecraft";
                name = raw;
            }
        }
        return MinecraftChannelIdentifier.create(namespace, name);
    }

    public MinecraftChannelIdentifier channel() {
        return channel;
    }

    /** 起服时把通道登记上，否则子服发回来的消息 Velocity 不认。 */
    public void register() {
        plugin.proxy().getChannelRegistrar().register(channel);
    }

    // ------------------------------------------------------------------
    // 收包
    // ------------------------------------------------------------------

    /** 子服发回来的消息统一在这里处理。 */
    public void handle(final PluginMessageEvent event) {
        if (!channel.getId().equals(event.getIdentifier().getId())) {
            return;
        }
        final Wire.Packet packet = Wire.read(event.getData());
        if (packet == null) {
            return;
        }
        // ⚠️ 插件消息一律不要往客户端转发（这是代理和子服之间的私聊）
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        // ⚠️ 只认子服发来的。这个通道玩家（以及别的插件）也能往里塞包 ——
        //    不看来路的话谁都能伪造一条「这就是坐标」/「他动了」，
        //    把别人的倒计时掐掉、或者把人传到伪造的点上。
        if (!(event.getSource() instanceof ServerConnection)) {
            if (plugin.configuration().debug()) {
                logger.info("[vtpa] 忽略一条不是子服发来的插件消息（source = "
                        + (event.getSource() == null ? "null"
                                : event.getSource().getClass().getSimpleName()) + "）。");
            }
            return;
        }
        final String from = serverNameOf(event.getSource());
        switch (packet.op()) {
            case Wire.OP_PONG:
                if (from != null) {
                    final String lower = from.toLowerCase(java.util.Locale.ROOT);
                    lastPong.put(lower, System.currentTimeMillis());
                    final boolean first = ready.add(lower);
                    final String version = packet.text() == null ? "" : packet.text();
                    versions.put(lower, version);
                    if (first) {
                        logger.info("[vtpa] 子服 " + from + " 的桥接组件已就位（" + version + "）"
                                + (supports(lower, 1, 1, 0)
                                ? "，支持子服端移动检测。" : "，版本偏老，移动检测退回代理轮询。"));
                    }
                }
                break;
            case Wire.OP_POS_RES:
                // 坐标要验一遍：世界名是空的、或者坐标是 NaN / 无穷大，
                // 拿去当落点会把人扔到莫名其妙的地方 —— 一律按「没拿到」处理
                if (!plausible(packet.loc())) {
                    if (plugin.configuration().debug()) {
                        logger.info("[vtpa] 子服 " + from + " 回的坐标不合法（" + packet.loc()
                                + "），按「拿不到」处理。");
                    }
                    complete(packet.uuid(), Optional.empty(), from);
                    break;
                }
                complete(packet.uuid(), Optional.of(packet.loc()), from);
                break;
            case Wire.OP_POS_NONE:
                complete(packet.uuid(), Optional.empty(), from);
                break;
            case Wire.OP_MOVED:
                // 子服说这个人动了 —— 交给 Teleporter 判断他是不是正在倒计时
                plugin.teleporter().onMoved(packet.uuid(), packet.loc());
                break;
            default:
                break;
        }
    }

    /** 这条坐标能不能信：世界名非空 + 三个坐标都是有限数（NaN / 无穷大一律不收）。 */
    private static boolean plausible(final Wire.Loc loc) {
        return loc != null
                && loc.world() != null && !loc.world().isBlank()
                && Double.isFinite(loc.x()) && Double.isFinite(loc.y()) && Double.isFinite(loc.z());
    }

    private static String serverNameOf(final Object source) {
        if (source instanceof ServerConnection) {
            return ((ServerConnection) source).getServerInfo().getName();
        }
        return null;
    }

    private void complete(final UUID uuid, final Optional<Wire.Loc> result, final String from) {
        final Pending p = take(uuid, from);
        if (p == null) {
            return;
        }
        if (p.timeout != null) {
            p.timeout.cancel();
        }
        p.callback.accept(result);
    }

    /**
     * 取某个人队列里最前面那条查询（FIFO）。
     *
     * <p>两个硬条件：① 队列里确实还挂着；② 回包的服就是<b>当初发查询时那个人所在的服</b> ——
     * 对不上说明这是别的服（或有人伪造）回的，丢掉并记一条 debug 日志。
     * 真正那条会一直等到超时（回调收到 empty），不会被错服的坐标顶掉。
     */
    private Pending take(final UUID uuid, final String from) {
        final Pending[] taken = new Pending[1];
        final Pending[] rejected = new Pending[1];
        pending.compute(uuid, (key, queue) -> {
            if (queue == null || queue.isEmpty()) {
                return null;
            }
            final Pending head = queue.peek();
            if (from != null && head.server != null && !head.server.equalsIgnoreCase(from)) {
                rejected[0] = head;
                return queue;
            }
            taken[0] = queue.poll();
            return queue.isEmpty() ? null : queue;
        });
        if (taken[0] == null && rejected[0] != null && plugin.configuration().debug()) {
            logger.info("[vtpa] 忽略一条来源不匹配的坐标回包：发起时玩家在 " + rejected[0].server
                    + "，回包来自 " + from + "。");
        }
        return taken[0];
    }

    /** 从队列里摘掉「我这一条」。摘掉了才返回 true（可能已经被回包取走了）。 */
    private boolean drop(final UUID uuid, final Pending p) {
        final boolean[] removed = new boolean[1];
        pending.compute(uuid, (key, queue) -> {
            if (queue == null) {
                return null;
            }
            if (queue.remove(p)) {
                removed[0] = true;
            }
            return queue.isEmpty() ? null : queue;
        });
        return removed[0];
    }

    // ------------------------------------------------------------------
    // 发包
    // ------------------------------------------------------------------

    /** 这个服确认装了桥接吗？（没确认过 = false） */
    public boolean isReady(final String serverName) {
        return serverName != null && ready.contains(serverName.toLowerCase(java.util.Locale.ROOT));
    }

    public Set<String> readyServers() {
        return ready;
    }

    /** 给某个服发心跳（有没有人无所谓，没人连就发不出去，返回 false）。 */
    public boolean ping(final RegisteredServer server) {
        return server.sendPluginMessage(channel, Wire.ping());
    }

    /**
     * 问子服要某个玩家的坐标。
     *
     * <p>拿不到（人不在服上 / 子服没装桥接 / 超时）时回调收到 {@code Optional.empty()}，
     * 不会抛异常也不会一直挂着 —— 调用方只管按「没拿到」处理。
     */
    public void queryLocation(final Player player, final Consumer<Optional<Wire.Loc>> callback) {
        final RegisteredServer server = currentServer(player);
        if (server == null) {
            callback.accept(Optional.empty());
            return;
        }
        if (!server.sendPluginMessage(channel, Wire.positionRequest(player.getUniqueId()))) {
            // 这个服一个人都没有（或连接断了）—— 直接当没拿到
            callback.accept(Optional.empty());
            return;
        }
        final Pending p = new Pending(callback, server.getServerInfo().getName()
                .toLowerCase(Locale.ROOT));
        // ⚠️ 排到这个人队列的队尾，别顶掉前面那条（前面那条的 callback 也等着回话）
        pending.compute(player.getUniqueId(), (key, queue) -> {
            final Deque<Pending> target = queue == null ? new ArrayDeque<>() : queue;
            target.add(p);
            return target;
        });
        p.timeout = plugin.proxy().getScheduler()
                .buildTask(plugin, () -> {
                    // 只有还挂着的是「我这一条」时才回调 —— 期间可能已经被回包取走了
                    if (drop(player.getUniqueId(), p)) {
                        callback.accept(Optional.empty());
                    }
                })
                .delay(plugin.configuration().bridgeTimeoutMillis(), TimeUnit.MILLISECONDS)
                .schedule();
    }

    // ------------------------------------------------------------------
    // ready 的过期
    // ------------------------------------------------------------------

    /**
     * 把太久没回心跳的子服移出 {@link #ready}。
     *
     * <p>子服重启 / 桥接被卸载，代理是<b>收不到任何通知</b>的 —— 不主动过期的话这个服会
     * 永远留在 ready 里，于是坐标照问、落点照发，全都石沉大海（玩家看到的就是「没反应」）。
     * 移出之后相关请求改走 {@code bridge.missing} 的兜底，比一直假装它有桥接安全。
     *
     * <p>阈值取 3 个心跳周期：正常服每一轮心跳都会刷新 {@link #lastPong}，
     * 偶尔丢一两个包也不会被误判。
     */
    public void expireStale() {
        final long maxAge = Math.max(5L, plugin.configuration().pingIntervalSeconds()) * 3L * 1000L;
        final long now = System.currentTimeMillis();
        final List<String> gone = new ArrayList<>();
        for (final Map.Entry<String, Long> entry : lastPong.entrySet()) {
            if (now - entry.getValue() <= maxAge) {
                continue;
            }
            final String name = entry.getKey();
            lastPong.remove(name);
            if (ready.remove(name)) {
                versions.remove(name);
                gone.add(name);
            }
        }
        if (!gone.isEmpty()) {
            logger.info("[vtpa] 子服 " + String.join("、", gone) + " 已经超过 " + (maxAge / 1000L)
                    + " 秒没回心跳 —— 视为桥接已离线，相关请求改走 bridge.missing 兜底。");
        }
    }

    // ------------------------------------------------------------------
    // 粒子特效
    // ------------------------------------------------------------------

    /**
     * 让子服跟着这个玩家撒一段特效（倒计时期间一直在他身上转的那种）。
     *
     * <p>特效是「锦上添花」：任何一步走不通（没开、没装桥接、消息发不出去）
     * 都只是没特效，<b>绝不影响传送本身</b>，也不会给玩家报错。
     *
     * @param spec  解析好的预设；null 表示不播，直接返回 false
     * @param ticks 播多少 tick（{@code ≤ 0} 时用预设自带的 {@code dur}）
     */
    public boolean effectFollow(final Player player, final FxSpec spec, final int ticks) {
        if (!canPlayEffect(player, spec)) {
            return false;
        }
        final RegisteredServer server = currentServer(player);
        if (server == null) {
            return false;
        }
        return server.sendPluginMessage(channel,
                Wire.effectFollow(player.getUniqueId(), spec.raw(), ticks));
    }

    /**
     * 让子服在某个坐标撒一段特效（出发地 / 落点那两下）。
     *
     * <p>⚠️ 坐标落在<b>发消息时玩家所在的那个服</b>上。所以「出发地」要在切服之前发，
     * 「落点」要等玩家真的进了新服再发。
     */
    public boolean effectStatic(final Player player, final Wire.Loc loc, final FxSpec spec, final int ticks) {
        if (loc == null || !canPlayEffect(player, spec)) {
            return false;
        }
        final RegisteredServer server = currentServer(player);
        if (server == null) {
            return false;
        }
        return server.sendPluginMessage(channel, Wire.effectStatic(loc, spec.raw(), ticks));
    }

    /** 把这个人身上正在播的特效全停掉（倒计时结束 / 被打断时都要调一次）。 */
    public boolean effectStop(final Player player) {
        if (!plugin.configuration().particlesEnabled()) {
            return false;
        }
        final RegisteredServer server = currentServer(player);
        if (server == null) {
            return false;
        }
        return server.sendPluginMessage(channel, Wire.effectStop(player.getUniqueId()));
    }

    /** 总开关 + 桥接检查。特效永远不该拦住传送，所以这里只管 true / false，不抛也不提示。 */
    private boolean canPlayEffect(final Player player, final FxSpec spec) {
        final Configuration config = plugin.configuration();
        if (!config.particlesEnabled() || spec == null || player == null) {
            return false;
        }
        if (config.particlesRequireBridge() && !isReady(serverName(player))) {
            return false;
        }
        return true;
    }

    /** 让子服把这个人传到指定坐标。返回 false = 消息没发出去。 */
    public boolean teleport(final Player player, final Wire.Loc loc) {
        final RegisteredServer server = currentServer(player);
        if (server == null) {
            return false;
        }
        return server.sendPluginMessage(channel, Wire.teleport(player.getUniqueId(), loc));
    }

    // ------------------------------------------------------------------
    // 移动监视 / 声音（1.1.0 起）
    // ------------------------------------------------------------------

    /**
     * 让子服盯住这个人的移动。子服会在他动超过 {@code tolerance} 时回一个 {@code MOVED}。
     *
     * <p>子服桥接太老（不支持）就返回 false —— 调用方要退回代理端轮询，别干脆不检测。
     */
    public boolean watch(final Player player, final double tolerance, final boolean ignoreY) {
        final RegisteredServer server = currentServer(player);
        if (server == null) {
            return false;
        }
        final String name = server.getServerInfo().getName();
        if (!supports(name, 1, 1, 0)) {
            // 排查用：之前移动取消「静默失效」多半栽在这里（版本没协商上就退回轮询了）
            logger.info("[vtpa] 子服 " + name + " 的桥接版本是「" + versions.get(name.toLowerCase(java.util.Locale.ROOT))
                    + "」，不支持移动监视 → 退回代理轮询。");
            return false;
        }
        return server.sendPluginMessage(channel, Wire.watch(player.getUniqueId(), tolerance,
                ignoreY ? Wire.WATCH_IGNORE_Y : 0));
    }

    /** 别盯了（倒计时结束 / 被打断 / 换服）。 */
    public void unwatch(final Player player) {
        final RegisteredServer server = currentServer(player);
        if (server != null) {
            server.sendPluginMessage(channel, Wire.unwatch(player.getUniqueId()));
        }
    }

    /**
     * 给这个玩家播个声音。{@code spec} 为空 / 没配就不发。
     *
     * <p>返回 false 只是「没播」（没开声音、这条没配、消息发不出去），
     * 调用方一律不用管，绝不影响传送流程。
     */
    public boolean sound(final Player player, final String spec) {
        if (player == null || spec == null || spec.isBlank()) {
            return false;
        }
        final RegisteredServer server = currentServer(player);
        if (server == null) {
            return false;
        }
        return server.sendPluginMessage(channel, Wire.sound(player.getUniqueId(), spec));
    }

    /** 这个服的桥接版本够不够新（用于「子服能不能干某件事」的判断）。 */
    public boolean supports(final String serverName, final int major, final int minor, final int patch) {
        if (serverName == null) {
            return false;
        }
        final String version = versions.get(serverName.toLowerCase(java.util.Locale.ROOT));
        return atLeast(version, major, minor, patch);
    }

    /** 比较 {@code 1.10.2} 这种版本串；解析不出来一律当「不够新」（保守）。 */
    public static boolean atLeast(final String version, final int major, final int minor, final int patch) {
        if (version == null || version.isBlank()) {
            return false;
        }
        final String[] parts = version.trim().split("[.\\-+]");
        final int[] got = new int[3];
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                got[i] = Integer.parseInt(parts[i].replaceAll("[^0-9]", ""));
            } catch (final Exception e) {
                got[i] = 0;
            }
        }
        final int[] want = {major, minor, patch};
        for (int i = 0; i < 3; i++) {
            if (got[i] != want[i]) {
                return got[i] > want[i];
            }
        }
        return true;
    }

    /** 这个人当前所在的服务器；还在登录中（没进任何服）返回 null。 */
    public static RegisteredServer currentServer(final Player player) {
        if (player == null) {
            return null;
        }
        final Optional<ServerConnection> connection = player.getCurrentServer();
        if (connection.isEmpty()) {
            return null;
        }
        return connection.get().getServer();
    }

    public static String serverName(final Player player) {
        final RegisteredServer server = currentServer(player);
        return server == null ? null : server.getServerInfo().getName();
    }
}
