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

/** {@code /tpadeny [玩家]} —— 拒绝一条发给我的请求。挑请求的规则跟 /tpaccept 一样。 */
public final class TpaDenyCommand implements SimpleCommand {

    private final VTpa plugin;

    public TpaDenyCommand(final VTpa plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final Invocation invocation) {
        final CommandSource source = invocation.source();
        final Configuration config = plugin.configuration();
        if (!Permissions.require(plugin, source, Permissions.DENY, config.allowByDefault())) {
            return;
        }
        if (!(source instanceof Player)) {
            plugin.send(source, config.message("players-only"));
            return;
        }
        final String[] args = invocation.arguments();
        if (args.length > 1) {
            plugin.send(source, config.message("usage-tpadeny",
                    "label", config.label(invocation.alias(), "tpadeny")));
            return;
        }
        plugin.service().deny((Player) source, args.length == 1 ? args[0] : null);
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
            }
        }
        return out;
    }

    @Override
    public boolean hasPermission(final Invocation invocation) {
        return true;
    }
}
