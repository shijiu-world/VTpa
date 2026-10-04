package cn.shijiu.vtpa;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder.Status;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.Title.Times;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 「对方同意了」之后的那一套：倒计时 → 中途检查有没有动 → 真正把人送过去。
 *
 * <p>传送这一步是<a href="#cross">分两种</a>的：
 * <ul>
 *   <li><b>同服</b>：直接让子服把人 teleport 到坐标（子服得装 VTpaBridge）。</li>
 *   <li><b>跨服</b>：先把人切到对方那个服，等 {@code ServerPostConnectEvent} 回来，
 *       再把坐标发给新服，让桥接把他挪到落点。落点坐标是切服<b>之前</b>问好的，
 *       所以哪怕对方在切服那一瞬间跑了几步，落点也不会飞。</li>
 * </ul>
 *
 * <p>⚠️ 移动检测同样要靠桥接给坐标。子服没装桥接时 {@code noBridge} 置位，
 * 这一项自动失效（等于不检测）—— 宁可不拦，也不能把站着不动的人误判成动了。
 */
public final class Teleporter {

    private final VTpa plugin;

    /** 正在倒计时的玩家 → 倒计时状态。 */
    private final Map<UUID, Countdown> active = new ConcurrentHashMap<>();
    /** 切服途中、等落地传送的玩家 → 落点。 */
    private final Map<UUID, PendingTp> pendingTeleports = new ConcurrentHashMap<>();

    private static final class PendingTp {
        final String server;
        final Wire.Loc loc;

        PendingTp(final String server, final Wire.Loc loc) {
            this.server = server;
            this.loc = loc;
        }
    }

    private static final class Countdown {
        final TpaRequest request;
        final UUID moverId;
        final UUID destId;
        final String moverName;
        final String destName;
        final String startServer;
        final long delayMillis;
        final boolean checkMovement;

        long elapsed;
        int lastShown = -1;
        volatile Wire.Loc origin;
        /** 最近一次问到的坐标 —— 出发地特效要用（省得再问一次）。 */
        volatile Wire.Loc lastLoc;
        /**
         * <b>锁定的落点</b>：对方按下「接受」那一刻他站在哪，就传去哪。
         *
         * <p>倒计时这几秒里对方还能继续走 —— 不锁的话落点会跟着他漂，
         * 传过去发现身边没人（甚至掉进他刚挖的坑里）。
         */
        volatile Wire.Loc destLoc;
        /** 锁定落点时对方所在的服。他要是中途换服了，这个坐标就作废（属于旧服）。 */
        volatile String destLocServer;
        volatile boolean noBridge;
        volatile boolean checking;
        volatile boolean done;
        /** 移动检测是不是交给子服了（子服盯着的时候代理不用再轮询）。 */
        boolean watching;
        /**
         * 子服已经在盯了，代理还要不要<b>同时</b>自己轮询一遍。
         *
         * <p>默认开：子服那条路万一没走通（桥接版本不认识、消息丢了、子服没装），
         * 移动取消就会<b>静默失效</b> —— 多问几次坐标很便宜，失效很难查，所以双保险。
         */
        boolean pollToo;
        ScheduledTask task;

        Countdown(final TpaRequest request, final UUID moverId, final String moverName,
                  final UUID destId, final String destName, final String startServer,
                  final long delayMillis, final boolean checkMovement) {
            this.request = request;
            this.moverId = moverId;
            this.moverName = moverName;
            this.destId = destId;
            this.destName = destName;
            this.startServer = startServer;
            this.delayMillis = delayMillis;
            this.checkMovement = checkMovement;
        }
    }

    public Teleporter(final VTpa plugin) {
        this.plugin = plugin;
    }

    public boolean isBusy(final UUID uuid) {
        return active.containsKey(uuid);
    }

    /**
     * 撤回请求时顺手把「已经在倒计时」的那一段也掐掉。
     *
     * <p>请求一旦被接受就从账本里摘掉了，所以 {@code /tpacancel} 光翻账本是找不到的 ——
     * 不处理的话玩家撤回成功了，三秒后照样被传走（实测过：取消完照样传送）。
     *
     * @param uuid 敲命令的人（可能是被传送的那个，也可能是发起请求的那个）
     * @return 真的掐掉了一段倒计时吗
     */
    public boolean abortCountdown(final UUID uuid) {
        Countdown countdown = active.get(uuid);
        if (countdown == null) {
            for (final Countdown other : active.values()) {
                if (other.destId.equals(uuid) || other.request.requesterId().equals(uuid)) {
                    countdown = other;
                    break;
                }
            }
        }
        if (countdown == null) {
            return false;
        }
        synchronized (countdown) {
            if (countdown.done) {
                return false;
            }
            finishEarly(countdown, "cancelled-self", "cancelled-other");
        }
        return true;
    }

    /** 玩家掉线：把他正在进行的倒计时取消掉，落点也一并丢掉。 */
    public void abortFor(final UUID uuid) {
        final Countdown countdown = active.remove(uuid);
        if (countdown != null) {
            countdown.done = true;
            if (countdown.task != null) {
                countdown.task.cancel();
            }
        }
        pendingTeleports.remove(uuid);
    }

    // ------------------------------------------------------------------
    // 起手
    // ------------------------------------------------------------------

    /** 开始倒计时（{@code delay = 0} 时会立刻传）。 */
    public void start(final TpaRequest request) {
        final Configuration config = plugin.configuration();
        final boolean requesterMoves = request.type().movesRequester();
        final UUID moverId = requesterMoves ? request.requesterId() : request.targetId();
        final UUID destId = requesterMoves ? request.targetId() : request.requesterId();
        final Optional<Player> mover = plugin.proxy().getPlayer(moverId);
        final Optional<Player> dest = plugin.proxy().getPlayer(destId);
        if (mover.isEmpty() || dest.isEmpty()) {
            notify(moverId, "offline-abort");
            notify(destId, "offline-abort");
            return;
        }
        final String startServer = Backend.serverName(mover.get());
        if (startServer == null) {
            // 还在登录中，没进任何服 —— 没法传
            notify(moverId, "teleport-failed", "reason", "你还不在任何服务器上");
            return;
        }
        if (isBusy(moverId)) {
            notify(moverId, "self-busy");
            return;
        }
        // ⚠️ 不看任何权限 —— 谁动都取消，OP / 有通配符的管理员也不例外。
        //    想整服关掉就只能改 movement.enabled = false（那是对所有人都关）。
        final boolean wantCheck = config.movementEnabled();
        final long delayMillis = Math.max(0L, config.teleportDelaySeconds()) * 1000L;

        // 移动检测优先交给子服（实时、准）；子服桥接太老才退回代理端轮询坐标
        // ⚠️ 这里不再用 isReady() 当门闸 —— 没握过手的服也可能有桥接，
        //    一票否决会让「移动取消」在某些服上静默失效
        boolean watching = false;
        if (wantCheck && config.movementBackend() && delayMillis > 0L) {
            watching = plugin.backend().watch(mover.get(), config.movementTolerance(),
                    config.movementIgnoreY());
        }
        final boolean checkMovement = wantCheck && !watching;

        final Countdown countdown = new Countdown(request, moverId, mover.get().getUsername(),
                destId, dest.get().getUsername(), startServer.toLowerCase(Locale.ROOT),
                delayMillis, checkMovement);
        countdown.watching = watching;
        countdown.pollToo = wantCheck && config.movementPollAlso();
        active.put(moverId, countdown);
        // 排查用：这一行能直接看出移动检测到底走的是哪条路（子服盯 / 代理轮询 / 压根没开）
        // ⚠️ 只在 advanced.debug = true 时打 —— 每次传送都来一行的太吵了
        if (config.debug()) {
            plugin.logger().info("[vtpa] 移动检测："
                    + (!config.movementEnabled() ? "关（movement.enabled = false）"
                    : (watching ? "子服实时盯" : "子服没接手 → 代理轮询")
                            + (countdown.pollToo ? "（同时代理也轮询，双保险）" : "")));
        }

        // 先把「基准坐标」抢到手 —— 这样轮询第一跳就能比，不用白等一轮
        if (countdown.pollToo) {
            pollPosition(countdown, mover.get());
        }

        // 落点：就锁在「对方按下接受」这一刻 —— 之后他再怎么走都不影响落点。
        // （桥接没回 / 延迟为 0 还没等到回包时，proceed 会退回现问一次。）
        if (config.lockDestination()) {
            lockDestination(countdown, dest.get());
        }

        // 倒计时的粒子环：跟着被传送的那个人转，时长按倒计时秒数算（多给半秒缓冲）
        if (delayMillis > 0L) {
            plugin.backend().effectFollow(mover.get(), config.particleCountdown(),
                    (int) (delayMillis / 50L) + 10);
        }
        // 倒计时开始那一下的音效（CMI 的 CommandWarmup）
        plugin.backend().sound(mover.get(), config.sound("countdown"));

        final long interval = Math.max(50L, config.pollIntervalMillis());
        countdown.task = plugin.proxy().getScheduler()
                .buildTask(plugin, () -> tick(countdown))
                .repeat(interval, TimeUnit.MILLISECONDS)
                .schedule();
        // 延迟为 0 时也要走一整轮（要问坐标），所以不在这里直接 finish
    }

    /**
     * 把落点锁死在这一刻：问一次对方在哪，记下来。
     *
     * <p>异步的（要往子服跑一趟），但倒计时有 3 秒，够用。回来时人已经传走了就丢掉，
     * 对方中途换了服也让这个坐标作废 —— 那时 {@code proceed} 会重新问一次。
     */
    private void lockDestination(final Countdown countdown, final Player dest) {
        final String server = Backend.serverName(dest);
        plugin.backend().queryLocation(dest, result -> {
            if (countdown.done) {
                return;
            }
            if (result.isEmpty()) {
                return;
            }
            countdown.destLoc = result.get();
            countdown.destLocServer = server;
            if (plugin.configuration().debug()) {
                plugin.logger().info("[vtpa] 落点已锁定：" + countdown.destName + " @"
                        + (server == null ? "?" : server) + " " + result.get().world()
                        + " " + String.format(Locale.ROOT, "%.2f %.2f %.2f",
                                result.get().x(), result.get().y(), result.get().z()));
            }
        });
    }

    // ------------------------------------------------------------------
    // 每一跳
    // ------------------------------------------------------------------

    private void tick(final Countdown countdown) {
        final Configuration config = plugin.configuration();
        synchronized (countdown) {
            if (countdown.done) {
                return;
            }
            final Optional<Player> moverOpt = plugin.proxy().getPlayer(countdown.moverId);
            if (moverOpt.isEmpty()) {
                finishEarly(countdown, "offline-abort", "offline-abort");
                return;
            }
            final Player mover = moverOpt.get();
            final String nowServer = Backend.serverName(mover);
            if (nowServer == null || !nowServer.equalsIgnoreCase(countdown.startServer)) {
                // 倒计时中途换服（自己敲 /server、被别的插件送走、掉线重连到别的服…）
                finishEarly(countdown, "countdown-switch", null);
                return;
            }
            if (!config.filter().allows(nowServer)
                    && !(config.serverBypassEnabled()
                            && Permissions.has(mover, Permissions.SERVER_BYPASS, false))) {
                finishEarly(countdown, "server-denied-self", null, "server", nowServer);
                return;
            }

            countdown.elapsed += Math.max(1L, config.pollIntervalMillis());

            if (config.countdownEnabled()) {
                final long secondsLeft = (countdown.delayMillis - countdown.elapsed + 999L) / 1000L;
                if (secondsLeft > 0 && secondsLeft != countdown.lastShown) {
                    countdown.lastShown = (int) secondsLeft;
                    display(mover, countdown, secondsLeft);
                    // 每过一秒滴一下（CMI 的 CommandWarmupRunning）
                    plugin.backend().sound(mover, config.sound("countdown-tick"));
                }
            }

            if (countdown.pollToo && !countdown.checking) {
                pollPosition(countdown, mover);
            }

            if (countdown.elapsed >= countdown.delayMillis) {
                finish(countdown);
            }
        }
    }

    /** 问一次坐标：第一次拿到的当基准，之后每次对比。 */
    private void pollPosition(final Countdown countdown, final Player mover) {
        countdown.checking = true;
        plugin.backend().queryLocation(mover, result -> {
            countdown.checking = false;
            if (result.isEmpty()) {
                // 桥接不在（或超时）：这一轮起不再检测，直接放行
                countdown.noBridge = true;
                return;
            }
            countdown.lastLoc = result.get();
            if (countdown.origin == null) {
                countdown.origin = result.get();
                return;
            }
            if (countdown.done) {
                return;
            }
            if (moved(countdown.origin, result.get())) {
                synchronized (countdown) {
                    if (countdown.done) {
                        return;
                    }
                    finishEarly(countdown, "countdown-moved", "countdown-moved-other");
                }
            }
        });
    }

    private boolean moved(final Wire.Loc from, final Wire.Loc to) {
        final Configuration config = plugin.configuration();
        if (config.cancelOnWorldChange() && !from.world().equals(to.world())) {
            return true;
        }
        final double moved = config.movementIgnoreY() ? from.flatDistance(to) : from.distance(to);
        return moved > config.movementTolerance();
    }

    // ------------------------------------------------------------------
    // 收尾
    // ------------------------------------------------------------------

    /**
     * 子服报告「这个人动了」。
     *
     * <p>⚠️ 这是从插件消息线程进来的（Netty），所以要跟 tick 一样拿 countdown 的锁。
     */
    public void onMoved(final UUID uuid, final Wire.Loc loc) {
        final Countdown countdown = active.get(uuid);
        if (countdown == null) {
            return;
        }
        synchronized (countdown) {
            if (countdown.done) {
                return;
            }
            if (plugin.configuration().debug()) {
                plugin.logger().info("[vtpa] 子服报告 " + countdown.moverName + " 移动到了 " + loc
                        + "，倒计时作废。");
            }
            finishEarly(countdown, "countdown-moved", "countdown-moved-other");
        }
    }

    /** 倒计时没走完就被打断。otherKey 为 null 时不通知另一方。 */
    private void finishEarly(final Countdown countdown, final String selfKey, final String otherKey,
                             final Object... args) {
        countdown.done = true;
        if (countdown.task != null) {
            countdown.task.cancel();
        }
        active.remove(countdown.moverId);
        clearDisplay(countdown.moverId);
        // 倒计时环收掉；配了 cancel 特效的话再补一下（被打断的提示感）
        plugin.proxy().getPlayer(countdown.moverId).ifPresent(mover -> {
            plugin.backend().unwatch(mover);
            plugin.backend().effectStop(mover);
            plugin.backend().sound(mover, plugin.configuration().sound("cancel"));
            plugin.backend().effectFollow(mover, plugin.configuration().particleCancel(),
                    plugin.configuration().cancelTicks());
        });
        notify(countdown.moverId, selfKey, args);
        if (otherKey != null) {
            final List<Object> otherArgs = new ArrayList<>();
            otherArgs.add("player");
            otherArgs.add(countdown.moverName);
            notify(countdown.destId, otherKey, otherArgs.toArray());
        }
        if (plugin.configuration().logToConsole()) {
            plugin.logger().info("[vtpa] 传送取消（" + selfKey + "）：" + countdown.moverName
                    + " → " + countdown.destName);
        }
    }

    /** 倒计时走完，问落点坐标并开始传。 */
    private void finish(final Countdown countdown) {
        countdown.done = true;
        if (countdown.task != null) {
            countdown.task.cancel();
        }
        active.remove(countdown.moverId);
        clearDisplay(countdown.moverId);
        plugin.proxy().getPlayer(countdown.moverId).ifPresent(plugin.backend()::unwatch);

        final Optional<Player> moverOpt = plugin.proxy().getPlayer(countdown.moverId);
        final Optional<Player> destOpt = plugin.proxy().getPlayer(countdown.destId);
        if (moverOpt.isEmpty() || destOpt.isEmpty()) {
            notify(countdown.moverId, "offline-abort");
            notify(countdown.destId, "offline-abort");
            return;
        }
        final Player mover = moverOpt.get();
        final Player dest = destOpt.get();
        final Configuration config = plugin.configuration();
        final String destServer = Backend.serverName(dest);
        if (destServer == null) {
            notify(countdown.moverId, "teleport-failed", "reason", "对方不在任何服务器上");
            return;
        }
        if (!config.filter().allows(destServer)
                && !(config.serverBypassEnabled()
                        && Permissions.has(mover, Permissions.SERVER_BYPASS, false))) {
            notify(countdown.moverId, "server-denied-target", "target", countdown.destName);
            return;
        }
        // 出发地特效要 mover 当前的坐标：能顺手拿到就顺手拿，拿不到（没开移动检测）就现问一次
        if (config.particleDepart() != null && countdown.lastLoc == null
                && plugin.backend().isReady(Backend.serverName(mover))) {
            plugin.backend().queryLocation(mover, loc -> {
                countdown.lastLoc = loc.orElse(null);
                proceed(countdown, mover, dest, destServer);
            });
            return;
        }
        proceed(countdown, mover, dest, destServer);
    }

    /** 坐标就绪之后的最后一跳：撒出发地特效 → 传 → 撒落地特效。 */
    private void proceed(final Countdown countdown, final Player mover,
                         final Player dest, final String destServer) {
        final Configuration config = plugin.configuration();
        // 倒计时那圈先收掉，再在脚下撒一把「出发」的
        plugin.backend().unwatch(mover);
        plugin.backend().effectStop(mover);
        plugin.backend().sound(mover, config.sound("depart"));
        if (countdown.lastLoc != null) {
            plugin.backend().effectStatic(mover, countdown.lastLoc,
                    config.particleDepart(), config.departTicks());
        }

        final String moverServer = Backend.serverName(mover);
        final boolean sameServer = destServer.equalsIgnoreCase(moverServer);

        // 落点锁过了（对方按下「接受」那一刻站的地方）就直接用，不用再问一次
        final Wire.Loc locked = lockedDestination(countdown, destServer);
        if (locked != null) {
            deliver(countdown, mover, destServer, sameServer, locked);
            return;
        }
        plugin.backend().queryLocation(dest, result -> {
            if (result.isEmpty()) {
                // 拿不到坐标
                if (!sameServer && "switch".equals(config.bridgeMissing())) {
                    switchServer(mover, destServer, null, countdown);
                } else {
                    plugin.backend().sound(mover, config.sound("fail"));
                    notify(countdown.moverId, "bridge-missing", "server", destServer);
                }
                return;
            }
            deliver(countdown, mover, destServer, sameServer, result.get());
        });
    }

    /**
     * 锁好的落点还能不能用。
     *
     * <p>唯一的作废条件是<b>对方中途换了服</b> —— 那坐标属于旧世界，用它会把人传到
     * 另一个服的同名坐标上。还站在原来的服就没问题：他走动没关系，落点就是要锁死的。
     */
    private Wire.Loc lockedDestination(final Countdown countdown, final String destServer) {
        final Wire.Loc loc = countdown.destLoc;
        if (loc == null || countdown.destLocServer == null
                || !countdown.destLocServer.equalsIgnoreCase(destServer)) {
            return null;
        }
        return loc;
    }

    /** 坐标到手之后真正把人送过去（同服直接传，跨服先切服再落点）。 */
    private void deliver(final Countdown countdown, final Player mover, final String destServer,
                         final boolean sameServer, final Wire.Loc loc) {
        final Configuration config = plugin.configuration();
        if (sameServer) {
            if (plugin.backend().teleport(mover, loc)) {
                // 同服：人已经落在坐标上了，直接在落点撒一把
                plugin.backend().effectStatic(mover, loc,
                        config.particleArrive(), config.arriveTicks());
                plugin.backend().sound(mover, config.sound("arrive"));
                notify(countdown.moverId, "teleport-done");
                log(countdown, loc);
            } else {
                plugin.backend().sound(mover, config.sound("fail"));
                notify(countdown.moverId, "teleport-failed", "reason", "消息没发出去");
            }
            return;
        }
        switchServer(mover, destServer, loc, countdown);
    }

    /** 跨服：先切服，落点记下来，等 {@code ServerPostConnectEvent} 到了再让子服挪人。 */
    private void switchServer(final Player mover, final String destServer,
                              final Wire.Loc loc, final Countdown countdown) {
        final Optional<RegisteredServer> server = plugin.proxy().getServer(destServer);
        if (server.isEmpty()) {
            notify(countdown.moverId, "teleport-failed", "reason", "找不到服务器 " + destServer);
            return;
        }
        notify(countdown.moverId, "switching", "server", destServer);
        if (loc != null) {
            pendingTeleports.put(mover.getUniqueId(), new PendingTp(destServer.toLowerCase(Locale.ROOT), loc));
        }
        mover.createConnectionRequest(server.get()).connect().thenAccept(result -> {
            if (result.isSuccessful() || result.getStatus() == Status.ALREADY_CONNECTED) {
                if (result.getStatus() == Status.ALREADY_CONNECTED && loc != null) {
                    // 已经在那儿了（罕见：同一帧内换过服），直接落点
                    pendingTeleports.remove(mover.getUniqueId());
                    plugin.backend().teleport(mover, loc);
                    plugin.backend().effectStatic(mover, loc,
                            plugin.configuration().particleArrive(),
                            plugin.configuration().arriveTicks());
                    plugin.backend().sound(mover, plugin.configuration().sound("arrive"));
                    notify(countdown.moverId, "teleport-done");
                    log(countdown, loc);
                }
                return;
            }
            pendingTeleports.remove(mover.getUniqueId());
            plugin.backend().sound(mover, plugin.configuration().sound("fail"));
            notify(countdown.moverId, "teleport-failed", "reason", String.valueOf(result.getStatus()));
        });
    }

    /** 玩家进到新服了：如果之前给他记了落点，就在这里让子服把他挪过去。 */
    public void onServerConnect(final Player player, final String serverName) {
        final PendingTp pending = pendingTeleports.remove(player.getUniqueId());
        if (pending == null) {
            return;
        }
        if (!pending.server.equalsIgnoreCase(serverName)) {
            // 最后落到了别的服（被别的插件拦了、或者目标服满了）—— 落点作废，别乱传
            plugin.logger().warn("[vtpa] " + player.getUsername() + " 最终进了 " + serverName
                    + "（预期 " + pending.server + "），落点作废。");
            return;
        }
        // 稍等一拍再发：进服那一瞬间后端还在处理 join，桥接会自己排队，这里给点余量
        plugin.proxy().getScheduler()
                .buildTask(plugin, () -> {
                    plugin.backend().teleport(player, pending.loc);
                    // 跨服落地：在新服的落点撒一把
                    plugin.backend().effectStatic(player, pending.loc,
                            plugin.configuration().particleArrive(),
                            plugin.configuration().arriveTicks());
                    plugin.backend().sound(player, plugin.configuration().sound("arrive"));
                    plugin.send(player, plugin.configuration().message("teleport-done"));
                    if (plugin.configuration().logToConsole()) {
                        plugin.logger().info("[vtpa] " + player.getUsername() + " 跨服传送到 "
                                + pending.server + " " + pending.loc);
                    }
                })
                .delay(300L, TimeUnit.MILLISECONDS)
                .schedule();
    }

    private void log(final Countdown countdown, final Wire.Loc loc) {
        if (plugin.configuration().logToConsole()) {
            plugin.logger().info("[vtpa] " + countdown.moverName + " 传送到 " + countdown.destName
                    + " 的位置：" + loc);
        }
    }

    // ------------------------------------------------------------------
    // 显示
    // ------------------------------------------------------------------

    private void display(final Player player, final Countdown countdown, final long secondsLeft) {
        final Configuration config = plugin.configuration();
        final String mode = config.countdownMode();
        if ("none".equals(mode)) {
            return;
        }
        final String seconds = String.valueOf(secondsLeft);
        final String other = countdown.destName;
        if ("title".equals(mode) || "both".equals(mode)) {
            final Times times = Times.times(
                    Duration.ofMillis(config.fadeInTicks() * 50L),
                    Duration.ofMillis(config.stayTicks() * 50L),
                    Duration.ofMillis(config.fadeOutTicks() * 50L));
            player.showTitle(Title.title(
                    Colors.colorize(replace(config.countdownTitle(), seconds, other)),
                    Colors.colorize(replace(config.countdownSubtitle(), seconds, other)),
                    times));
        }
        if ("actionbar".equals(mode) || "both".equals(mode)) {
            player.sendActionBar(Colors.colorize(replace(config.countdownActionbar(), seconds, other)));
        }
    }

    private static String replace(final String text, final String seconds, final String player) {
        return text.replace("#seconds#", seconds).replace("#player#", player);
    }

    private void clearDisplay(final UUID uuid) {
        final Optional<Player> player = plugin.proxy().getPlayer(uuid);
        if (player.isEmpty()) {
            return;
        }
        final String mode = plugin.configuration().countdownMode();
        if ("title".equals(mode) || "both".equals(mode)) {
            player.get().clearTitle();
        }
        if ("actionbar".equals(mode) || "both".equals(mode)) {
            player.get().sendActionBar(Component.empty());
        }
    }

    private void notify(final UUID uuid, final String key, final Object... args) {
        final Optional<Player> player = plugin.proxy().getPlayer(uuid);
        if (player.isEmpty()) {
            return;
        }
        plugin.send(player.get(), plugin.configuration().message(key, args));
    }
}
