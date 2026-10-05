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
 * {@code /tpacancel [玩家]} —— 撤回我发出去的请求。
 *
 * <p>有了它就不用干等三分钟：被拒绝 / 被接受 / 主动取消 / 超时，请求就算完结，
 * 可以立刻再给同一个人发新的。
 */
public final class TpaCancelCommand implements SimpleCommand {

    private final VTpa plugin;

    public TpaCancelCommand(final VTpa plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final Invocation invocation) {
        final CommandSource source = invocation.source();
        final Configuration config = plugin.configuration();
        if (!Permissions.require(plugin, source, Permissions.CANCEL, config.allowByDefault())) {
            return;
        }
        if (!(source instanceof Player)) {
            plugin.send(source, config.message("players-only"));
            return;
        }
        final String[] args = invocation.arguments();
        if (args.length > 1) {
            plugin.send(source, config.message("usage-tpacancel",
                    "label", config.label(invocation.alias(), "tpacancel")));
            return;
        }
        plugin.service().cancel((Player) source, args.length == 1 ? args[0] : null);
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
        for (final TpaRequest request : plugin.store().outgoingFrom(self.getUniqueId())) {
            final String name = request.targetName();
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
