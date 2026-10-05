package cn.shijiu.vtpa.command;

import cn.shijiu.vtpa.Configuration;
import cn.shijiu.vtpa.Permissions;
import cn.shijiu.vtpa.RequestType;
import cn.shijiu.vtpa.VTpa;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /tpa <玩家>} —— 请求传送到对方那里。
 *
 * <p>⚠️ 主命令名小写（Velocity 底层 Brigadier 的 literal 节点大小写敏感，注册成大写
 *    会导致敲小写时报"命令不存在"并被转发给后端）。
 */
public final class TpaCommand implements SimpleCommand {

    private final VTpa plugin;

    public TpaCommand(final VTpa plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final Invocation invocation) {
        final CommandSource source = invocation.source();
        final Configuration config = plugin.configuration();
        if (!Permissions.require(plugin, source, Permissions.USE, config.allowByDefault())) {
            return;
        }
        if (!(source instanceof Player)) {
            plugin.send(source, config.message("players-only"));
            return;
        }
        final String[] args = invocation.arguments();
        if (args.length < 1) {
            plugin.send(source, config.message("usage-tpa",
                    "label", config.label(invocation.alias(), "tpa")));
            return;
        }
        final Player target = plugin.service().resolve(source, args[0]);
        if (target == null) {
            return;
        }
        plugin.service().sendRequest((Player) source, target, RequestType.TPA,
                config.label(invocation.alias(), "tpa"));
    }

    @Override
    public List<String> suggest(final Invocation invocation) {
        final String[] args = invocation.arguments();
        final String prefix = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        // 补出自己没意义（选了就是一句「不能向自己发送请求」），顺便把条数封顶
        return plugin.onlineNames(prefix, selfId(invocation.source()));
    }

    /** 补全时要排除的那个 UUID（敲命令的人自己）；不是玩家就不排除。 */
    static UUID selfId(final CommandSource source) {
        return source instanceof Player ? ((Player) source).getUniqueId() : null;
    }

    /** 权限自己在 execute 里判 —— 没权限的人看到的是"你没权限"，而不是"命令不存在"。 */
    @Override
    public boolean hasPermission(final Invocation invocation) {
        return true;
    }
}
