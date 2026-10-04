package cn.shijiu.vtpa.command;

import cn.shijiu.vtpa.Configuration;
import cn.shijiu.vtpa.Permissions;
import cn.shijiu.vtpa.VTpa;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.List;

/**
 * {@code /tpaworld} —— 给「跟我同一个世界」的所有在线玩家发一条「传送到我这儿」。
 *
 * <p>比 {@code /tpaserver} 还窄一层：同一个子服、但不在同一个世界（主世界 / 地狱 / 末地 /
 * 资源世界）的人不会被打扰。
 *
 * <p>⚠️ 世界是<b>子服</b的概念，代理自己不知道谁在哪个世界，所以要先问一圈坐标
 * （拿回来的 {@code Loc} 里带着世界名）再筛 —— 这一步是异步的，命令会晚几十毫秒才回话。
 *
 * <p>🔴 特权命令，要 {@code vtpa.world}（必须显式给）。
 */
public final class TpaWorldCommand implements SimpleCommand {

    private final VTpa plugin;

    public TpaWorldCommand(final VTpa plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final Invocation invocation) {
        final CommandSource source = invocation.source();
        final Configuration config = plugin.configuration();
        if (!Permissions.require(plugin, source, Permissions.WORLD, false)) {
            return;
        }
        if (!(source instanceof Player)) {
            plugin.send(source, config.message("players-only"));
            return;
        }
        if (invocation.arguments().length > 0) {
            plugin.send(source, config.message("usage-tpaworld",
                    "label", config.label(invocation.alias(), "tpaworld")));
            return;
        }
        plugin.service().sendToWorld((Player) source);
    }

    @Override
    public List<String> suggest(final Invocation invocation) {
        return List.of();
    }

    @Override
    public boolean hasPermission(final Invocation invocation) {
        return true;
    }
}
