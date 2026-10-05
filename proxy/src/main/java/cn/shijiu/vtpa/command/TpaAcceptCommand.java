package cn.shijiu.vtpa.command;

import cn.shijiu.vtpa.Configuration;
import cn.shijiu.vtpa.Permissions;
import cn.shijiu.vtpa.TpaRequest;
import cn.shijiu.vtpa.VTpa;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /tpaccept [玩家]} —— 接受一条发给我的请求。
 *
 * <p>不写玩家名：只有一条待处理请求就直接处理；有多条会列出名字让你选一个。
 * 聊天里那个「[接受]」按钮点的是 {@code /tpaccept <名字>}，所以永远对得上。
 */
public final class TpaAcceptCommand implements SimpleCommand {

    private final VTpa plugin;

    public TpaAcceptCommand(final VTpa plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final Invocation invocation) {
        final CommandSource source = invocation.source();
        final Configuration config = plugin.configuration();
        if (!Permissions.require(plugin, source, Permissions.ACCEPT, config.allowByDefault())) {
            return;
        }
        if (!(source instanceof Player)) {
            plugin.send(source, config.message("players-only"));
            return;
        }
        final String[] args = invocation.arguments();
        if (args.length > 1) {
            plugin.send(source, config.message("usage-tpaccept",
                    "label", config.label(invocation.alias(), "tpaccept")));
            return;
        }
        plugin.service().accept((Player) source, args.length == 1 ? args[0] : null);
    }

    @Override
    public List<String> suggest(final Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            return List.of();
        }
        final Player self = (Player) invocation.source();
        final String[] args = invocation.arguments();
        final String prefix = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        final List<String> out = new ArrayList<>();
        for (final TpaRequest request : plugin.store().incomingTo(self.getUniqueId())) {
            final String name = request.requesterName();
            if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(name);
                if (out.size() >= VTpa.MAX_SUGGESTIONS) {
                    break;
                }
            }
        }
        return out;
    }

    @Override
    public boolean hasPermission(final Invocation invocation) {
        return true;
    }
}
