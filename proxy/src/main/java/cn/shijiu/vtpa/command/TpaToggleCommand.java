package cn.shijiu.vtpa.command;

import cn.shijiu.vtpa.Configuration;
import cn.shijiu.vtpa.Permissions;
import cn.shijiu.vtpa.VTpa;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /tpatoggle [on|off]} —— 关掉 / 恢复「接收别人的传送请求」。
 *
 * <p>关掉之后别人给你发请求只会收到一句「对方关掉了传送请求」，
 * 你这边完全不会被打扰（有 {@code vtpa.toggle.bypass} 的人除外）。
 */
public final class TpaToggleCommand implements SimpleCommand {

    private final VTpa plugin;

    public TpaToggleCommand(final VTpa plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final Invocation invocation) {
        final CommandSource source = invocation.source();
        final Configuration config = plugin.configuration();
        if (!Permissions.require(plugin, source, Permissions.TOGGLE, config.allowByDefault())) {
            return;
        }
        if (!(source instanceof Player)) {
            plugin.send(source, config.message("players-only"));
            return;
        }
        final Player self = (Player) source;
        final String[] args = invocation.arguments();
        final boolean disabled;
        if (args.length == 0) {
            disabled = !plugin.service().isDisabled(self.getUniqueId());
        } else {
            final String arg = args[0].toLowerCase(Locale.ROOT);
            if (arg.equals("on") || arg.equals("true") || arg.equals("enable")) {
                disabled = false;
            } else if (arg.equals("off") || arg.equals("false") || arg.equals("disable")) {
                disabled = true;
            } else {
                plugin.send(source, config.message("usage-tpatoggle",
                        "label", config.label(invocation.alias(), "tpatoggle")));
                return;
            }
        }
        plugin.service().setDisabled(self.getUniqueId(), disabled);
        plugin.saveToggles();
        plugin.send(source, config.message(disabled ? "toggle-off" : "toggle-on"));
    }

    @Override
    public List<String> suggest(final Invocation invocation) {
        final String[] args = invocation.arguments();
        if (args.length != 1) {
            return List.of();
        }
        final String prefix = args[0].toLowerCase(Locale.ROOT);
        final List<String> out = new ArrayList<>();
        for (final String option : List.of("on", "off")) {
            if (option.startsWith(prefix)) {
                out.add(option);
            }
        }
        return out;
    }

    @Override
    public boolean hasPermission(final Invocation invocation) {
        return true;
    }
}
