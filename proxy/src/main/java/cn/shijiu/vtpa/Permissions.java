package cn.shijiu.vtpa;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.ConsoleCommandSource;

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
    /** 不受发起冷却限制。 */
    public static final String COOLDOWN_BYPASS = "vtpa.cooldown.bypass";
    /** 不受子服黑白名单限制。 */
    public static final String SERVER_BYPASS = "vtpa.server.bypass";
    /** 不受「待处理请求数量上限」限制。 */
    public static final String LIMIT_BYPASS = "vtpa.limit.bypass";
    /** 倒计时期间动一下也不取消传送（比如给管理用）。 */
    public static final String MOVE_BYPASS = "vtpa.move.bypass";
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
}
