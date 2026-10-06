package cn.shijiu.vtpa;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 传送请求的业务逻辑：发请求、接受、拒绝、取消、过期、全员广播。
 *
 * <p>命令类只负责解析参数和权限，真正的判断全在这里 —— 好处是所有的边界情况
 * （自己发给自己、重复请求、服务器名单、冷却、对方掉线、对方正在传送…）
 * 都集中在一处，改起来不会漏。
 */
public final class TpaService {

    private static final String TOKEN_ACCEPT = "#accept#";
    private static final String TOKEN_DENY = "#deny#";
    /** 「已发送请求」里那个撤回按钮 —— 点了等于敲 /tpacancel 对方。 */
    private static final String TOKEN_CANCEL = "#cancel#";

    private final VTpa plugin;

    /** 上一次发起请求的时间（冷却用）。 */
    private final Map<UUID, Long> lastRequestAt = new ConcurrentHashMap<>();
    /** 关掉了「接收别人的请求」的玩家。 */
    private final Map<UUID, Boolean> toggledOff = new ConcurrentHashMap<>();
    /** 被拒绝之后的封锁：key = 「谁」不能再发给「谁」，value = 解封的时间戳（毫秒）。 */
    private final Map<DenyKey, Long> denyUntil = new ConcurrentHashMap<>();

    /**
     * 「谁被谁拒绝过」这条记录的键 —— <b>有方向</b>：
     * 阿甲被阿乙拒绝，封的是「阿甲 → 阿乙」这一条，反过来不受影响。
     */
    private record DenyKey(UUID requester, UUID target) {
    }

    public TpaService(final VTpa plugin) {
        this.plugin = plugin;
    }

    private ProxyServer proxy() {
        return plugin.proxy();
    }

    private Configuration config() {
        return plugin.configuration();
    }

    private RequestStore store() {
        return plugin.store();
    }

    // ------------------------------------------------------------------
    // /tpatoggle
    // ------------------------------------------------------------------

    public boolean isDisabled(final UUID uuid) {
        return toggledOff.containsKey(uuid);
    }

    public void setDisabled(final UUID uuid, final boolean disabled) {
        if (disabled) {
            toggledOff.put(uuid, Boolean.TRUE);
        } else {
            toggledOff.remove(uuid);
        }
    }

    public Map<UUID, Boolean> disabledPlayers() {
        return toggledOff;
    }

    // ------------------------------------------------------------------
    // 被拒绝之后的封锁（general.deny-cooldown-seconds）
    // ------------------------------------------------------------------

    /**
     * 记一次拒绝：从现在起 {@code deny-cooldown-seconds} 秒内，
     * 这个人不能再给<b>拒绝他的那个人</b>发请求。
     *
     * <p>⚠️ 只锁「这一条路」：阿甲被阿乙拒绝 → 只有阿甲发给阿乙被拦，
     * 阿甲找别人、阿乙找阿甲都不受影响（就是为了让被拒绝的人别去反复戳同一个人）。
     */
    private void markDenied(final UUID requester, final UUID target) {
        final long seconds = config().denyCooldownSeconds();
        if (seconds <= 0) {
            return;
        }
        denyUntil.put(new DenyKey(requester, target),
                System.currentTimeMillis() + seconds * 1000L);
    }

    /**
     * 这个人还在「被对方拒绝」的封锁期里吗。
     *
     * @return 还剩多少秒（向上取整 —— 只剩 0.4 秒也算「还剩 1 秒」，免得提示刚出来就过期）；
     *         没被锁 / 已经到期 → 0
     */
    private long denyRemaining(final UUID requester, final UUID target) {
        final DenyKey key = new DenyKey(requester, target);
        final Long until = denyUntil.get(key);
        if (until == null) {
            return 0L;
        }
        final long left = until - System.currentTimeMillis();
        if (left <= 0) {
            denyUntil.remove(key);
            return 0L;
        }
        return (left + 999L) / 1000L;
    }

    /** 清掉已经到期的封锁记录 —— 每秒那趟清扫顺手做一次，免得这张表只增不减。 */
    public void purgeDenyCooldowns() {
        final long now = System.currentTimeMillis();
        denyUntil.values().removeIf(until -> until <= now);
    }

    // ------------------------------------------------------------------
    // 发请求
    // ------------------------------------------------------------------

    /**
     * 检查「发起者能不能给目标发一条请求」，返回 null 表示可以发，
     * 否则返回要提示给发起者的那条消息的 key（以及要替换的占位符）。
     *
     * <p>单独拆出来是为了 {@code /tpaall} 复用 —— 它要静默跳过不合适的人，
     * 不能每人弹一条提示。
     *
     * @param mutual 走「互相请求直接同意」那条路时为 true。语义上它等价于
     *               「B 点了 A 那条请求的 [接受]」，被消费掉的是对面先发的那一条，
     *               <b>并没有新请求产生</b>，所以「发新请求」那几道闸要跳过：
     *               冷却、对方关没关接收、我挂着的请求超没超上限、两人之间是不是已经有请求。
     *               「传送能不能发生」那几道闸一个不少：子服名单、跨服/同服开关、
     *               人在不在线、有没有人在倒计时、落点那个服装没装桥接。
     */
    private String rejection(final Player requester, final Player target,
                             final RequestType type, final boolean mutual) {
        final Configuration config = config();
        final UUID requesterId = requester.getUniqueId();
        final UUID targetId = target.getUniqueId();

        if (requesterId.equals(targetId)) {
            return "self-request";
        }

        // 被【这个人】拒绝过，还在封锁期里
        // ⚠️ 互相请求时不查：那条挂着的请求正是对方主动发过来的（他明明是想要的那方），
        //    这时候拿「你刚被他拒绝过」把人挡回去会很莫名其妙 —— 跟下面 target-disabled 一个道理
        // ⚠️ 也不设 bypass 权限：这条就是为了防止反复骚扰，给了绕过等于没配
        if (!mutual) {
            final long deniedLeft = denyRemaining(requesterId, targetId);
            if (deniedLeft > 0) {
                return "deny-cooldown:" + deniedLeft;
            }
        }

        // 冷却（⚠️ 互相请求不产生新请求，所以不算「又一次发起」，不卡冷却）
        if (!mutual && !Permissions.has(requester, Permissions.COOLDOWN_BYPASS, false)) {
            final Long last = lastRequestAt.get(requesterId);
            if (last != null) {
                final long seconds = config.cooldownSeconds();
                final long passed = (System.currentTimeMillis() - last) / 1000L;
                if (seconds > 0 && passed < seconds) {
                    return "cooldown:" + (seconds - passed);
                }
            }
        }

        // 子服名单（发起者这边 / 目标那边，两边都要过）
        final boolean bypassServer = config.serverBypassEnabled()
                && Permissions.has(requester, Permissions.SERVER_BYPASS, false);
        if (!bypassServer) {
            final String selfServer = Backend.serverName(requester);
            final String targetServer = Backend.serverName(target);
            final boolean selfOk = config.filter().allows(selfServer);
            final boolean targetOk = config.filter().allows(targetServer);
            // 名单是空的时候（= 全参与）没什么可看的，别在 /tpaall 里刷一屏
            if (config.debug() && !config.filter().servers().isEmpty()) {
                plugin.logger().info("[vtpa] 子服名单检查：" + requester.getUsername() + " @"
                        + (selfServer == null ? "?" : selfServer) + (selfOk ? " ✅" : " ❌")
                        + " → " + target.getUsername() + " @"
                        + (targetServer == null ? "?" : targetServer) + (targetOk ? " ✅" : " ❌"));
            }
            if (!selfOk) {
                return "server-denied-self:" + (selfServer == null ? "?" : selfServer);
            }
            if (!targetOk) {
                return "server-denied-target";
            }
        } else if (config.debug()) {
            plugin.logger().info("[vtpa] 子服名单检查：跳过 —— " + requester.getUsername()
                    + " 有 vtpa.server.bypass（检查 /lp user " + requester.getUsername()
                    + " permission check vtpa.server.bypass）");
        }

        // 跨服 / 同服开关
        final String selfServer = Backend.serverName(requester);
        final String targetServer = Backend.serverName(target);
        final boolean sameServer = selfServer != null && selfServer.equalsIgnoreCase(targetServer);
        if (sameServer && !config.allowSameServer()) {
            return "same-server-disabled";
        }
        if (!sameServer && !config.allowCrossServer()) {
            return "cross-server-disabled";
        }
        if (selfServer == null || targetServer == null) {
            return "target-connecting";
        }

        // 对方关了接收
        // ⚠️ 互相请求时「对方」正是先发那条请求的人 —— 人家早就表态了，
        //    这时候拿「他关了接收」把人挡回去会很莫名其妙（他明明是主动的那一方）
        if (!mutual && isDisabled(targetId)
                && !Permissions.has(requester, Permissions.TOGGLE_BYPASS, false)) {
            return "target-disabled";
        }

        // 两个人之间已经挂着一个未处理的请求（双向都算）
        // ⚠️ 走「互相请求」时不查这条 —— 那条挂着的请求正是我们打算替双方同意掉的那一条
        final TpaRequest existing = store().findBetween(requesterId, targetId);
        if (existing != null && !mutual) {
            return requesterId.equals(existing.requesterId()) ? "already-pending" : "reverse-pending";
        }

        // 发起者挂着的请求太多（⚠️ 同上：互相请求是「摘掉」一条，不是「再加」一条）
        if (!mutual && !Permissions.has(requester, Permissions.LIMIT_BYPASS, false)
                && store().outgoingCount(requesterId) >= config.maxOutgoingRequests()) {
            return "too-many-outgoing";
        }

        // 正在倒计时的人不能再接/再发
        final UUID mover = type.movesRequester() ? requesterId : targetId;
        final UUID destId = type.movesRequester() ? targetId : requesterId;
        if (plugin.teleporter().isBusy(mover)) {
            return mover.equals(requesterId) ? "self-busy" : "target-busy";
        }
        // ⚠️ 落点那个人也要看：他正在倒计时的话马上就要离开现在站的地方，
        //    再配上 lock-destination 就等于把人锁在一个「马上没人了」的点上
        if (plugin.teleporter().isBusy(destId)) {
            return destId.equals(requesterId) ? "self-busy" : "target-busy";
        }

        // 桥接：落点所在的服必须要有（不然拿不到坐标，也没法落地传送）
        final String destServer = serverNameOf(destId);

        // 🔴 「能不能落脚到那个服」：跨服时，被移动的那个人必须有 vtpa.to.<落点服>
        //
        //   查的是【被移动的那个人】，不是【敲命令的那个人】：
        //     /tpa B     动的是我自己、落点是 B  → 查我的 vtpa.to.<B 所在的服>
        //     /tpahere A 动的是 A、落点是我      → 查 A 的 vtpa.to.<我所在的服>
        //   所以「A 从 survival 落到 industry」这件事，不管是谁敲的命令，
        //   判据始终是 A 有没有 vtpa.to.industry —— 用户请求里的两个方向由此统一。
        //
        //   ⚠️ 只管【跨服】：两人本来就在同一个子服的话不查（否则连服内互传都要授权，太重）。
        //   ⚠️ 节点默认未定义 = 拒绝：不 grant 就是过不去。
        //   ⚠️ 互相请求（mutual）也照查 —— 它等价于「点了接受」，属于「传送能不能发生」那一类闸。
        if (!sameServer) {
            final Player traveller = mover.equals(requesterId) ? requester : target;
            if (!Permissions.mayTravelTo(traveller, destServer, config.toBypassEnabled())) {
                return (mover.equals(requesterId) ? "travel-denied-self:" : "travel-denied-target:")
                        + destServer;
            }
        }

        if (!plugin.backend().isReady(destServer)) {
            if (!sameServer && "switch".equals(config.bridgeMissing())) {
                // 跨服 + 配置允许 → 放行，但只能落到那个服的出生点（传送时会提示）
                return null;
            }
            return "bridge-missing:" + (destServer == null ? "?" : destServer);
        }
        return null;
    }

    /**
     * 发一条请求。
     *
     * <p>⚠️ 进来先看一眼是不是「互相请求」：对方已经发过一条、两条的结果又完全一样，
     * 那就不是发请求，而是直接同意（见 {@link #mutualPending}）。
     *
     * @param label 玩家实际敲的命令，用于提示语里的 {@code #label#}
     */
    public void sendRequest(final Player requester, final Player target,
                            final RequestType type, final String label) {
        final Configuration config = config();
        final long now = System.currentTimeMillis();
        final long timeout = config.requestTimeoutSeconds() * 1000L;
        final TpaRequest request = new TpaRequest(
                requester.getUniqueId(), requester.getUsername(),
                target.getUniqueId(), target.getUsername(),
                type, now, now + timeout);

        // ① 互相请求 → 直接同意（对方先发过、还没过期、两条结果一样）
        final TpaRequest pending = mutualPending(request, now);
        if (pending != null) {
            final String problem = rejection(requester, target, type, true);
            if (problem != null) {
                sendRejection(requester, problem, target.getUsername(), label);
                return;
            }
            store().remove(pending);
            lastRequestAt.put(requester.getUniqueId(), now);
            startMutual(pending);
            return;
        }

        // ② 普通路径：过闸门 → 挂到账本 → 通知对方
        final String problem = rejection(requester, target, type, false);
        if (problem != null) {
            sendRejection(requester, problem, target.getUsername(), label);
            return;
        }
        store().put(request);
        lastRequestAt.put(requester.getUniqueId(), now);

        // 文本配成空就不发这条（声音照播 —— 声音是独立的一档配置）
        if (!Colors.isBlank(rawRequest(request))) {
            target.sendMessage(buildRequestMessage(request));
        }
        // 对方听到「叮」的一声（CMI 的 TpaRequest）
        plugin.backend().sound(target, config.sound("request"));
        final String seconds = String.valueOf(config.requestTimeoutSeconds());
        final String sentRaw = config.rawMessage("request-sent");
        if (!Colors.isBlank(sentRaw)) {
            requester.sendMessage(buildSentMessage(sentRaw, target.getUsername(), seconds));
        }
        if (config.logToConsole()) {
            plugin.logger().info("[vtpa] " + requester.getUsername() + " -> " + target.getUsername()
                    + " (" + type + ")");
        }
    }

    /**
     * 找出「对方先发过一条、而且跟我要发的这条<b>结果一模一样</b>」的那条请求。
     *
     * <p>这就是「互相请求」：两边各说了一次，说的还是同一件事 ——
     * A 敲 {@code /tpa B}（我想去你那儿）之后 B 敲 {@code /tpahere A}（你过来吧），
     * 两条都是「A 传送到 B」，没必要再让谁点一次接受。
     *
     * <p>三条硬条件，少一条都不算：
     * <ol>
     *   <li>那条是<b>对面</b>先发的（自己重发自己那条走「你已经请求过了」）；</li>
     *   <li>还没过有效期（过期了就当没有 —— 对方的意愿已经作废了）；</li>
     *   <li>动的是同一个人、落点也是同一个人（{@link TpaRequest#sameOutcomeAs}）。</li>
     * </ol>
     *
     * @return 可以「直接同意」的那条请求；没有 / 开关关了 / 已经过期 → null
     */
    private TpaRequest mutualPending(final TpaRequest candidate, final long now) {
        if (!config().reverseAutoAccept()) {
            return null;
        }
        final TpaRequest pending = store().findBetween(candidate.requesterId(), candidate.targetId());
        if (pending == null || pending.isExpired(now)) {
            return null;
        }
        if (pending.requesterId().equals(candidate.requesterId())) {
            // 是我自己之前发的 —— 那叫「重复请求」，不叫「互相请求」
            return null;
        }
        return pending.sameOutcomeAs(candidate) ? pending : null;
    }

    /**
     * 双方互相请求 → 直接进倒计时。
     *
     * <p>跟「点接受」那条路唯一的区别是提示语：两边都被告知「双方都同意了」，
     * 而不是「你接受了 / 对方接受了」—— 因为这里确实是两个人各自表达了同一个意思。
     * 倒计时、移动取消、落点锁定、切服这些完全复用 {@link Teleporter#start}。
     *
     * @param pending 先发的那条（它才是被「同意」掉的那一条）
     */
    private void startMutual(final TpaRequest pending) {
        final Configuration config = config();
        final long seconds = Math.max(0L, config.teleportDelaySeconds());
        final Optional<Player> mover = proxy().getPlayer(pending.moverId());
        final Optional<Player> dest = proxy().getPlayer(pending.destinationId());
        if (mover.isEmpty() || dest.isEmpty()) {
            // 闸门里查过两边都在线，正常走不到这儿；真走到了就当普通请求被拦下，别硬传
            mover.ifPresent(p -> plugin.send(p, config.message("offline-abort")));
            dest.ifPresent(p -> plugin.send(p, config.message("offline-abort")));
            return;
        }
        // 老配置（1.2.0 之前生成的）里没有这两条 —— 退回「已接受」，别发出「缺少配置项」
        final String moverKey = config.hasMessage("mutual-accept-mover")
                ? "mutual-accept-mover" : "accepted-self";
        final String destKey = config.hasMessage("mutual-accept-dest")
                ? "mutual-accept-dest" : "accepted-other";
        plugin.send(mover.get(), config.message(moverKey,
                "player", pending.destinationName(), "seconds", String.valueOf(seconds)));
        plugin.send(dest.get(), config.message(destKey,
                "player", pending.moverName(), "seconds", String.valueOf(seconds)));
        if (config.logToConsole()) {
            plugin.logger().info("[vtpa] " + pending.moverName() + " ↔ " + pending.destinationName()
                    + " 互相请求，自动同意：" + pending.moverName() + " → " + pending.destinationName());
        }
        plugin.teleporter().start(pending);
    }

    /** 把 {@link #rejection} 返回的 "原因" 翻译成一条提示语发给发起者。 */
    private void sendRejection(final Player requester, final String problem,
                               final String targetName, final String label) {
        final Configuration config = config();
        final int colon = problem.indexOf(':');
        final String key = colon < 0 ? problem : problem.substring(0, colon);
        final String extra = colon < 0 ? "" : problem.substring(colon + 1);
        switch (key) {
            case "cooldown":
                plugin.send(requester, config.message("cooldown", "seconds", extra));
                return;
            case "deny-cooldown":
                // #player# / #target# 都是「刚才拒绝我的那个人」，#seconds# 是还剩多久解封
                plugin.send(requester, config.message("deny-cooldown", "seconds", extra,
                        "player", targetName, "target", targetName));
                return;
            case "server-denied-self":
                plugin.send(requester, config.message("server-denied-self", "server", extra));
                return;
            case "bridge-missing":
                plugin.send(requester, config.message("bridge-missing", "server", extra));
                return;
            case "travel-denied-self":
            case "travel-denied-target":
                // #target# 照样是「另一个人」—— travel-denied-target 里指的就是
                // 真正要被挪过去的那位（/tpahere 时是自己以外的那个目标）
                plugin.send(requester, config.message(key, "target", targetName,
                        "label", label, "server", extra));
                return;
            default:
                // "max" 给 too-many-outgoing 那条（"最多 #max# 个"）补上上限值，
                // 不补的话玩家看到的是字面量 #max#
                plugin.send(requester, config.message(key, "target", targetName, "label", label,
                        "max", String.valueOf(config.maxOutgoingRequests())));
        }
    }

    /**
     * {@code /tpaall} —— 给所有在线玩家发一条「传送到我这儿」的请求。
     *
     * <p>不合适的人（自己、名单外的服、关了接收、已经挂着一个请求的、正在传送的）
     * 一律静默跳过，最后只给发起者一条汇总，免得刷屏。
     */
    public void sendToAll(final Player requester) {
        final List<Player> everyone = new ArrayList<>(proxy().getAllPlayers());
        everyone.removeIf(p -> p.getUniqueId().equals(requester.getUniqueId()));
        batchSend(requester, everyone, "request-sent-all", "tpaall");
    }

    /**
     * {@code /tpaserver} —— 只发给<b>跟我同一个子服</b>的人（跨服的不打扰）。
     *
     * <p>子服归属代理自己就知道，不用问子服，所以是同步的。
     */
    public void sendToServer(final Player requester) {
        batchSend(requester, sameServerPlayers(requester), "request-sent-server", "tpaserver");
    }

    /**
     * {@code /tpaworld} —— 只发给<b>跟我同一个子服、且同一个世界</b>的人。
     *
     * <p>⚠️ 世界是子服的概念，代理不知道谁在哪个世界，得先问一圈坐标
     * （{@code Loc} 里带世界名）再筛。这一步是异步的，所以汇总会晚几十毫秒才发出来。
     */
    public void sendToWorld(final Player requester) {
        final Configuration config = config();
        // 1) 先问自己在哪个世界
        plugin.backend().queryLocation(requester, self -> {
            if (self.isEmpty()) {
                plugin.send(requester, config.message("world-unknown"));
                return;
            }
            final String myWorld = self.get().world();
            final List<Player> candidates = sameServerPlayers(requester);
            if (candidates.isEmpty()) {
                batchSend(requester, List.of(), "request-sent-world", "tpaworld");
                return;
            }
            // 2) 再挨个问他们的世界，全回来了一起筛
            final Map<UUID, String> worlds = new ConcurrentHashMap<>();
            final AtomicInteger left = new AtomicInteger(candidates.size());
            for (final Player target : candidates) {
                plugin.backend().queryLocation(target, loc -> {
                    if (loc.isPresent()) {
                        worlds.put(target.getUniqueId(), loc.get().world());
                    }
                    if (left.decrementAndGet() == 0) {
                        final List<Player> same = new ArrayList<>();
                        for (final Player p : candidates) {
                            if (myWorld.equals(worlds.get(p.getUniqueId()))) {
                                same.add(p);
                            }
                        }
                        batchSend(requester, same, "request-sent-world", "tpaworld");
                    }
                });
            }
        });
    }

    /** 跟我同一个子服、且不是我的在线玩家。 */
    private List<Player> sameServerPlayers(final Player requester) {
        final String myServer = Backend.serverName(requester);
        final List<Player> out = new ArrayList<>();
        if (myServer == null) {
            return out;
        }
        for (final Player p : new ArrayList<>(proxy().getAllPlayers())) {
            if (p.getUniqueId().equals(requester.getUniqueId())) {
                continue;
            }
            final String server = Backend.serverName(p);
            if (server != null && server.equalsIgnoreCase(myServer)) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * 批量发「传送到我这儿」：不合适的人静默跳过，最后只回一条汇总（免得刷屏）。
     *
     * <p>{@code /tpaall}、{@code /tpaserver}、{@code /tpaworld} 三条共用这一段。
     */
    private void batchSend(final Player requester, final Collection<Player> targets,
                           final String summaryKey, final String logName) {
        final Configuration config = config();
        if (!Permissions.has(requester, Permissions.COOLDOWN_BYPASS, false)) {
            final Long last = lastRequestAt.get(requester.getUniqueId());
            if (last != null) {
                final long seconds = config.cooldownSeconds();
                final long passed = (System.currentTimeMillis() - last) / 1000L;
                if (seconds > 0 && passed < seconds) {
                    plugin.send(requester, config.message("cooldown", "seconds",
                            String.valueOf(seconds - passed)));
                    return;
                }
            }
        }
        final long timeout = config.requestTimeoutSeconds() * 1000L;
        int sent = 0;
        int skipped = 0;
        for (final Player target : targets) {
            // 群发不参与「互相请求直接同意」—— 忽然把人传走太突然，还是让对方自己点
            if (rejection(requester, target, RequestType.HERE, false) != null) {
                skipped++;
                continue;
            }
            final long now = System.currentTimeMillis();
            final TpaRequest request = new TpaRequest(
                    requester.getUniqueId(), requester.getUsername(),
                    target.getUniqueId(), target.getUsername(),
                    RequestType.HERE, now, now + timeout);
            store().put(request);
            // 跟单人路径一样：文本配成空就不发（不然每个人都会收到一条空消息）
            if (Colors.isBlank(rawRequest(request))) {
                skipped++;
                continue;
            }
            target.sendMessage(buildRequestMessage(request));
            // 对方听到「叮」的一声（CMI 的 TpaRequest）
            plugin.backend().sound(target, config.sound("request"));
            sent++;
        }
        // ⚠️ 一条都没发出去（全被跳过）就不记冷却 —— 玩家什么都没干成，不该被扣时间
        if (sent > 0) {
            lastRequestAt.put(requester.getUniqueId(), System.currentTimeMillis());
        }
        plugin.send(requester, config.message(summaryKey,
                "amount", String.valueOf(sent), "skipped", String.valueOf(skipped)));
        if (config.logToConsole()) {
            plugin.logger().info("[vtpa] " + requester.getUsername() + " /" + logName
                    + "：发出 " + sent + " 条，跳过 " + skipped + " 人。");
        }
    }

    // ------------------------------------------------------------------
    // 接受 / 拒绝 / 取消
    // ------------------------------------------------------------------

    /** 从某人收到的请求里挑出一条：给了名字就按名字找，没给就只有一条才认。 */
    private TpaRequest pick(final Player viewer, final String nameArg,
                            final List<TpaRequest> candidates, final String emptyKey) {
        final Configuration config = config();
        if (candidates.isEmpty()) {
            plugin.send(viewer, config.message(emptyKey));
            return null;
        }
        if (nameArg != null && !nameArg.isBlank()) {
            final Player resolved = resolve(viewer, nameArg);
            if (resolved == null) {
                return null;
            }
            for (final TpaRequest candidate : candidates) {
                if (candidate.other(viewer.getUniqueId()).equals(resolved.getUniqueId())) {
                    return candidate;
                }
            }
            plugin.send(viewer, config.message(emptyKey));
            return null;
        }
        if (candidates.size() > 1) {
            final StringBuilder names = new StringBuilder();
            for (final TpaRequest candidate : candidates) {
                if (names.length() > 0) {
                    names.append("、");
                }
                names.append(candidate.otherName(viewer.getUniqueId()));
            }
            plugin.send(viewer, config.message("pick-one", "list", names.toString()));
            return null;
        }
        return candidates.get(0);
    }

    public void accept(final Player viewer, final String nameArg) {
        final Configuration config = config();
        final TpaRequest request = pick(viewer, nameArg,
                store().incomingTo(viewer.getUniqueId()), "no-incoming");
        if (request == null) {
            return;
        }
        final Optional<Player> requester = proxy().getPlayer(request.requesterId());
        if (requester.isEmpty()) {
            store().remove(request);
            plugin.send(viewer, config.message("offline-abort"));
            return;
        }
        // 接受之前再查一次子服名单 —— 请求挂着的三分钟里，任意一方都可能换到名单外的服
        if (!(config.serverBypassEnabled()
                && Permissions.has(viewer, Permissions.SERVER_BYPASS, false))) {
            final String selfServer = Backend.serverName(viewer);
            final String otherServer = Backend.serverName(requester.get());
            if (!config.filter().allows(selfServer)) {
                plugin.send(viewer, config.message("server-denied-self",
                        "server", selfServer == null ? "?" : selfServer));
                return;
            }
            if (!config.filter().allows(otherServer)) {
                plugin.send(viewer, config.message("server-denied-target", "target", request.otherName(viewer.getUniqueId())));
                return;
            }
        }
        if (plugin.teleporter().isBusy(viewer.getUniqueId())) {
            plugin.send(viewer, config.message("self-busy"));
            return;
        }
        if (plugin.teleporter().isBusy(request.requesterId())) {
            plugin.send(viewer, config.message("target-busy", "target", request.requesterName()));
            return;
        }

        // 接受之前再查一次 vtpa.to.<落点服> —— 请求挂着的三分钟里两人都可能换过服，
        // 「落点在哪个服」跟着就变了，发请求那一刻查过的不算数。
        // ⚠️ 同样只管跨服（见 rejection 里那道的说明）。
        final Optional<Player> mover = proxy().getPlayer(request.moverId());
        final Optional<Player> destination = proxy().getPlayer(request.destinationId());
        if (mover.isPresent() && destination.isPresent()) {
            final String arrival = Backend.serverName(destination.get());
            final String departure = Backend.serverName(mover.get());
            if (arrival != null && (departure == null || !departure.equalsIgnoreCase(arrival))
                    && !Permissions.mayTravelTo(mover.get(), arrival, config.toBypassEnabled())) {
                if (request.moverId().equals(viewer.getUniqueId())) {
                    plugin.send(viewer, config.message("travel-denied-self", "server", arrival));
                } else {
                    plugin.send(viewer, config.message("travel-denied-target",
                            "target", mover.get().getUsername(), "server", arrival));
                }
                return;
            }
        }

        store().remove(request);
        final long seconds = Math.max(0L, config.teleportDelaySeconds());
        plugin.send(viewer, config.message("accepted-self",
                "player", request.otherName(viewer.getUniqueId()),
                "seconds", String.valueOf(seconds)));
        plugin.send(requester.get(), config.message("accepted-other",
                "player", viewer.getUsername(),
                "seconds", String.valueOf(seconds)));
        plugin.teleporter().start(request);
    }

    public void deny(final Player viewer, final String nameArg) {
        final Configuration config = config();
        final TpaRequest request = pick(viewer, nameArg,
                store().incomingTo(viewer.getUniqueId()), "no-incoming");
        if (request == null) {
            return;
        }
        store().remove(request);
        // 从此刻起一段时间内，对方不能再给【我】发请求（时长 general.deny-cooldown-seconds）
        markDenied(request.requesterId(), viewer.getUniqueId());
        plugin.send(viewer, config.message("denied-self", "player", request.otherName(viewer.getUniqueId())));
        final Optional<Player> requester = proxy().getPlayer(request.requesterId());
        if (requester.isPresent()) {
            plugin.send(requester.get(), config.message("denied-other", "target", viewer.getUsername()));
            // 被拒绝的人听到一声（默认空，想要就自己在 [sounds] 里填）
            plugin.backend().sound(requester.get(), config.sound("deny"));
        }
    }

    /** 发起者撤回自己发出的请求。 */
    public void cancel(final Player viewer, final String nameArg) {
        final Configuration config = config();
        // ⚠️ 顺序很关键：先在账本里找这条请求，找到了就<b>只撤请求</b>，别去动倒计时 ——
        //    名字是「撤回发给谁的那条」（[撤回] 按钮就是这么点的），跟正在进行的传送是两回事。
        //    早先这里是先掐倒计时的：点一下 [撤回] 会把另一场正在进行的传送一起掐掉。
        //    只有没给名字、或者账本里查无此条（多半是已经被接受、正在倒计时）才去掐倒计时。
        TpaRequest request = nameArg == null || nameArg.isBlank()
                ? null : findOutgoing(viewer, nameArg);
        if (request == null && plugin.teleporter().abortCountdown(viewer.getUniqueId())) {
            return;
        }
        if (request == null) {
            request = pick(viewer, nameArg, store().outgoingFrom(viewer.getUniqueId()),
                    "cancelled-none");
        }
        if (request == null) {
            return;
        }
        store().remove(request);
        plugin.send(viewer, config.message("cancelled-self", "target", request.targetName()));
        final Optional<Player> target = proxy().getPlayer(request.targetId());
        if (target.isPresent()) {
            plugin.send(target.get(), config.message("cancelled-other", "player", viewer.getUsername()));
        }
    }

    /** 在某人发出的请求里按名字找一条（<b>不发任何提示</b>），找不到返回 null。 */
    private TpaRequest findOutgoing(final Player viewer, final String nameArg) {
        final String lower = nameArg.trim().toLowerCase(Locale.ROOT);
        for (final TpaRequest candidate : store().outgoingFrom(viewer.getUniqueId())) {
            if (candidate.targetName() != null
                    && candidate.targetName().toLowerCase(Locale.ROOT).equals(lower)) {
                return candidate;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 过期 / 掉线
    // ------------------------------------------------------------------

    /** 请求过期：通知双方并从账本里摘掉。 */
    public void expire(final List<TpaRequest> expired) {
        final Configuration config = config();
        if (!config.notifyOnExpire()) {
            return;
        }
        for (final TpaRequest request : expired) {
            notifyBoth(request, "expired");
        }
    }

    /** 有人掉线：把跟他相关的请求全清掉，并通知另一方。 */
    public void abortFor(final UUID uuid) {
        final Configuration config = config();
        final List<TpaRequest> removed = store().removeAllFor(uuid);
        if (!config.notifyOnDisconnect()) {
            return;
        }
        for (final TpaRequest request : removed) {
            final Optional<Player> other = proxy().getPlayer(request.other(uuid));
            if (other.isPresent()) {
                plugin.send(other.get(), config.message("offline-abort",
                        "player", request.otherName(uuid),
                        "target", request.otherName(uuid)));
            }
        }
    }

    private void notifyBoth(final TpaRequest request, final String kind) {
        final Configuration config = config();
        final Optional<Player> requester = proxy().getPlayer(request.requesterId());
        final Optional<Player> target = proxy().getPlayer(request.targetId());
        if (requester.isPresent()) {
            plugin.send(requester.get(), config.message(kind + "-other",
                    "player", request.targetName(), "target", request.targetName()));
        }
        if (target.isPresent()) {
            plugin.send(target.get(), config.message(kind + "-self",
                    "player", request.requesterName(), "target", request.requesterName()));
        }
    }

    // ------------------------------------------------------------------
    // 请求消息（含可点击按钮）
    // ------------------------------------------------------------------

    /**
     * 拼出那条发给<b>被请求者</b>的消息：文本里的 {@code #accept#} / {@code #deny#}
     * 会被换成可点击的组件。
     */
    public Component buildRequestMessage(final TpaRequest request) {
        final Configuration config = config();
        final String raw = request.type() == RequestType.TPA ? config.requestTpa() : config.requestHere();
        final Player requester = proxy().getPlayer(request.requesterId()).orElse(null);
        final String fromServer = requester == null ? null : Backend.serverName(requester);
        return renderButtons(raw, request.requesterName(),
                fromServer == null ? "?" : fromServer,
                String.valueOf(request.remainingSeconds(System.currentTimeMillis())));
    }

    /**
     * 拼出那条发给<b>发起者</b>的「已发送请求」：文本里的 {@code #cancel#}
     * 会变成撤回按钮（点了等于自己敲 {@code /tpacancel 对方}）。
     *
     * <p>⚠️ 这里的 {@code %player%} 指的是<b>对方</b>（被请求的人）——
     * 因为撤回命令要指定「撤回发给谁的那个」。
     */
    public Component buildSentMessage(final String raw, final String targetName, final String seconds) {
        final Player target = proxy().getAllPlayers().stream()
                .filter(p -> p.getUsername().equalsIgnoreCase(targetName))
                .findFirst().orElse(null);
        final String server = target == null ? "?" : String.valueOf(Backend.serverName(target));
        // #target# / #seconds# 是这条消息自己的占位符，先替掉再交给按钮渲染
        final String prepared = raw == null ? "" : raw
                .replace("#target#", targetName)
                .replace("#seconds#", seconds == null ? "" : seconds);
        return renderButtons(prepared, targetName, server, seconds);
    }

    /** 这条请求用的原始文本（TPA / TPAHERE 各一句）。 */
    private String rawRequest(final TpaRequest request) {
        final Configuration config = config();
        return request.type() == RequestType.TPA ? config.requestTpa() : config.requestHere();
    }

    /** 把 {@code #accept#} / {@code #deny#} / {@code #cancel#} 三个占位符换成可点击按钮。 */
    private Component renderButtons(final String raw, final String who,
                                    final String server, final String time) {
        final Configuration config = config();
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        final TextComponent.Builder out = Component.text();
        int cursor = 0;
        while (cursor < raw.length()) {
            final int atAccept = raw.indexOf(TOKEN_ACCEPT, cursor);
            final int atDeny = raw.indexOf(TOKEN_DENY, cursor);
            final int atCancel = raw.indexOf(TOKEN_CANCEL, cursor);
            final int at = earliest(atAccept, earliest(atDeny, atCancel));
            if (at < 0) {
                break;
            }
            final String token;
            final Configuration.Button button;
            if (at == atAccept) {
                token = TOKEN_ACCEPT;
                button = config.acceptButton();
            } else if (at == atDeny) {
                token = TOKEN_DENY;
                button = config.denyButton();
            } else {
                token = TOKEN_CANCEL;
                button = config.cancelButton();
            }
            if (at > cursor) {
                out.append(Colors.colorize(vars(raw.substring(cursor, at), who, server, time)));
            }
            out.append(button(button, who, server, time));
            cursor = at + token.length();
        }
        if (cursor < raw.length()) {
            out.append(Colors.colorize(vars(raw.substring(cursor), who, server, time)));
        }
        return out.build();
    }

    /** 两个下标里取更靠前那个（-1 表示没有）。 */
    private static int earliest(final int a, final int b) {
        if (a < 0) {
            return b;
        }
        if (b < 0) {
            return a;
        }
        return Math.min(a, b);
    }

    private static String vars(final String text, final String player,
                               final String server, final String time) {
        return text.replace("%player%", player)
                .replace("#player#", player)
                .replace("%server%", server)
                .replace("#server#", server)
                .replace("%time%", time)
                .replace("#time#", time);
    }

    private static Component button(final Configuration.Button button, final String player,
                                    final String server, final String time) {
        final String command = vars(button.command(), player, server, time);
        final String hover = vars(button.hover(), player, server, time);
        Component component = Colors.colorize(vars(button.text(), player, server, time))
                .clickEvent(ClickEvent.runCommand(command));
        if (!hover.isBlank()) {
            component = component.hoverEvent(HoverEvent.showText(Colors.colorize(hover)));
        }
        return component;
    }

    // ------------------------------------------------------------------
    // 找人
    // ------------------------------------------------------------------

    /**
     * 按名字找在线玩家：先完全匹配（大小写不敏感），再前缀匹配；
     * 多个前缀匹配就报「有歧义」。找不到会给 {@code source} 发提示语并返回 null。
     */
    public Player resolve(final CommandSource source, final String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        final String lower = name.toLowerCase(Locale.ROOT);
        Player prefixHit = null;
        int prefixHits = 0;
        for (final Player player : proxy().getAllPlayers()) {
            final String candidate = player.getUsername().toLowerCase(Locale.ROOT);
            if (candidate.equals(lower)) {
                return player;
            }
            if (candidate.startsWith(lower)) {
                prefixHit = player;
                prefixHits++;
            }
        }
        if (prefixHits == 1) {
            return prefixHit;
        }
        final Configuration config = config();
        plugin.send(source, config.message(prefixHits > 1 ? "ambiguous-target" : "player-not-found",
                "target", name));
        return null;
    }

    private String serverNameOf(final UUID uuid) {
        final Optional<Player> player = proxy().getPlayer(uuid);
        return player.map(Backend::serverName).orElse(null);
    }
}
