package cn.shijiu.vtpa;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * config.toml 的读取与校验。
 *
 * <p>铁律：**读失败的时候保留上一次的配置**，绝不让插件变成半成品。
 * 所以这里所有取值都带默认值，{@link #load} 出错时返回 error 而不是 null 之后裸奔。
 */
public final class Configuration {

    /** 加载结果：config 可能为旧的（出错时），error 不为空说明这次没能读成。 */
    public static final class LoadResult {
        private final Configuration config;
        private final String error;

        LoadResult(final Configuration config, final String error) {
            this.config = config;
            this.error = error;
        }

        public Configuration config() {
            return config;
        }

        public String error() {
            return error;
        }
    }

    /** 一个可点击的按钮（接受 / 拒绝）。 */
    public static final class Button {
        private final String text;
        private final String hover;
        private final String command;

        Button(final String text, final String hover, final String command) {
            this.text = text;
            this.hover = hover;
            this.command = command;
        }

        public String text() {
            return text;
        }

        public String hover() {
            return hover;
        }

        public String command() {
            return command;
        }
    }

    // ---------------- 服务器名单 / 权限 ----------------
    private final ServerFilter filter;
    private final boolean allowByDefault;
    private final boolean serverBypassEnabled;
    // ---------------- 基本规则 ----------------
    private final long requestTimeoutSeconds;
    private final long teleportDelaySeconds;
    private final long cooldownSeconds;
    private final int maxOutgoingRequests;
    private final boolean allowCrossServer;
    private final boolean lockDestination;
    private final boolean allowSameServer;
    private final boolean notifyOnExpire;
    private final boolean notifyOnDisconnect;
    // ---------------- 倒计时 ----------------
    private final boolean countdownEnabled;
    private final String countdownMode;
    private final String countdownTitle;
    private final String countdownSubtitle;
    private final String countdownActionbar;
    private final int fadeInTicks;
    private final int stayTicks;
    private final int fadeOutTicks;
    // ---------------- 粒子特效 ----------------
    private final boolean particlesEnabled;
    private final boolean particlesRequireBridge;
    private final FxSpec particleCountdown;
    private final FxSpec particleDepart;
    private final FxSpec particleArrive;
    private final FxSpec particleCancel;
    private final int departTicks;
    private final int arriveTicks;
    private final int cancelTicks;
    private final int maxPerPlayer;
    private final List<String> particleProblems;
    // ---------------- 移动检测 ----------------
    private final boolean movementEnabled;
    private final double movementTolerance;
    private final long pollIntervalMillis;
    private final boolean cancelOnWorldChange;
    // ---------------- 请求文本 / 按钮 ----------------
    private final String requestTpa;
    private final String requestHere;
    private final Button acceptButton;
    private final Button denyButton;
    private final Button cancelButton;
    // ---------------- 移动检测 ----------------
    private final boolean movementBackend;
    private final boolean movementIgnoreY;
    private final boolean movementPollAlso;
    // ---------------- 声音 ----------------
    private final boolean soundsEnabled;
    private final Map<String, String> sounds;
    // ---------------- 提示语 ----------------
    private final String prefix;
    private final Map<String, String> messages;
    // ---------------- 桥接 ----------------
    private final String bridgeChannel;
    private final String bridgeMissing;
    private final long bridgeTimeoutMillis;
    private final long pingIntervalSeconds;
    // ---------------- 命令 ----------------
    private final List<String> rootAliases;
    private final Map<String, List<String>> subAliases;
    private final Map<String, String> shortcuts;
    // ---------------- 杂项 ----------------
    private final boolean autoReload;
    private final int autoReloadIntervalSeconds;
    private final boolean logToConsole;
    private final boolean saveToggles;

    private Configuration(final Map<String, Object> m) {
        this.filter = new ServerFilter(
                TomlLite.string(m, "servers.mode", "blacklist"),
                TomlLite.list(m, "servers.list", Collections.emptyList()));

        this.allowByDefault = TomlLite.bool(m, "permissions.allow-by-default", true);
        // 默认 false：子服名单对所有人一视同仁，OP / 有 vtpa.*、* 通配符的也不例外
        this.serverBypassEnabled = TomlLite.bool(m, "permissions.server-bypass", false);

        this.requestTimeoutSeconds = Math.max(1L, TomlLite.integer(m, "general.request-timeout-seconds", 180L));
        this.teleportDelaySeconds = Math.max(0L, TomlLite.integer(m, "general.teleport-delay-seconds", 3L));
        this.cooldownSeconds = Math.max(0L, TomlLite.integer(m, "general.cooldown-seconds", 5L));
        this.maxOutgoingRequests = (int) Math.max(1L, TomlLite.integer(m, "general.max-outgoing-requests", 3L));
        this.allowCrossServer = TomlLite.bool(m, "general.allow-cross-server", true);
        this.lockDestination = TomlLite.bool(m, "general.lock-destination", true);
        this.allowSameServer = TomlLite.bool(m, "general.allow-same-server", true);
        this.notifyOnExpire = TomlLite.bool(m, "general.notify-on-expire", true);
        this.notifyOnDisconnect = TomlLite.bool(m, "general.notify-on-disconnect", true);

        this.countdownEnabled = TomlLite.bool(m, "countdown.enabled", true);
        this.countdownMode = normalizeMode(TomlLite.string(m, "countdown.mode", "title"));
        this.countdownTitle = TomlLite.string(m, "countdown.title", "&b#seconds#");
        this.countdownSubtitle = TomlLite.string(m, "countdown.subtitle", "&7正在传送，请不要移动");
        this.countdownActionbar = TomlLite.string(m, "countdown.actionbar", "&b#seconds# &7秒后传送…");
        this.fadeInTicks = (int) Math.max(0L, TomlLite.integer(m, "countdown.fade-in-ticks", 2L));
        this.stayTicks = (int) Math.max(1L, TomlLite.integer(m, "countdown.stay-ticks", 20L));
        this.fadeOutTicks = (int) Math.max(0L, TomlLite.integer(m, "countdown.fade-out-ticks", 4L));

        // 粒子特效：预设串当场解析一遍，写错了立刻能在日志里看到（而不是等到有人传送才发现没效果）
        this.particlesEnabled = TomlLite.bool(m, "particles.enabled", true);
        this.particlesRequireBridge = TomlLite.bool(m, "particles.require-bridge", true);
        this.particleCountdown = parseFx(m, "particles.countdown",
                "circle;effect:flying_glyph;dur:5;pitchc:15;part:10;offset:0,1.7,0;radius:0.5;yawc:12;color:rs;pitch:90");
        this.particleDepart = parseFx(m, "particles.depart",
                "circle;c:200,50,210;twist;part:5;r:0.5;pitch:90;move:0,0.33,0;offset:0,-0.2,0");
        this.particleArrive = parseFx(m, "particles.arrive",
                "circle;c:150,50,10;part:5;r:0.5;pitch:90;move:0,-0.33,0;offset:0,2.2,0");
        this.particleCancel = parseFx(m, "particles.cancel", "");
        this.departTicks = (int) Math.max(1L, TomlLite.integer(m, "particles.depart-ticks", 15L));
        this.arriveTicks = (int) Math.max(1L, TomlLite.integer(m, "particles.arrive-ticks", 15L));
        this.cancelTicks = (int) Math.max(1L, TomlLite.integer(m, "particles.cancel-ticks", 10L));
        this.maxPerPlayer = (int) Math.max(1L, TomlLite.integer(m, "particles.max-per-player", 4L));
        final List<String> fxProblems = new ArrayList<>();
        collectProblems("particles.countdown", particleCountdown, fxProblems);
        collectProblems("particles.depart", particleDepart, fxProblems);
        collectProblems("particles.arrive", particleArrive, fxProblems);
        collectProblems("particles.cancel", particleCancel, fxProblems);
        this.particleProblems = Collections.unmodifiableList(fxProblems);

        this.movementEnabled = TomlLite.bool(m, "movement.enabled", true);
        // 默认 0：动一下就取消（跟 CMI 的 Tpa.Move: false 一个体感）
        this.movementTolerance = Math.max(0D, TomlLite.decimal(m, "movement.tolerance", 0D));
        this.pollIntervalMillis = Math.max(50L, TomlLite.integer(m, "movement.poll-interval-millis", 250L));
        this.cancelOnWorldChange = TomlLite.bool(m, "movement.cancel-on-world-change", true);

        this.requestTpa = TomlLite.string(m, "request.tpa", "&e%player% &7请求传送到你这里! #accept# #deny#");
        this.requestHere = TomlLite.string(m, "request.here", "&e%player% &7请求你传送到他那里! #accept# #deny#");
        this.acceptButton = new Button(
                TomlLite.string(m, "buttons.accept-text", "&a&l[接受]"),
                TomlLite.string(m, "buttons.accept-hover", "&a点击接受 %player% 的请求"),
                TomlLite.string(m, "buttons.accept-command", "/tpaccept %player%"));
        this.denyButton = new Button(
                TomlLite.string(m, "buttons.deny-text", "&c&l[拒绝]"),
                TomlLite.string(m, "buttons.deny-hover", "&c点击拒绝 %player% 的请求"),
                TomlLite.string(m, "buttons.deny-command", "/tpadeny %player%"));
        // 发给发起者的「已发送请求」里那个撤回按钮 —— 点了等于自己敲 /tpacancel
        this.cancelButton = new Button(
                TomlLite.string(m, "buttons.cancel-text", "&e&l[撤回]"),
                TomlLite.string(m, "buttons.cancel-hover", "&e点一下撤回发给 %player% 的请求"),
                TomlLite.string(m, "buttons.cancel-command", "/tpacancel %player%"));

        this.movementBackend = TomlLite.bool(m, "movement.backend-detection", true);
        this.movementPollAlso = TomlLite.bool(m, "movement.backend-and-poll", true);
        // 默认 false：竖直方向动了也算（跳一下就取消），跟 CMI 一致
        this.movementIgnoreY = TomlLite.bool(m, "movement.ignore-y", false);

        this.soundsEnabled = TomlLite.bool(m, "sounds.enabled", true);
        final Map<String, String> sounds = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : m.entrySet()) {
            if (e.getKey().startsWith("sounds.") && !e.getKey().equals("sounds.enabled")) {
                sounds.put(e.getKey().substring("sounds.".length()), String.valueOf(e.getValue()));
            }
        }
        this.sounds = Collections.unmodifiableMap(sounds);

        this.prefix = TomlLite.string(m, "messages.prefix", "&8[&b传送&8]&r");
        final Map<String, String> messages = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : m.entrySet()) {
            if (e.getKey().startsWith("messages.") && !e.getKey().equals("messages.prefix")) {
                messages.put(e.getKey().substring("messages.".length()), String.valueOf(e.getValue()));
            }
        }
        this.messages = Collections.unmodifiableMap(messages);

        this.bridgeChannel = TomlLite.string(m, "bridge.channel", "vtpa:main").trim();
        this.bridgeMissing = normalizeMissing(TomlLite.string(m, "bridge.missing", "switch"));
        this.bridgeTimeoutMillis = Math.max(100L, TomlLite.integer(m, "bridge.timeout-millis", 1200L));
        this.pingIntervalSeconds = Math.max(5L, TomlLite.integer(m, "bridge.ping-interval-seconds", 30L));

        // [commands] 段：root 是主命令的别名，其余每个键都是「子命令主名 = [别名...]」
        final Map<String, List<String>> commands = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : m.entrySet()) {
            if (e.getKey().startsWith("commands.") && e.getValue() instanceof List) {
                @SuppressWarnings("unchecked")
                final List<String> list = new ArrayList<>((List<String>) e.getValue());
                commands.put(e.getKey().substring("commands.".length()), list);
            }
        }
        final List<String> root = commands.remove("root");
        this.rootAliases = root == null ? Collections.singletonList("vt") : root;
        this.subAliases = Collections.unmodifiableMap(commands);

        // [shortcuts] 段：把某个子命令直接注册成顶层命令（"命令名" = "子命令主名"）
        final Map<String, String> sc = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : m.entrySet()) {
            if (e.getKey().startsWith("shortcuts.") && e.getValue() instanceof String) {
                final String target = ((String) e.getValue()).trim().toLowerCase(Locale.ROOT);
                if (!target.isEmpty()) {
                    sc.put(e.getKey().substring("shortcuts.".length()).toLowerCase(Locale.ROOT), target);
                }
            }
        }
        this.shortcuts = Collections.unmodifiableMap(sc);

        this.autoReload = TomlLite.bool(m, "advanced.auto-reload", false);
        this.autoReloadIntervalSeconds =
                (int) Math.max(1L, TomlLite.integer(m, "advanced.auto-reload-interval-seconds", 3L));
        this.logToConsole = TomlLite.bool(m, "advanced.log-to-console", true);
        this.saveToggles = TomlLite.bool(m, "advanced.save-toggles", true);
    }

    /** 解析一条粒子预设；空串 / 写坏都按「不播」处理（坏了的问题会记进 {@link #particleProblems()}）。 */
    private static FxSpec parseFx(final Map<String, Object> m, final String key, final String fallback) {
        final FxSpec parsed = FxSpec.parse(TomlLite.string(m, key, fallback));
        return parsed;
    }

    private static void collectProblems(final String key, final FxSpec spec, final List<String> sink) {
        if (spec == null) {
            return;
        }
        for (final String problem : spec.problems()) {
            sink.add(key + "：" + problem);
        }
    }

    private static String normalizeMode(final String raw) {
        final String mode = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (mode.equals("title") || mode.equals("actionbar") || mode.equals("both")) {
            return mode;
        }
        return "none";
    }

    /** {@code bridge.missing} 只认 deny / switch，写歪了一律按 deny（宁可不送，也不送到莫名其妙的地方）。 */
    private static String normalizeMissing(final String raw) {
        final String mode = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return mode.equals("switch") ? "switch" : "deny";
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    public static LoadResult load(final Path dataDirectory, final Logger logger) {
        final Path file = dataDirectory.resolve("config.toml");
        try {
            Files.createDirectories(dataDirectory);
            if (!Files.exists(file)) {
                copyDefault(logger, file);
            }
            final Map<String, Object> parsed = TomlLite.parse(file);
            return new LoadResult(new Configuration(parsed), null);
        } catch (final Exception e) {
            logger.warn("[vtpa] 读取 config.toml 失败：" + e);
            return new LoadResult(null, String.valueOf(e));
        }
    }

    /** 用 jar 里自带的默认 config.toml 造一份配置 —— 连内置模板都读不出来时的兜底。 */
    public static Configuration defaults() {
        try (InputStream in = Configuration.class.getClassLoader().getResourceAsStream("config.toml")) {
            if (in == null) {
                throw new IOException("jar 里找不到默认 config.toml");
            }
            return new Configuration(TomlLite.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (final Exception e) {
            throw new IllegalStateException("连默认配置都读不出来：" + e, e);
        }
    }

    private static void copyDefault(final Logger logger, final Path file) throws IOException {
        try (InputStream in = Configuration.class.getClassLoader().getResourceAsStream("config.toml")) {
            if (in == null) {
                throw new IOException("jar 里找不到默认 config.toml");
            }
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
        }
        logger.info("[vtpa] 已生成默认配置文件：" + file.toAbsolutePath());
    }

    // ------------------------------------------------------------------
    // 取值
    // ------------------------------------------------------------------

    public ServerFilter filter() {
        return filter;
    }

    public boolean allowByDefault() {
        return allowByDefault;
    }

    /**
     * 特权节点 {@code vtpa.server.bypass}（不受子服黑白名单限制）要不要生效。
     *
     * <p>默认 false = 名单对所有人一视同仁。因为 LuckPerms 给管理组发的
     * {@code vtpa.*} / {@code *} 会把这个节点判定为「有」，默认生效的话
     * 名单对管理员就形同虚设 —— 而名单的用途恰恰是「隔离某些服」。
     * 真要特权就改成 true，然后<b>单独</b>发那个节点，别用通配符。
     */
    public boolean serverBypassEnabled() {
        return serverBypassEnabled;
    }

    public long requestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public long teleportDelaySeconds() {
        return teleportDelaySeconds;
    }

    public long cooldownSeconds() {
        return cooldownSeconds;
    }

    public int maxOutgoingRequests() {
        return maxOutgoingRequests;
    }

    public boolean allowCrossServer() {
        return allowCrossServer;
    }

    public boolean allowSameServer() {
        return allowSameServer;
    }

    /**
     * 落点锁不锁在「对方按下接受」那一刻。
     *
     * <p>锁着（默认）的含义：同意之后对方还能自由走动，但你会传到他<b>同意时</b>站的
     * 那个点 —— 不锁的话落点跟着他漂，三秒里他能走出好几十格。
     */
    public boolean lockDestination() {
        return lockDestination;
    }

    public boolean notifyOnExpire() {
        return notifyOnExpire;
    }

    public boolean notifyOnDisconnect() {
        return notifyOnDisconnect;
    }

    public boolean countdownEnabled() {
        return countdownEnabled;
    }

    public String countdownMode() {
        return countdownMode;
    }

    public String countdownTitle() {
        return countdownTitle;
    }

    public String countdownSubtitle() {
        return countdownSubtitle;
    }

    public String countdownActionbar() {
        return countdownActionbar;
    }

    public int fadeInTicks() {
        return fadeInTicks;
    }

    public int stayTicks() {
        return stayTicks;
    }

    public int fadeOutTicks() {
        return fadeOutTicks;
    }

    /** 粒子特效总开关。关掉就一个粒子都不撒（传送照常）。 */
    public boolean particlesEnabled() {
        return particlesEnabled;
    }

    /** 只给确认装了桥接的子服发特效吗。 */
    public boolean particlesRequireBridge() {
        return particlesRequireBridge;
    }

    /** 倒计时期间跟着被传送者转的特效；null = 不播。 */
    public FxSpec particleCountdown() {
        return particleCountdown;
    }

    /** 传送瞬间在出发地撒的特效；null = 不播。 */
    public FxSpec particleDepart() {
        return particleDepart;
    }

    /** 落地瞬间在落点撒的特效；null = 不播。 */
    public FxSpec particleArrive() {
        return particleArrive;
    }

    /** 倒计时被打断时撒的特效；null = 不播。 */
    public FxSpec particleCancel() {
        return particleCancel;
    }

    public int departTicks() {
        return departTicks;
    }

    public int arriveTicks() {
        return arriveTicks;
    }

    public int cancelTicks() {
        return cancelTicks;
    }

    public int maxPerPlayer() {
        return maxPerPlayer;
    }

    /** 解析粒子预设时攒下的问题（写坏了之类）。非空就该打日志提醒服主。 */
    public List<String> particleProblems() {
        return particleProblems;
    }

    public boolean movementEnabled() {
        return movementEnabled;
    }

    public double movementTolerance() {
        return movementTolerance;
    }

    public long pollIntervalMillis() {
        return pollIntervalMillis;
    }

    public boolean cancelOnWorldChange() {
        return cancelOnWorldChange;
    }

    /** {@code /tpa} 发给对方的文本（含 {@code #accept#} / {@code #deny#} 占位符）。 */
    public String requestTpa() {
        return requestTpa;
    }

    /** {@code /tpahere}、{@code /tpaall} 发给对方的文本。 */
    public String requestHere() {
        return requestHere;
    }

    public Button acceptButton() {
        return acceptButton;
    }

    public Button denyButton() {
        return denyButton;
    }

    public Button cancelButton() {
        return cancelButton;
    }

    /**
     * 移动检测交给子服做（子服监听 PlayerMoveEvent，实时、准，跟 CMI 一个路子）。
     *
     * <p>只有子服桥接版本太老（不支持这条协议）时才会退回代理端轮询。
     */
    public boolean movementBackend() {
        return movementBackend;
    }

    /** 只算水平距离：原地跳一下 / 被活塞顶一下不算「移动」。 */
    public boolean movementIgnoreY() {
        return movementIgnoreY;
    }

    /**
     * 子服已经在盯了，代理还要不要<b>同时</b>自己轮询一遍（默认开）。
     *
     * <p>子服那条路万一没走通，移动取消会<b>静默失效</b>（玩家动了照样传走）。
     * 多问几次坐标很便宜，失效很难查，所以默认双保险。
     */
    public boolean movementPollAlso() {
        return movementPollAlso;
    }

    public boolean soundsEnabled() {
        return soundsEnabled;
    }

    /**
     * 取一个音效串（CMI 那种 {@code 名字:音量:音调}）。没配 / 配成空串返回 null = 不播。
     *
     * <p>可用的 key：{@code request}（对方收到请求时）、{@code countdown}（倒计时开始）、
     * {@code countdown-tick}（倒计时每过一秒）、{@code depart}（出发）、
     * {@code arrive}（落地）、{@code cancel}（被打断）、{@code deny}（被拒绝）、
     * {@code fail}（传送失败）。
     */
    public String sound(final String key) {
        if (!soundsEnabled) {
            return null;
        }
        final String value = sounds.get(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 取一条提示语（已经拼好 prefix）。缺了就返回兜底文本，不返回 null。
     *
     * @param args 依次替换 {@code #name#} 形式的占位符（"占位符名", "值", …）
     */
    public String message(final String key, final Object... args) {
        String text = messages.get(key);
        if (text == null) {
            text = "&c(缺少配置项 messages." + key + ")";
        }
        // 先替换调用方传进来的 —— 允许用 ("label", "/tpa") 覆盖掉默认的 #label#，
        // 这样从顶层快捷命令进来时，用法提示显示的就是玩家真正敲的那个命令
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (args[i + 1] == null) {
                continue;
            }
            text = text.replace("#" + args[i] + "#", String.valueOf(args[i + 1]));
        }
        // #label# 最后兜底填成实际命令名（/vtpa 之类）—— 改了别名提示语也跟着变
        if (text.contains("#label#")) {
            text = text.replace("#label#", label());
        }
        // 配置里这条设成空（或者只剩颜色码）→ 整条都不发，连 prefix 也不带。
        // 不然会发出一条孤零零的「[传送] 」，玩家看着莫名其妙。
        return Colors.isBlank(text) ? "" : prefix + text;
    }

    /** 提示语里显示成什么命令名 —— 取配置的第一个主命令别名，没配就用 /vtpa。 */
    public String label() {
        final String first = rootAliases.isEmpty() ? null : rootAliases.get(0);
        return "/" + (first == null || first.isEmpty() ? "vtpa" : first);
    }

    /**
     * 按玩家实际敲的那个命令来显示用法。
     *
     * @param alias 玩家敲的命令名（{@code Invocation#alias()}，不含斜杠）
     * @param sub   子命令主名
     * @return 走顶层快捷命令时是 {@code /tpa} 这种，走主命令时是 {@code /vtpa tpa} 这种
     */
    public String label(final String alias, final String sub) {
        if (alias == null || alias.isEmpty()) {
            return label() + " " + sub;
        }
        if (shortcuts.containsKey(alias.toLowerCase(Locale.ROOT))) {
            return "/" + alias;
        }
        return "/" + alias + " " + sub;
    }

    /** 不带 prefix 的原始提示语 —— 少数场景（比如要拼换行）用。 */
    public String rawMessage(final String key) {
        final String text = messages.get(key);
        return text == null ? key : text;
    }

    public String bridgeChannel() {
        return bridgeChannel;
    }

    /** {@code "deny"} 或 {@code "switch"}：子服没装桥接时怎么办。 */
    public String bridgeMissing() {
        return bridgeMissing;
    }

    public long bridgeTimeoutMillis() {
        return bridgeTimeoutMillis;
    }

    public long pingIntervalSeconds() {
        return pingIntervalSeconds;
    }

    /** 主命令 /vtpa 的别名（默认 ["vt"]）；写空 list 就只用 /vtpa。 */
    public List<String> rootAliases() {
        return rootAliases;
    }

    /** 子命令别名表：键是子命令主名（tpa / tpaccept / …），值是在 /vtpa 后面能用的别名。 */
    public Map<String, List<String>> subAliases() {
        return subAliases;
    }

    public Map<String, String> shortcuts() {
        return shortcuts;
    }

    public boolean autoReload() {
        return autoReload;
    }

    public int autoReloadIntervalSeconds() {
        return autoReloadIntervalSeconds;
    }

    public boolean logToConsole() {
        return logToConsole;
    }

    public boolean saveToggles() {
        return saveToggles;
    }

    // ------------------------------------------------------------------
    // 服务器名单
    // ------------------------------------------------------------------

    /**
     * 子服黑白名单。
     *
     * <p>失效模式偏向「放行」：模式名写错会退回黑名单（= 全通），
     * 服名取不到（比如人还在登录中）也按「参与」处理 —— 宁可多发，不可吞掉。
     */
    public static final class ServerFilter {
        private final boolean whitelist;
        private final List<String> servers;

        ServerFilter(final String mode, final List<String> servers) {
            final String normalized = mode.trim().toLowerCase(Locale.ROOT);
            this.whitelist = normalized.equals("whitelist");
            this.servers = TomlLite.lowercase(new ArrayList<>(servers));
        }

        /**
         * 这个服能不能用传送请求。
         *
         * @param server Velocity 里的服务器名；null（比如在控制台、或人不在任何服上）按放行处理
         */
        public boolean allows(final String server) {
            if (server == null) {
                return true;
            }
            final boolean listed = servers.contains(server.trim().toLowerCase(Locale.ROOT));
            return whitelist == listed;
        }

        public boolean isWhitelist() {
            return whitelist;
        }

        public List<String> servers() {
            return servers;
        }
    }
}
