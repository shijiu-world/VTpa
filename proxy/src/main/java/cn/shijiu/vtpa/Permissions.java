package cn.shijiu.vtpa;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.ConsoleCommandSource;

import java.util.Locale;

/**
 * 权限节点清单 + 判定。
 *
 * <p>分两类：
 * <ul>
 *   <li><b>基础节点</b>（use / here / accept / deny / cancel / toggle）：受配置
 *       {@code permissions.allow-by-default} 控制 —— 默认 true，也就是服上没配过权限的人
 *       也能正常用传送请求（Velocity 对没配的权限是「未定义」，不是「允许」）。</li>
 *   <li><b>特权节点</b>（{@link #ALL} /tpaall、reload、各种 bypass）：必须显式给，
 *       allow-by-default 对它们无效。默认只有控制台能用。</li>
 * </ul>
 *
 * <pre>
 *   /lp group default permission set vtpa.use true
 *   /lp group vip   permission set vtpa.all true      # 能 /tpaall
 *   /lp group admin permission set vtpa.* true        # 通配符，全部放通
 * </pre>
 */
public final class Permissions {

    /** 用 /tpa、/tpahere、/tpaccept、/tpadeny、/tpacancel 的基础权限。 */
    public static final String USE = "vtpa.use";
    /** /tpahere（把别人拉过来）。 */
    public static final String HERE = "vtpa.here";
    /** 接受请求。 */
    public static final String ACCEPT = "vtpa.accept";
    /** 拒绝请求。 */
    public static final String DENY = "vtpa.deny";
    /** 取消自己发出的请求。 */
    public static final String CANCEL = "vtpa.cancel";
    /** /tpatoggle 自己开关接收。 */
    public static final String TOGGLE = "vtpa.toggle";
    /** 能发给关掉接收的人。 */
    public static final String TOGGLE_BYPASS = "vtpa.toggle.bypass";
    /** 🔴 /tpaall —— 广播给全服，默认关，必须显式给。 */
    public static final String ALL = "vtpa.all";
    /** 🔴 /tpaworld —— 请求「跟我同一个世界」的人传送过来，默认关，必须显式给。 */
    public static final String WORLD = "vtpa.world";
    /** 🔴 /tpaserver —— 请求「跟我同一个子服」的人传送过来，默认关，必须显式给。 */
    public static final String SERVER = "vtpa.server";
    /** 不受发起冷却限制。 */
    public static final String COOLDOWN_BYPASS = "vtpa.cooldown.bypass";
    /** 不受子服黑白名单限制。 */
    public static final String SERVER_BYPASS = "vtpa.server.bypass";
    /**
     * 🔴 跨服传送的「落脚许可」前缀 —— 完整节点是 {@code vtpa.to.<子服名>}。
     *
     * <p><b>默认一律未定义</b>（Velocity 那边是 Tristate.UNDEFINED），也就是服主不给
     * 就过不去。谁能进哪个子服，全靠这一个系列的节点串起来控制。
     */
    public static final String TRAVEL = "vtpa.to.";
    /**
     * 🔴 不受 {@code vtpa.to.<子服名>} 限制 —— 要在 [permissions] 里写 to-bypass = true 才生效。
     *
     * <p>默认焊死：给管理组发 {@code vtpa.*} 或 {@code *} 会让这个节点自动成立，
     * 所以默认不起效（跟 {@link #SERVER_BYPASS} 一个道理），真要特权就单独显式给。
     */
    public static final String TRAVEL_BYPASS = "vtpa.to.bypass";
    /** 不受「待处理请求数量上限」限制。 */
    public static final String LIMIT_BYPASS = "vtpa.limit.bypass";
    /** /vtpa reload。 */
    public static final String RELOAD = "vtpa.reload";

    private Permissions() {
    }

    /**
     * 判断有没有某个权限。
     *
     * @param allowByDefault 权限「没配过」的时候算不算有。特权节点一律传 false。
     */
    public static boolean has(final CommandSource source, final String node, final boolean allowByDefault) {
        if (source instanceof ConsoleCommandSource) {
            return true;
        }
        if (source == null) {
            // 没有命令来源（不该发生）按没有权限处理 —— 别把特权默认放出去
            return false;
        }
        final Tristate value = source.getPermissionValue(node);
        if (value == Tristate.TRUE) {
            return true;
        }
        if (value == Tristate.FALSE) {
            return false;
        }
        return allowByDefault;
    }

    /**
     * 有权限才往下走；没权限就给对方发一句「你没权限」并返回 false。
     *
     * @param allowByDefault 同上，基础节点传配置值、特权节点传 false
     */
    public static boolean require(final VTpa plugin, final CommandSource source,
                                  final String node, final boolean allowByDefault) {
        if (has(source, node, allowByDefault)) {
            return true;
        }
        plugin.send(source, plugin.configuration().message("no-permission", "permission", node));
        return false;
    }

    // ------------------------------------------------------------------
    // vtpa.to.<子服名> ——「能不能传送到这个子服」
    // ------------------------------------------------------------------

    /**
     * 「传送到某个子服」的权限节点。
     *
     * <p>服名统一转小写 —— Velocity 的服名是大小写敏感注册的（{@code survival} 和
     * {@code Survival} 能是两个不同的服），而 LuckPerms 判权限时把节点当小写处理，
     * 所以这里不转小写的话，大写服名会拼出一个永远配不上的节点。
     *
     * @return {@code vtpa.to.<小写服名>}；服名取不到返回 null（= 没法判定，按放行）
     */
    public static String travelNode(final String server) {
        if (server == null || server.isBlank()) {
            return null;
        }
        return TRAVEL + server.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 这个人能不能落脚（被传送）到 {@code server} 这个子服。
     *
     * <p>🔴 <b>没配过就是没有</b>：节点 undefined 一律按拒绝。这是整组 travel 权限的
     * 设计前提 —— 宁可拦住，也不放人过去。服主想开放就得逐个服显式 grant。
     *
     * @param source        要被移动到那个服的人。用发起者 / 目标那个 {@code Player}，
     *                      别用当前是谁在敲命令（{@code /tpahere} 时动的是对方）
     * @param server        落点所在的子服；取不到（null / 空）时放行，由别的闸拦
     * @param bypassEnabled [permissions] to-bypass 的值。关着的时候
     *                      {@link #TRAVEL_BYPASS} 形同不存在
     */
    public static boolean mayTravelTo(final CommandSource source, final String server,
                                      final boolean bypassEnabled) {
        final String node = travelNode(server);
        if (node == null) {
            return true;
        }
        if (bypassEnabled && has(source, TRAVEL_BYPASS, false)) {
            return true;
        }
        return has(source, node, false);
    }
}
