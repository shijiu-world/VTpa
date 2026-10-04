package cn.shijiu.vtpa.command;

import cn.shijiu.vtpa.Permissions;
import cn.shijiu.vtpa.VTpa;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;

import java.util.List;

/** {@code /vtpa reload} —— 重读 config.toml。读失败会保留旧配置，不会让插件变成半成品。 */
public final class ReloadCommand implements SimpleCommand {

    private final VTpa plugin;

    public ReloadCommand(final VTpa plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final Invocation invocation) {
        final CommandSource source = invocation.source();
        if (!Permissions.require(plugin, source, Permissions.RELOAD, false)) {
            return;
        }
        plugin.reload(source);
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
