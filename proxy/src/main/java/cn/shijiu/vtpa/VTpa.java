package cn.shijiu.vtpa;

import cn.shijiu.vtpa.command.RootCommand;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * VTpa —— 跨服传送请求。
 *
 * <p>代理端负责：命令、请求账本、倒计时、子服名单、提示语、切服。
 * 子服端（VTpaBridge）负责：报坐标、落地传送。两边靠插件消息（默认 {@code vtpa:main}）说话。
 *
 * <p>为什么非得有子服那一半：代理只知道「谁在哪个服」，<b>不知道坐标也挪不动人</b>。
 * 不装桥接的话，跨服最多只能把人切到对方的服（落到出生点），同服则什么都做不了。
 */
@Plugin(
        id = "vtpa",
        name = "VTpa",
        version = "1.5.0",
        description = "跨服传送请求：/tpa /tpahere /tpall /tpaccept /tpadeny",
        authors = {"拾玖世界"}
)
public final class VTpa {

    /** Tab 补全一次最多给这么多条 —— 几百人在线时不至于把客户端刷爆。 */
    public static final int MAX_SUGGESTIONS = 50;

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final RequestStore store;
    private final Backend backend;
    private final Teleporter teleporter;
    private final TpaService service;

    private volatile Configuration config;
    /** 上一次读到的 config.toml 修改时间，热重载用。 */
    private volatile long lastModified;

    @Inject
    public VTpa(final ProxyServer proxy, final Logger logger, @DataDirectory final Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.store = new RequestStore();
        this.config = Configuration.defaults();
        this.backend = new Backend(this);
        this.teleporter = new Teleporter(this);
        this.service = new TpaService(this);
    }

    @Subscribe
    public void onProxyInitialization(final ProxyInitializeEvent event) {
        reload(null);
        backend.register();
        registerCommands();
        loadToggles();
        startSweeps();
        startAutoReload();

        logger.info("[vtpa] VTpa 已就绪 —— 命令 " + config.label()
                + " tpa / tpahere / tpaccept / tpadeny / tpall。"
                + "子服记得装 VTpaBridge，不然只能切服、不能落到具体位置。");
    }

    /** 掉线：请求清掉、倒计时取消、落点作废。 */
    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        final Player player = event.getPlayer();
        teleporter.abortFor(player.getUniqueId());
        service.abortFor(player.getUniqueId());
    }

    /** 进到（或切换到）某个服：给那个服发心跳，顺便看看有没有等着落地的传送。 */
    @Subscribe
    public void onServerPostConnect(final ServerPostConnectEvent event) {
        final RegisteredServer server = event.getPlayer().getCurrentServer()
                .map(connection -> connection.getServer())
                .orElse(null);
        if (server != null) {
            backend.ping(server);
            teleporter.onServerConnect(event.getPlayer(), server.getServerInfo().getName());
        }
    }

    /** 子服发回来的插件消息。 */
    @Subscribe
    public void onPluginMessage(final PluginMessageEvent event) {
        backend.handle(event);
    }

    // ------------------------------------------------------------------
    // 命令
    // ------------------------------------------------------------------

    private void registerCommands() {
        final RootCommand root = new RootCommand(this);
        register("vtpa", root, config.rootAliases());
        registerShortcuts(root);
    }

    /**
     * {@code [shortcuts]}：把子命令直接注册成顶层命令（默认接管 {@code /tpa /tpahere}
     * 等 —— 后端子服自己的 /tpa（CMI 那个）会被彻底盖掉，全服传送请求统一走这里）。
     *
     * <p>⚠️ 命令名是起服时注册的，改这里要重启代理（配置本身仍可 {@code /vtpa reload}）。
     */
    private void registerShortcuts(final RootCommand root) {
        final List<String> taken = new ArrayList<>();
        taken.add("vtpa");
        for (final String alias : config.rootAliases()) {
            taken.add(alias.toLowerCase(Locale.ROOT));
        }
        final List<String> ok = new ArrayList<>();
        for (final Map.Entry<String, String> e : config.shortcuts().entrySet()) {
            final String name = e.getKey();
            if (taken.contains(name)) {
                logger.warn("[vtpa] 快捷命令 /" + name + " 跟主命令重名，跳过。");
                continue;
            }
            final SimpleCommand command = root.lookup(e.getValue());
            if (command == null) {
                logger.warn("[vtpa] 快捷命令 /" + name + " 指向了不存在的子命令「" + e.getValue() + "」，跳过。");
                continue;
            }
            try {
                register(name, command, List.of());
                ok.add("/" + name);
            } catch (final Exception ex) {
                logger.warn("[vtpa] 快捷命令 /" + name + " 没注册上（可能别的插件已经占用）：" + ex);
            }
        }
        if (!ok.isEmpty()) {
            logger.info("[vtpa] 已接管顶层命令：" + String.join(" ", ok)
                    + "（代理优先处理，后端子服同名的命令不会再收到）");
        }
    }

    private void register(final String name, final SimpleCommand command, final List<String> aliases) {
        // ⚠️ 主名和别名一律小写、并去重：Velocity 底层走 Brigadier，literal 节点大小写敏感，
        //    注册成大写的话，敲小写会"命令不存在"，还会被转发给后端
        final String lower = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        final List<String> clean = new ArrayList<>();
        for (final String alias : aliases) {
            final String candidate = alias == null ? "" : alias.trim().toLowerCase(Locale.ROOT);
            // 空串、跟主名重名、已经在列表里 —— 都别注册（重名会让 Velocity 直接拒绝注册）
            if (candidate.isEmpty() || candidate.equals(lower) || clean.contains(candidate)) {
                continue;
            }
            clean.add(candidate);
        }
        final CommandManager manager = proxy.getCommandManager();
        final CommandMeta meta = manager.metaBuilder(lower)
                .aliases(clean.toArray(new String[0]))
                .plugin(this)
                .build();
        manager.register(meta, command);
    }

    // ------------------------------------------------------------------
    // 定时任务
    // ------------------------------------------------------------------

    private void startSweeps() {
        // 过期清扫：每秒扫一遍账本，到点的摘掉并通知双方
        proxy.getScheduler().buildTask(this, () -> {
            // ⚠️ 这一步抛异常会把整个重复任务停掉（之后请求再也不过期）—— 宁可漏一轮
            try {
                final List<TpaRequest> expired = store.removeExpired(System.currentTimeMillis());
                if (!expired.isEmpty()) {
                    service.expire(expired);
                }
                // 顺手把到期的「被拒绝封锁」记录清掉（不清也不影响功能，只是白占内存）
                service.purgeDenyCooldowns();
            } catch (final Exception e) {
                logger.warn("[vtpa] 清扫过期请求时出错（这一轮跳过，任务继续）：" + e);
            }
        }).repeat(1L, TimeUnit.SECONDS).schedule();

        // 桥接心跳：定期问有人的服「你在吗」，用来判断能不能精确到坐标
        proxy.getScheduler().buildTask(this, () -> {
            // 清掉太久没回心跳的服（子服重启 / 桥接被卸载后不该一直当它还在）
            backend.expireStale();
            for (final RegisteredServer server : proxy.getAllServers()) {
                if (!server.getPlayersConnected().isEmpty()) {
                    backend.ping(server);
                }
            }
        }).delay(3L, TimeUnit.SECONDS)
                .repeat(Math.max(5L, config.pingIntervalSeconds()), TimeUnit.SECONDS)
                .schedule();
    }

    private long configMtime() {
        try {
            return Files.getLastModifiedTime(dataDirectory.resolve("config.toml")).toMillis();
        } catch (final Exception e) {
            return -1L;
        }
    }

    /** 改了配置不想敲命令就用这个（advanced.auto-reload = true）。按修改时间判断，没变不读。 */
    private void startAutoReload() {
        proxy.getScheduler().buildTask(this, () -> {
            if (!config.autoReload()) {
                return;
            }
            final long now = configMtime();
            if (now > 0 && now != lastModified) {
                logger.info("[vtpa] 发现 config.toml 变了，自动重载。");
                reload(null);
            }
        }).delay(3L, TimeUnit.SECONDS)
                .repeat(Math.max(1L, config.autoReloadIntervalSeconds()), TimeUnit.SECONDS)
                .schedule();
    }

    // ------------------------------------------------------------------
    // 重载
    // ------------------------------------------------------------------

    /** 重新读 config.toml。出错时保留旧配置 —— 宁可用旧的，也不让插件变成半成品。 */
    public void reload(final CommandSource feedback) {
        final Configuration.LoadResult result = Configuration.load(dataDirectory, logger);
        if (result.error() != null) {
            logger.warn("[vtpa] config.toml 没读出来，继续用旧配置：" + result.error());
            if (feedback != null) {
                send(feedback, config.message("reload-failed", "reason", result.error()));
            }
            return;
        }
        config = result.config();
        lastModified = configMtime();
        reportConfig();
        if (feedback != null) {
            send(feedback, config.message("reloaded"));
        }
    }

    /** 起服 / reload 之后把关键配置打一遍 —— 省得改了半天不知道到底生没生效。 */
    private void reportConfig() {
        final Configuration.ServerFilter filter = config.filter();
        logger.info("[vtpa] 请求时效 " + config.requestTimeoutSeconds() + " 秒，同意后倒计时 "
                + config.teleportDelaySeconds() + " 秒，发起冷却 " + config.cooldownSeconds()
                + " 秒，被拒绝后 " + config.denyCooldownSeconds()
                + " 秒内不能再发给同一人（0 = 不限）");
        logger.info("[vtpa] 子服名单：" + (filter.isWhitelist() ? "白名单" : "黑名单")
                + (filter.servers().isEmpty() ? "（空 = 全都参与）" : " " + String.join(", ", filter.servers())));
        logger.info("[vtpa] 跨服请求：" + (config.allowCrossServer() ? "开" : "关")
                + "，同服请求：" + (config.allowSameServer() ? "开" : "关")
                + (config.allowCrossServer()
                        ? "，跨服落脚权限 vtpa.to.<服名>：开（没授权的一律拒绝"
                                + (config.toBypassEnabled() ? "，vtpa.to.bypass 生效）" : "）")
                        : "，跨服落脚权限 vtpa.to.<服名>：用不上（跨服关着）"));
        logger.info("[vtpa] 互相请求自动同意：" + (config.reverseAutoAccept()
                ? "开（两条结果一样就直接进倒计时）" : "关（后发的那条会被挡回去）"));
        logger.info("[vtpa] 倒计时显示：" + (config.countdownEnabled()
                ? config.countdownMode() : "关")
                + "，移动取消：" + (!config.movementEnabled() ? "关"
                : "开（" + (config.movementTolerance() <= 0D
                        ? "动一下就取消" : "超过 " + config.movementTolerance() + " 格算动")
                + "，" + (config.movementBackend() ? "子服实时判定" : "代理轮询")
                + (config.movementIgnoreY() ? "，只算水平距离" : "，上下也算动") + "）"));
        if (config.soundsEnabled()) {
            final List<String> on = new ArrayList<>();
            for (final String key : new String[]{"request", "countdown", "countdown-tick",
                    "depart", "arrive", "cancel", "deny", "fail"}) {
                if (config.sound(key) != null) {
                    on.add(key);
                }
            }
            logger.info("[vtpa] 声音：" + (on.isEmpty() ? "全关" : String.join("/", on)));
        } else {
            logger.info("[vtpa] 声音：关");
        }
        logger.info("[vtpa] 排查日志：" + (config.debug() ? "开"
                : "关（要排查就把 advanced.debug 改成 true，重载即可）"));
        logger.info("[vtpa] 桥接通道 " + config.bridgeChannel()
                + "，子服没装桥接时：" + ("switch".equals(config.bridgeMissing())
                ? "跨服只切服（落出生点）" : "拒绝"));
        logger.info("[vtpa] 粒子特效：" + (config.particlesEnabled()
                ? "开（倒计时" + (config.particleCountdown() == null ? "关" : "开")
                + " / 出发" + (config.particleDepart() == null ? "关" : "开")
                + " / 落地" + (config.particleArrive() == null ? "关" : "开") + "）"
                : "关"));
        // 预设串写坏了当场说，别等有人传送才发现没效果
        for (final String problem : config.particleProblems()) {
            logger.warn("[vtpa] 粒子预设有问题（这一段不会播）：" + problem);
        }
    }

    // ------------------------------------------------------------------
    // /tpatoggle 的存盘
    // ------------------------------------------------------------------

    private Path toggleFile() {
        return dataDirectory.resolve("toggles.txt");
    }

    private void loadToggles() {
        if (!config.saveToggles() || !Files.exists(toggleFile())) {
            return;
        }
        try {
            for (final String line : Files.readAllLines(toggleFile(), StandardCharsets.UTF_8)) {
                final String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                service.setDisabled(UUID.fromString(trimmed), true);
            }
        } catch (final Exception e) {
            logger.warn("[vtpa] 读取 toggles.txt 失败（忽略）：" + e);
        }
    }

    /** 把「关掉接收」的名单写盘。存不下来也不影响功能，只打一条警告。 */
    public void saveToggles() {
        if (!config.saveToggles()) {
            return;
        }
        try {
            Files.createDirectories(dataDirectory);
            final StringBuilder out = new StringBuilder("# 关掉了「接收传送请求」的玩家 UUID，一行一个\n");
            for (final UUID uuid : service.disabledPlayers().keySet()) {
                out.append(uuid).append('\n');
            }
            Files.writeString(toggleFile(), out.toString(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            logger.warn("[vtpa] 写 toggles.txt 失败（忽略）：" + e);
        }
    }

    // ------------------------------------------------------------------
    // 给命令 / 服务用的小接口
    // ------------------------------------------------------------------

    /**
     * 发一条带 & 颜色码的文本。
     *
     * <p>空串 / 只剩颜色码的<b>不发</b> —— 配置里把某条提示设成 {@code ""} 就是「不想看到它」，
     * 发出去只会剩一个孤零零的 prefix。
     */
    public void send(final CommandSource source, final String legacy) {
        if (Colors.isBlank(legacy)) {
            return;
        }
        source.sendMessage(Colors.colorize(legacy));
    }

    /**
     * Tab 补全用：在线玩家名（按前缀过滤，小写比较）。
     *
     * @param exclude 要排除掉的人（一般就是敲命令的自己 ——
     *                补全出自己只会换来一句「不能向自己发送请求」）；null 表示不排除
     */
    public List<String> onlineNames(final String lowerPrefix, final UUID exclude) {
        final List<String> names = new ArrayList<>();
        final String prefix = lowerPrefix == null ? "" : lowerPrefix;
        for (final Player player : proxy.getAllPlayers()) {
            if (exclude != null && exclude.equals(player.getUniqueId())) {
                continue;
            }
            if (player.getUsername().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                names.add(player.getUsername());
                if (names.size() >= MAX_SUGGESTIONS) {
                    break;
                }
            }
        }
        return names;
    }

    public String version() {
        return proxy.getPluginManager().fromInstance(this)
                .map(container -> container.getDescription().getVersion().orElse("unknown"))
                .orElse("unknown");
    }

    public Configuration configuration() {
        return config;
    }

    public RequestStore store() {
        return store;
    }

    public Backend backend() {
        return backend;
    }

    public Teleporter teleporter() {
        return teleporter;
    }

    public TpaService service() {
        return service;
    }

    public ProxyServer proxy() {
        return proxy;
    }

    public Logger logger() {
        return logger;
    }

    Path dataDirectory() {
        return dataDirectory;
    }
}
