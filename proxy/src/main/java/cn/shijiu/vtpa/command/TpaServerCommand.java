package cn.shijiu.vtpa.command;

import cn.shijiu.vtpa.Configuration;
import cn.shijiu.vtpa.Permissions;
import cn.shijiu.vtpa.VTpa;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.List;

/**
 * {@code /tpaserver} —— 给「跟我同一个子服」的所有在线玩家发一条「传送到我这儿」。
 *
 * <p>相当于 {@code /tpaall} 缩到当前子服：跨服的服不打扰。
 * 🔴 特权命令，要 {@code vtpa.server}（必须显式给，{@code allow-by-default} 对它无效）。
 */
public final class TpaServerCommand implements SimpleCommand {

    private final VTpa plugin;

    public TpaServerCommand(final VTpa plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final Invocation invocation) {
        final CommandSource source = invocation.source();
        final Configuration config = plugin.configuration();
        if (!Permissions.require(plugin, source, Permissions.SERVER, false)) {
            return;
        }
        if (!(source instanceof Player)) {
            plugin.send(source, config.message("players-only"));
            return;
        }
        if (invocation.arguments().length > 0) {
            plugin.send(source, config.message("usage-tpaserver",
                    "label", config.label(invocation.alias(), "tpaserver")));
            return;
        }
        plugin.service().sendToServer((Player) source);
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
