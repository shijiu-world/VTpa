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

/** {@code /tpahere <玩家>} —— 请求把对方传送到我这儿（动的是对方）。 */
public final class TpaHereCommand implements SimpleCommand {

    private final VTpa plugin;

    public TpaHereCommand(final VTpa plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final Invocation invocation) {
        final CommandSource source = invocation.source();
        final Configuration config = plugin.configuration();
        if (!Permissions.require(plugin, source, Permissions.USE, config.allowByDefault())
                || !Permissions.require(plugin, source, Permissions.HERE, config.allowByDefault())) {
            return;
        }
        if (!(source instanceof Player)) {
            plugin.send(source, config.message("players-only"));
            return;
        }
        final String[] args = invocation.arguments();
        if (args.length < 1) {
            plugin.send(source, config.message("usage-tpa",
                    "label", config.label(invocation.alias(), "tpahere")));
            return;
        }
        final Player target = plugin.service().resolve(source, args[0]);
        if (target == null) {
            return;
        }
        plugin.service().sendRequest((Player) source, target, RequestType.HERE,
                config.label(invocation.alias(), "tpahere"));
    }

    @Override
    public List<String> suggest(final Invocation invocation) {
        final String[] args = invocation.arguments();
        final String prefix = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        return plugin.onlineNames(prefix);
    }

    @Override
    public boolean hasPermission(final Invocation invocation) {
        return true;
    }
}
