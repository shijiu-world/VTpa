package cn.shijiu.vtpa.command;

import cn.shijiu.vtpa.Configuration;
import cn.shijiu.vtpa.Permissions;
import cn.shijiu.vtpa.VTpa;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.List;

/**
 * {@code /tpall} —— 给全服在线玩家发一条「传送到我这儿」的请求。
 *
 * <p>旧名 {@code /tpaall} 保留成别名（{@code [commands]} 与 {@code [shortcuts]} 里各留了一行），
 * 老玩家照旧敲得动。
 *
 * <p>🔴 这是特权命令，要 {@code vtpa.all} 权限（{@code allow-by-default} 对它无效，
 * 必须去 LuckPerms 显式给）。不合适的人一律静默跳过，最后只回一条汇总。
 */
public final class TpaAllCommand implements SimpleCommand {

    private final VTpa plugin;

    public TpaAllCommand(final VTpa plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final Invocation invocation) {
        final CommandSource source = invocation.source();
        final Configuration config = plugin.configuration();
        if (!Permissions.require(plugin, source, Permissions.ALL, false)) {
            return;
        }
        if (!(source instanceof Player)) {
            plugin.send(source, config.message("players-only"));
            return;
        }
        if (invocation.arguments().length > 0) {
            plugin.send(source, config.message("usage-tpall",
                    "label", config.label(invocation.alias(), "tpall")));
            return;
        }
        plugin.service().sendToAll((Player) source);
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
