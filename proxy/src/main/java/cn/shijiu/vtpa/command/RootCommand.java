package cn.shijiu.vtpa.command;

import cn.shijiu.vtpa.Configuration;
import cn.shijiu.vtpa.Permissions;
import cn.shijiu.vtpa.VTpa;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /vtpa <子命令> …} —— 全插件只有这一个命令入口（别名默认 {@code /vt}）。
 *
 * <p>之所以全收进来：代理上同一个命令名只能注册一次，收成一个入口之后
 * 想怎么叫都行，也不会误伤后端子服的命令（需要接管 {@code /tpa} 这种短命令时，
 * 走 {@code [shortcuts]} 单独注册）。
 */
public final class RootCommand implements SimpleCommand {

    /** 一条子命令：名字、用法、说明，以及权限（决定它在帮助里出不出现）。 */
    private static final class Entry {
        final String name;
        final String usage;
        final String description;
        final String permission;
        final boolean basic;
        final SimpleCommand command;

        Entry(final String name, final String usage, final String description,
              final String permission, final boolean basic, final SimpleCommand command) {
            this.name = name;
            this.usage = usage;
            this.description = description;
            this.permission = permission;
            this.basic = basic;
            this.command = command;
        }

        /** 这个人有没有资格在帮助里看到这一条。 */
        boolean visible(final CommandSource source, final Configuration config) {
            return permission == null
                    || Permissions.has(source, permission, basic && config.allowByDefault());
        }
    }

    private final VTpa plugin;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public RootCommand(final VTpa plugin) {
        this.plugin = plugin;
        add("tpa", new TpaCommand(plugin), "tpa <玩家>", "请求传送到对方那里",
                Permissions.USE, true);
        add("tpahere", new TpaHereCommand(plugin), "tpahere <玩家>", "请求对方传送到我这儿",
                Permissions.HERE, true);
        add("tpaccept", new TpaAcceptCommand(plugin), "tpaccept [玩家]", "接受一条请求",
                Permissions.ACCEPT, true);
        add("tpadeny", new TpaDenyCommand(plugin), "tpadeny [玩家]", "拒绝一条请求",
                Permissions.DENY, true);
        add("tpacancel", new TpaCancelCommand(plugin), "tpacancel [玩家]", "撤回我发出去的请求",
                Permissions.CANCEL, true);
        add("tpatoggle", new TpaToggleCommand(plugin), "tpatoggle [on|off]", "开关接收别人的请求",
                Permissions.TOGGLE, true);
        add("tpaall", new TpaAllCommand(plugin), "tpaall", "请求全服玩家传送到我这儿",
                Permissions.ALL, false);
        add("tpaworld", new TpaWorldCommand(plugin), "tpaworld", "请求同世界的玩家传送到我这儿",
                Permissions.WORLD, false);
        add("tpaserver", new TpaServerCommand(plugin), "tpaserver", "请求本子服玩家传送到我这儿",
                Permissions.SERVER, false);
        add("reload", new ReloadCommand(plugin), "reload", "重载配置",
                Permissions.RELOAD, false);
        add("version", new VersionCommand(plugin), "version", "显示插件版本", null, false);
    }

    private void add(final String name, final SimpleCommand command, final String usage,
                     final String description, final String permission, final boolean basic) {
        entries.put(name, new Entry(name, usage, description, permission, basic, command));
    }

    // ------------------------------------------------------------------
    // 分发
    // ------------------------------------------------------------------

    @Override
    public void execute(final Invocation invocation) {
        final CommandSource source = invocation.source();
        final String[] args = invocation.arguments();
        if (args.length == 0) {
            help(source);
            return;
        }
        final String name = args[0].toLowerCase(Locale.ROOT);
        if (name.equals("help") || name.equals("?")) {
            help(source);
            return;
        }
        final Entry entry = resolve(name);
        if (entry == null) {
            plugin.send(source, plugin.configuration().message("unknown-subcommand", "sub", args[0]));
            return;
        }
        entry.command.execute(new SubInvocation(invocation, tail(args)));
    }

    @Override
    public List<String> suggest(final Invocation invocation) {
        final Configuration config = plugin.configuration();
        final String[] args = invocation.arguments();
        if (args.length <= 1) {
            final String prefix = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
            final List<String> out = new ArrayList<>();
            for (final Entry entry : entries.values()) {
                if (!entry.visible(invocation.source(), config)) {
                    continue;
                }
                if (entry.name.startsWith(prefix)) {
                    out.add(entry.name);
                }
                for (final String alias : config.subAliases().getOrDefault(entry.name, List.of())) {
                    if (alias.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        out.add(alias);
                    }
                }
            }
            return out;
        }
        final Entry entry = resolve(args[0]);
        if (entry == null) {
            return List.of();
        }
        return entry.command.suggest(new SubInvocation(invocation, tail(args)));
    }

    /** 权限一律在子命令里判 —— 没权限的人看到的是"你没权限"，而不是"命令不存在"。 */
    @Override
    public boolean hasPermission(final Invocation invocation) {
        return true;
    }

    // ------------------------------------------------------------------
    // 帮助
    // ------------------------------------------------------------------

    private void help(final CommandSource source) {
        final Configuration config = plugin.configuration();
        plugin.send(source, "&8===== &bVTpa &8=====");
        for (final Entry entry : entries.values()) {
            if (!entry.visible(source, config)) {
                continue;
            }
            plugin.send(source, "&7" + config.label() + " " + entry.usage + " &8- &f" + entry.description);
        }
        plugin.send(source, "&7顶层还有 &f/tpa &f/tpahere &f/tpaccept &f/tpadeny &f/tpacancel"
                + " &7这些短命令（在 [shortcuts] 里改）");
    }

    /** 按名字（或别名）找子命令的执行器；找不到返回 null。 */
    public SimpleCommand lookup(final String nameOrAlias) {
        final Entry entry = resolve(nameOrAlias == null ? "" : nameOrAlias.toLowerCase(Locale.ROOT));
        return entry == null ? null : entry.command;
    }

    private Entry resolve(final String name) {
        final Entry direct = entries.get(name);
        if (direct != null) {
            return direct;
        }
        for (final Map.Entry<String, List<String>> e : plugin.configuration().subAliases().entrySet()) {
            for (final String alias : e.getValue()) {
                if (alias.equalsIgnoreCase(name)) {
                    return entries.get(e.getKey());
                }
            }
        }
        return null;
    }

    private static String[] tail(final String[] args) {
        return Arrays.copyOfRange(args, 1, args.length);
    }

    /** 把 invocation 剥掉第一个参数交给子命令 —— source 和 alias 原样透传。 */
    private static final class SubInvocation implements Invocation {
        private final Invocation parent;
        private final String[] args;

        SubInvocation(final Invocation parent, final String[] args) {
            this.parent = parent;
            this.args = args;
        }

        @Override
        public CommandSource source() {
            return parent.source();
        }

        @Override
        public String[] arguments() {
            return args;
        }

        @Override
        public String alias() {
            return parent.alias();
        }
    }
}
