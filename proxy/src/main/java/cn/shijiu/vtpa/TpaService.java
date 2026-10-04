package cn.shijiu.vtpa;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    // 发请求
    // ------------------------------------------------------------------

    /**
     * 检查「发起者能不能给目标发一条请求」，返回 null 表示可以发，
     * 否则返回要提示给发起者的那条消息的 key（以及要替换的占位符）。
     *
     * <p>单独拆出来是为了 {@code /tpaall} 复用 —— 它要静默跳过不合适的人，
     * 不能每人弹一条提示。
     */
    private String rejection(final Player requester, final Player target, final RequestType type) {
        final Configuration config = config();
        final UUID requesterId = requester.getUniqueId();
        final UUID targetId = target.getUniqueId();

        if (requesterId.equals(targetId)) {
            return "self-request";
        }

        // 冷却
        if (!Permissions.has(requester, Permissions.COOLDOWN_BYPASS, false)) {
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
        if (!Permissions.has(requester, Permissions.SERVER_BYPASS, false)) {
            final String selfServer = Backend.serverName(requester);
            final String targetServer = Backend.serverName(target);
            if (!config.filter().allows(selfServer)) {
                return "server-denied-self:" + (selfServer == null ? "?" : selfServer);
            }
            if (!config.filter().allows(targetServer)) {
                return "server-denied-target";
            }
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
        if (isDisabled(targetId) && !Permissions.has(requester, Permissions.TOGGLE_BYPASS, false)) {
            return "target-disabled";
        }

        // 两个人之间已经挂着一个未处理的请求（双向都算）
        final TpaRequest existing = store().findBetween(requesterId, targetId);
        if (existing != null) {
            return requesterId.equals(existing.requesterId()) ? "already-pending" : "reverse-pending";
        }

        // 发起者挂着的请求太多
        if (!Permissions.has(requester, Permissions.LIMIT_BYPASS, false)
                && store().outgoingCount(requesterId) >= config.maxOutgoingRequests()) {
            return "too-many-outgoing";
        }

        // 正在倒计时的人不能再接/再发
        final UUID mover = type.movesRequester() ? requesterId : targetId;
        if (plugin.teleporter().isBusy(mover)) {
            return mover.equals(requesterId) ? "self-busy" : "target-busy";
        }

        // 桥接：落点所在的服必须要有（不然拿不到坐标，也没法落地传送）
        final UUID destId = type.movesRequester() ? targetId : requesterId;
        final String destServer = serverNameOf(destId);
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
     * @param label 玩家实际敲的命令，用于提示语里的 {@code #label#}
     */
    public void sendRequest(final Player requester, final Player target,
                            final RequestType type, final String label) {
        final Configuration config = config();
        final String problem = rejection(requester, target, type);
        if (problem != null) {
            sendRejection(requester, problem, target.getUsername(), label);
            return;
        }
        final long timeout = config.requestTimeoutSeconds() * 1000L;
        final long now = System.currentTimeMillis();
        final TpaRequest request = new TpaRequest(
                requester.getUniqueId(), requester.getUsername(),
                target.getUniqueId(), target.getUsername(),
                type, now, now + timeout);
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
            case "server-denied-self":
                plugin.send(requester, config.message("server-denied-self", "server", extra));
                return;
            case "bridge-missing":
                plugin.send(requester, config.message("bridge-missing", "server", extra));
                return;
            default:
                plugin.send(requester, config.message(key, "target", targetName, "label", label));
        }
    }

    /**
     * {@code /tpaall} —— 给所有在线玩家发一条「传送到我这儿」的请求。
     *
     * <p>不合适的人（自己、名单外的服、关了接收、已经挂着一个请求的、正在传送的）
     * 一律静默跳过，最后只给发起者一条汇总，免得刷屏。
     */
    public void sendToAll(final Player requester) {
        final Configuration config = config();
        if (!Permissions.has(requester, Permissions.COOLDOWN_BYPASS, false)) {
            final Long last = lastRequestAt.get(requester.getUniqueId());
            if (last != null) {
                final long seconds = config.cooldownSeconds();
                final long passed = (System.currentTimeMillis() - last) / 1000L;
                if (seconds > 0 && passed < seconds) {
                    plugin.send(requester, config.message("cooldown", "seconds", String.valueOf(seconds - passed)));
                    return;
                }
            }
        }
        final long timeout = config.requestTimeoutSeconds() * 1000L;
        int sent = 0;
        int skipped = 0;
        for (final Player target : new ArrayList<>(proxy().getAllPlayers())) {
            if (target.getUniqueId().equals(requester.getUniqueId())) {
                continue;
            }
            if (rejection(requester, target, RequestType.HERE) != null) {
                skipped++;
                continue;
            }
            final long now = System.currentTimeMillis();
            final TpaRequest request = new TpaRequest(
                    requester.getUniqueId(), requester.getUsername(),
                    target.getUniqueId(), target.getUsername(),
                    RequestType.HERE, now, now + timeout);
            store().put(request);
            target.sendMessage(buildRequestMessage(request));
            sent++;
        }
        lastRequestAt.put(requester.getUniqueId(), System.currentTimeMillis());
        plugin.send(requester, config.message("request-sent-all",
                "amount", String.valueOf(sent), "skipped", String.valueOf(skipped)));
        if (config.logToConsole()) {
            plugin.logger().info("[vtpa] " + requester.getUsername() + " /tpaall：发出 " + sent
                    + " 条，跳过 " + skipped + " 人。");
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
        if (!Permissions.has(viewer, Permissions.SERVER_BYPASS, false)) {
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
        // 请求被接受后就进倒计时了（账本里已经没有它）—— 这时候撤回要连倒计时一起掐掉，
        // 不然玩家看到「已取消」，三秒后照样被传走。
        if (plugin.teleporter().abortCountdown(viewer.getUniqueId())) {
            return;
        }
        final List<TpaRequest> outgoing = store().outgoingFrom(viewer.getUniqueId());
        final TpaRequest request = pick(viewer, nameArg, outgoing, "cancelled-none");
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
