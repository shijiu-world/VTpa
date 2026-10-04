package cn.shijiu.vtpa.command;

import cn.shijiu.vtpa.VTpa;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;

import java.util.List;

/** {@code /vtpa version} —— 显示插件版本（反馈问题时先报这个）。 */
public final class VersionCommand implements SimpleCommand {

    private final VTpa plugin;

    public VersionCommand(final VTpa plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final Invocation invocation) {
        final CommandSource source = invocation.source();
        plugin.send(source, "&8[&bVTpa&8] &7版本 &f" + plugin.version());
        plugin.send(source, "&7跨服传送请求 —— 代理端 &fVTpa&7 + 子服端 &fVTpaBridge");
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
