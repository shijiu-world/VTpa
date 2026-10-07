import cn.shijiu.vtpa.Backend;
import cn.shijiu.vtpa.Colors;
import cn.shijiu.vtpa.Configuration;
import cn.shijiu.vtpa.FxSpec;
import cn.shijiu.vtpa.Permissions;
import cn.shijiu.vtpa.RequestStore;
import cn.shijiu.vtpa.RequestType;
import cn.shijiu.vtpa.TomlLite;
import cn.shijiu.vtpa.TpaRequest;
import cn.shijiu.vtpa.Wire;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainComponentSerializer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * VTpa 冒烟测试：TOML 解析 + 配置取值 + 请求账本 + 插件消息协议编解码。
 * 不依赖 Velocity 运行时，纯逻辑，直接 javac + java 就能跑。
 *
 * <pre>
 *   javac -cp &lt;proxy/target/classes:velocity-api.jar:...&gt; -d out tests/SmokeTest.java
 *   java  -cp &lt;同上&gt;:out SmokeTest &lt;path/to/config.toml&gt;
 * </pre>
 */
public class SmokeTest {

    private static final List<String> failures = new java.util.ArrayList<>();
    private static int passed;

    public static void main(final String[] args) throws Exception {
        final Map<String, Object> map = TomlLite.parse(Path.of(args[0]));
        System.out.println("解析出 " + map.size() + " 个键");

        // ---- 基本配置 ----
        check("请求时效默认 180 秒", TomlLite.integer(map, "general.request-timeout-seconds", 0L) == 180L);
        check("传送延迟默认 3 秒", TomlLite.integer(map, "general.teleport-delay-seconds", 0L) == 3L);
        check("冷却默认 5 秒", TomlLite.integer(map, "general.cooldown-seconds", 0L) == 5L);
        check("最多挂 3 个外出请求", TomlLite.integer(map, "general.max-outgoing-requests", 0L) == 3L);
        check("移动容差 0 格（动一下就取消，跟 CMI 一样）",
                TomlLite.decimal(map, "movement.tolerance", -1D) == 0D);
        check("倒计时模式默认 title", "title".equals(TomlLite.string(map, "countdown.mode", "")));
        check("请求文本里有接受占位符", ((String) map.get("request.tpa")).contains("#accept#"));
        check("请求文本里有拒绝占位符", ((String) map.get("request.here")).contains("#deny#"));
        check("请求文本里用 %player% 指代发起者", ((String) map.get("request.tpa")).contains("%player%"));
        check("接受按钮的点击命令带 %player%",
                ((String) map.get("buttons.accept-command")).contains("%player%"));
        check("按钮悬停提示可配", map.containsKey("buttons.accept-hover"));
        check("撤回按钮有默认文字", map.containsKey("buttons.cancel-text")
                && !((String) map.get("buttons.cancel-text")).isBlank());
        check("撤回命令带 %player%", ((String) map.get("buttons.cancel-command")).contains("%player%"));
        check("Cancel 按钮指向 tpacancel",
                ((String) map.get("buttons.cancel-command")).contains("/tpacancel"));
        check("顶层快捷命令默认接管 /tpa", map.containsKey("shortcuts.tpa"));
        check("顶层快捷命令默认接管 /tpaccept", map.containsKey("shortcuts.tpaccept"));
        check("顶层快捷命令默认接管 /tpall", map.containsKey("shortcuts.tpall"));
        check("顶层快捷命令默认接管 /tpaworld", map.containsKey("shortcuts.tpaworld"));
        check("顶层快捷命令默认接管 /tpaserver", map.containsKey("shortcuts.tpaserver"));
        check("批量请求有各自的汇总文案", map.containsKey("messages.request-sent-world")
                && map.containsKey("messages.request-sent-server"));
        check("tpaworld 拿不到世界时有提示", map.containsKey("messages.world-unknown"));
        check("两个新命令都要专门权限（不是基础节点）",
                "vtpa.world".equals(cn.shijiu.vtpa.Permissions.WORLD)
                        && "vtpa.server".equals(cn.shijiu.vtpa.Permissions.SERVER));

        // ---- 群发命令改名：/tpaall → /tpall（1.5.0）----
        check("随包配置里群发子命令的主名是 tpall", map.containsKey("commands.tpall"));
        check("随包配置里不再有 commands.tpaall 这个键", !map.containsKey("commands.tpaall"));
        final Configuration bundled = new Probe(map).unwrap();
        check("顶层注册了 /tpall", "tpall".equals(bundled.shortcuts().get("tpall")));
        // ★ 1.6.0 起旧名彻底不接管：既不再注册顶层 /tpaall，也不再当子命令别名
        check("旧名 /tpaall 不再注册", !bundled.shortcuts().containsKey("tpaall"));
        check("旧名 /tpaall 不再是子命令别名",
                !bundled.subAliases().getOrDefault("tpall", List.of()).contains("tpaall"));
        check("用法提示的键跟着改成 usage-tpall", bundled.hasMessage("usage-tpall"));
        // 老 config.toml 升级上来：三处老键都得自动搬过来，否则命令会凭空消失
        final Configuration legacyAll = new Probe(TomlLite.parse(
                "[commands]\ntpaall = [\"tpall\"]\n"
                        + "[shortcuts]\ntpaall = \"tpaall\"\n"
                        + "[messages]\nusage-tpaall = \"&7用法：&f#label#\"\n")).unwrap();
        check("★ 老配置：子命令主名搬到 tpall 下",
                legacyAll.subAliases().getOrDefault("tpall", List.of()).contains("tpaall"));
        check("★ 老配置：不再残留 tpaall 这个子命令键", !legacyAll.subAliases().containsKey("tpaall"));
        check("★ 老配置：顶层 /tpall 被补注册上", "tpall".equals(legacyAll.shortcuts().get("tpall")));
        check("★ 老配置：顶层 /tpaall 改指 tpall（不会「指向不存在的子命令」）",
                "tpall".equals(legacyAll.shortcuts().get("tpaall")));
        check("★ 老配置：用法提示键也搬了（不会显示「缺少配置项」）",
                legacyAll.hasMessage("usage-tpall"));

        // ---- Configuration ----
        final Configuration defaults = Configuration.defaults();
        check("jar 内置默认配置可用", defaults.requestTpa().contains("#accept#"));
        check("默认时效 180 秒", defaults.requestTimeoutSeconds() == 180L);
        check("默认延迟 3 秒", defaults.teleportDelaySeconds() == 3L);
        check("默认主命令别名是 vt", defaults.rootAliases().contains("vt"));
        check("label() 跟着别名变", defaults.label().equals("/vt"));
        check("走 /tpa 进来提示 /tpa", defaults.label("tpa", "tpa").equals("/tpa"));
        check("走 /vt tpa 进来提示 /vt tpa", defaults.label("vt", "tpa").equals("/vt tpa"));
        check("拼不出 alias 时退回 /vt tpa", defaults.label(null, "tpa").equals("/vt tpa"));
        check("默认配置里 prefix 是空的（不想要就自己填）", "".equals(map.get("messages.prefix")));
        check("prefix 为空时提示语就是原文",
                defaults.message("self-request").equals(defaults.rawMessage("self-request")));
        check("子服端移动检测默认开", defaults.movementBackend());
        check("代理轮询默认也开着（双保险，防子服那条路静默失效）", defaults.movementPollAlso());
        check("子服名单默认对所有人生效（vtpa.server.bypass 默认不查）",
                !defaults.serverBypassEnabled());
        check("运行日志默认关（advanced.debug = false），控制台不刷屏", !defaults.debug());
        check("默认配置里已经没有 log-to-console 这一项了（并进 advanced.debug）",
                !map.containsKey("advanced.log-to-console"));
        check("默认配置里已经没有心跳间隔了（去掉了 PING/PONG 探测）",
                !map.containsKey("bridge.ping-interval-seconds"));
        check("桥接版本默认跟 jar 同版本（1.6.0）", "1.6.0".equals(defaults.bridgeVersion()));
        check("桥接版本够新 → 子服端移动检测可用",
                Backend.atLeast(defaults.bridgeVersion(), 1, 1, 0));
        check("落点默认锁在「接受那一刻」（对方之后走动不影响落点）",
                defaults.lockDestination()
                        && Boolean.TRUE.equals(map.get("general.lock-destination")));
        check("移动取消不看权限：配置里没有 permissions.move-bypass 了",
                !map.containsKey("permissions.move-bypass"));
        check("移动取消不看权限：vtpa.move.bypass 节点已删除（谁都绕不过，含 OP）",
                !hasMoveBypassNode());
        check("默认竖直方向也算动（跳一下就取消，跟 CMI 一致）", !defaults.movementIgnoreY());
        check("默认容差 0 —— 动一下就取消", defaults.movementTolerance() == 0D);
        check("声音默认开", defaults.soundsEnabled());
        check("请求音效用的是 CMI 那个", "block_anvil_land:0.5:2".equals(defaults.sound("request")));
        check("倒计时音效用的是 CMI 那个",
                "blockrespawnanchorcharge:1:1".equals(defaults.sound("countdown")));
        check("出发音效 entity_enderman_teleport:2:1",
                "entity_enderman_teleport:2:1".equals(defaults.sound("depart")));
        check("落地音效 entity_enderman_teleport:0.2:1",
                "entity_enderman_teleport:0.2:1".equals(defaults.sound("arrive")));
        check("没配的音效返回 null（= 不播）", defaults.sound("根本没这个") == null);
        // —— 功能：配置文本为空时整条不发送
        final Configuration blank = new Probe(TomlLite.parse(
                "[messages]\nprefix = \"&8[&b传送&8]&r \"\nself-request = \"\"\n"
                        + "cooldown = \"&c\"\n")).unwrap();
        check("文本为空 → 整条不发送（连 prefix 都不带）", blank.message("self-request").isEmpty());
        check("只剩颜色码也算空 → 不发", blank.message("cooldown").isEmpty());
        check("空文本不会误伤正常文本", !blank.message("no-permission").isEmpty());
        check("Colors.isBlank 认得空串", Colors.isBlank(""));
        check("Colors.isBlank 认得纯颜色码", Colors.isBlank("&c&l"));
        check("Colors.isBlank 不误判正常文本", !Colors.isBlank("&c慢一点"));
        check("Colors.plain 能剥掉 hex 颜色", Colors.plain("&#FF0000红").equals("红"));
        check("声音关掉后一律返回 null", new Probe(TomlLite.parse(
                "[sounds]\nenabled = false\nrequest = \"block_anvil_land:0.5:2\"\n"))
                .unwrap().sound("request") == null);
        check("提示语缺配置时有兜底", defaults.message("根本没这个键").contains("messages.根本没这个键"));
        check("占位符替换生效", defaults.message("request-sent", "target", "小明", "seconds", "180")
                .contains("小明"));
        // ⚠️ 别绑死成 switch：默认配置在 4638ec0 里改成了 deny（宁可不送，也不送到莫名其妙的地方），
        //    改回 switch 的话这条要跟着改
        check("子服没装桥接默认 deny", "deny".equals(defaults.bridgeMissing()));
        check("桥接通道默认 vtpa:main", "vtpa:main".equals(defaults.bridgeChannel()));
        check("mode 写歪了退回 none", new Probe(TomlLite.parse(
                "[countdown]\nmode = \"随便写\"\n")).unwrap().countdownMode().equals("none"));
        check("missing 写歪了退回 deny（宁可不送）", "deny".equals(new Probe(TomlLite.parse(
                "[bridge]\nmissing = \"随便写\"\n")).unwrap().bridgeMissing()));

        // ---- 「传送到某个子服」的权限 vtpa.to.<服名> ----
        check("travel 节点按服名拼、一律转小写",
                "vtpa.to.survival".equals(Permissions.travelNode("Survival")));
        check("travel 节点服名两端的空格会被 trim",
                "vtpa.to.industry".equals(Permissions.travelNode("  Industry  ")));
        check("取不到服名时不判定（返回 null，调用方按放行处理）",
                Permissions.travelNode(null) == null && Permissions.travelNode("  ") == null);
        check("bypass 和 travel 是两个不同的节点，通配不会串",
                !Permissions.TRAVEL_BYPASS.equals(Permissions.TRAVEL)
                        && Permissions.TRAVEL_BYPASS.startsWith(Permissions.TRAVEL));
        // 三种真实权限值：显式给了 / 显式拒了 / 压根没配过（undefined）
        final CommandSource granted = sourceOf("vtpa.to.survival", Tristate.TRUE);
        final CommandSource denied = sourceOf("vtpa.to.survival", Tristate.FALSE);
        final CommandSource untouched = sourceOf("vtpa.to.nothing", Tristate.TRUE);
        check("★ 显式给了 vtpa.to.survival → 能进 survival",
                Permissions.mayTravelTo(granted, "survival", false));
        check("★ 显式拒了 vtpa.to.survival → 进不去 survival",
                !Permissions.mayTravelTo(denied, "survival", false));
        check("★ 压根没配过（undefined）→ 拒绝 —— 这套节点的默认就是未定义",
                !Permissions.mayTravelTo(untouched, "survival", false));
        check("服名大小写不敏感（节点已经转小写）", Permissions.mayTravelTo(granted, "SURVIVAL", false));
        check("给了这个服换不到别服（survival 的 grant 不能进 industry）",
                !Permissions.mayTravelTo(granted, "industry", false));
        check("落点服取不到时放行（交给后面的闸去拦）", Permissions.mayTravelTo(untouched, null, false));
        // bypass 默认必须焊死：给管理组发 vtpa.* / * 会让它自动成立
        final CommandSource admin = sourceOf("vtpa.to.bypass", Tristate.TRUE);
        check("to-bypass 默认不生效：有 vtpa.to.bypass 照样被 vtpa.to.<服名> 拦住",
                !Permissions.mayTravelTo(admin, "survival", false));
        check("to-bypass 打开后 vtpa.to.bypass 才生效",
                Permissions.mayTravelTo(admin, "survival", true));
        check("随包配置默认不给 bypass 特权", !defaults.toBypassEnabled());
        check("随包配置里显式写出了 permissions.to-bypass", map.containsKey("permissions.to-bypass"));
        check("被拦时有「我自己没权限」那条提示语，且带 #server#",
                map.containsKey("messages.travel-denied-self")
                        && defaults.message("travel-denied-self", "server", "industry")
                                .contains("industry"));
        check("被拦时有「对方没权限」那条提示语，说清楚是谁（#target#）",
                map.containsKey("messages.travel-denied-target")
                        && defaults.message("travel-denied-target", "target", "小明",
                                "server", "industry").contains("小明"));

        check("被拒绝之后的封锁默认 300 秒", defaults.denyCooldownSeconds() == 300L
                && TomlLite.integer(map, "general.deny-cooldown-seconds", 0L) == 300L);
        check("随包配置里显式写出了 deny-cooldown-seconds",
                map.containsKey("general.deny-cooldown-seconds"));
        check("封锁期设成 0 就是不限（负数也不会变成别的怪值）",
                new Probe(TomlLite.parse("[general]\ndeny-cooldown-seconds = 0\n"))
                        .unwrap().denyCooldownSeconds() == 0L);
        check("封锁跟「发起冷却」是两个独立的配置（改一个不影响另一个）",
                new Probe(TomlLite.parse("[general]\ncooldown-seconds = 60\n"))
                        .unwrap().denyCooldownSeconds() == 300L);
        check("被拒绝时有专属提示语，且说得清还剩几秒、是谁",
                map.containsKey("messages.deny-cooldown")
                        && defaults.message("deny-cooldown", "seconds", "137", "player", "阿乙")
                                .contains("阿乙")
                        && defaults.message("deny-cooldown", "seconds", "137")
                                .contains("137"));
        check("封锁提示用的是用户要的那句话",
                defaults.rawMessage("deny-cooldown")
                        .equals("&e你接下来的 &6#seconds# &e秒内无法发送请求到 &6#player#"));

        // ---- 服务器名单 ----
        check("黑名单默认全放行", defaults.filter().allows("survival"));
        check("黑名单里的服被拦住", !new Probe(blacklistMap()).unwrap().filter().allows("bedwars"));
        check("服名大小写不敏感", !new Probe(blacklistMap()).unwrap().filter().allows("BEDWARS"));
        check("服名取不到（登录中）按放行处理", defaults.filter().allows(null));
        check("白名单模式名单内放行", new Probe(whitelistMap()).unwrap().filter().allows("lobby"));
        check("白名单模式名单外拦住", !new Probe(whitelistMap()).unwrap().filter().allows("survival"));

        // ---- 请求账本 ----
        final RequestStore store = new RequestStore();
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        final long now = System.currentTimeMillis();
        final TpaRequest r1 = new TpaRequest(a, "阿甲", b, "阿乙", RequestType.TPA, now, now + 180_000L);
        store.put(r1);
        check("放进去就能查到", store.findBetween(a, b) == r1);
        check("反方向也能查到（两个人之间只准挂一个）", store.findBetween(b, a) == r1);
        check("按被请求者能列出", store.incomingTo(b).size() == 1);
        check("按发起者能列出", store.outgoingFrom(a).size() == 1);
        check("外出计数正确", store.outgoingCount(a) == 1);

        final TpaRequest r2 = new TpaRequest(a, "阿甲", b, "阿乙", RequestType.HERE, now, now + 180_000L);
        store.put(r2);
        check("同一对玩家重复 put 会覆盖（不会叠加）", store.outgoingCount(a) == 1);
        store.remove(r2);
        check("删掉之后查不到", store.findBetween(a, b) == null);
        check("删掉之后列出为空", store.incomingTo(b).isEmpty());

        store.put(r1);
        check("掉线清掉相关的请求（含反向）", store.removeAllFor(b).size() == 1
                && store.findBetween(a, b) == null && store.outgoingCount(a) == 0);

        final TpaRequest expired = new TpaRequest(a, "阿甲", b, "阿乙", RequestType.TPA,
                now - 500_000L, now - 320_000L);
        store.put(expired);
        check("过期的会被清扫出来", store.removeExpired(now).size() == 1);
        check("清扫之后账本空了", store.size() == 0);

        // ---- 请求本体 ----
        check("TPA 动的是发起者", RequestType.TPA.movesRequester());
        check("HERE 动的是被请求者", !RequestType.HERE.movesRequester());
        check("剩余秒数向上取整", r1.remainingSeconds(now) == 180L);
        check("已过期就不剩了", expired.remainingSeconds(now) == 0L);
        check("另一个当事人取对", r1.other(a).equals(b));
        check("另一个当事人的名字取对", "阿甲".equals(r1.otherName(b)));

        // ---- 互相请求（反向请求自动同意）—— 判定全是纯数据，不开服也能测 ----
        // 四种组合：谁先发 × 发的是 tpa 还是 tpahere
        final long t0 = 1_000_000L;
        final TpaRequest aTpaB = new TpaRequest(a, "阿甲", b, "阿乙", RequestType.TPA, t0, t0 + 180_000L);
        final TpaRequest bTpaA = new TpaRequest(b, "阿乙", a, "阿甲", RequestType.TPA, t0, t0 + 180_000L);
        final TpaRequest aHereB = new TpaRequest(a, "阿甲", b, "阿乙", RequestType.HERE, t0, t0 + 180_000L);
        final TpaRequest bHereA = new TpaRequest(b, "阿乙", a, "阿甲", RequestType.HERE, t0, t0 + 180_000L);
        check("/tpa B 动的是发起者、落点是对方",
                aTpaB.moverId().equals(a) && aTpaB.destinationId().equals(b));
        check("/tpahere B 动的是对方、落点是自己",
                aHereB.moverId().equals(b) && aHereB.destinationId().equals(a));
        check("动的人 / 落点的名字也对得上",
                "阿甲".equals(aTpaB.moverName()) && "阿乙".equals(aTpaB.destinationName())
                        && "阿乙".equals(aHereB.moverName()) && "阿甲".equals(aHereB.destinationName()));
        check("★ A /tpa B + B /tpahere A → 都是 A 去 B，算互相请求",
                aTpaB.sameOutcomeAs(bHereA) && bHereA.sameOutcomeAs(aTpaB));
        check("★ A /tpahere B + B /tpa A → 都是 B 去 A，也算互相请求",
                aHereB.sameOutcomeAs(bTpaA) && bTpaA.sameOutcomeAs(aHereB));
        check("A /tpa B + B /tpa A → 一个想去对方那儿，结果相反，不算",
                !aTpaB.sameOutcomeAs(bTpaA));
        check("A /tpahere B + B /tpahere A → 结果相反，不算",
                !aHereB.sameOutcomeAs(bHereA));
        check("同一个人的两条（/tpa B 与 /tpahere B）结果也相反",
                !aTpaB.sameOutcomeAs(aHereB));
        check("跟 null 比不算互相请求（不炸）", !aTpaB.sameOutcomeAs(null));
        check("互相请求自动同意默认开", defaults.reverseAutoAccept()
                && Boolean.TRUE.equals(map.get("general.reverse-auto-accept")));
        check("随包配置里显式写出了 reverse-auto-accept",
                map.containsKey("general.reverse-auto-accept"));
        check("关掉开关就不自动同意", !new Probe(TomlLite.parse(
                "[general]\nreverse-auto-accept = false\n")).unwrap().reverseAutoAccept());
        check("互相同意有专属提示语（被传送的那个人）",
                map.containsKey("messages.mutual-accept-mover")
                        && defaults.message("mutual-accept-mover").contains("#seconds#"));
        check("互相同意有专属提示语（另一方）",
                map.containsKey("messages.mutual-accept-dest")
                        && defaults.message("mutual-accept-dest").contains("#player#"));
        // 老配置（升级上来的）里没有这两条 → 得能退回老的，不能发出「缺少配置项」
        final Configuration old = new Probe(TomlLite.parse(
                "[messages]\naccepted-self = \"&e已接受\"\naccepted-other = \"&e被接受了\"\n"))
                .unwrap();
        check("老配置缺 mutual-accept-mover → hasMessage 为 false",
                !old.hasMessage("mutual-accept-mover") && !old.hasMessage("mutual-accept-dest"));
        check("老配置里 hasMessage 认得已有的键", old.hasMessage("accepted-self"));
        check("老配置退回 accepted-self，不发「缺少配置项」",
                !old.message("accepted-self").contains("缺少配置项")
                        && old.message("accepted-self").endsWith("&e已接受"));

        // ---- 插件消息协议 ----
        final Wire.Loc loc = new Wire.Loc("world", 1.5D, 64D, -3.25D, 90F, -12F);
        check("PING 能解回来", Wire.read(Wire.ping()).op() == Wire.OP_PING);
        check("PONG 带着版本号", "1.0.0".equals(Wire.read(Wire.pong("1.0.0")).text()));
        check("POS_REQ 带着 UUID", a.equals(Wire.read(Wire.positionRequest(a)).uuid()));
        final Wire.Packet res = Wire.read(Wire.positionResponse(a, loc));
        check("POS_RES 带坐标", res.op() == Wire.OP_POS_RES && res.loc().world().equals("world"));
        check("POS_RES 浮点不失真", res.loc().x() == 1.5D && res.loc().yaw() == 90F);
        check("POS_NONE 能解回来", b.equals(Wire.read(Wire.positionNone(b)).uuid()));
        final Wire.Packet tp = Wire.read(Wire.teleport(b, loc));
        check("TP 带完整落点", tp.op() == Wire.OP_TP && b.equals(tp.uuid())
                && tp.loc().z() == -3.25D);
        check("坏包不会炸（返回 null）", Wire.read(new byte[]{99}) == null);
        check("空包不会炸", Wire.read(null) == null);
        check("原地不动不算移动", loc.distance(new Wire.Loc("world", 1.5D, 64D, -3.25D, 0F, 0F)) == 0D);
        check("走 1 格超过 0 的容差（动一下就取消）",
                loc.distance(new Wire.Loc("world", 2.5D, 64D, -3.25D, 0F, 0F)) > 0D);
        check("原地跳 10 格：3D 距离算动了",
                loc.distance(new Wire.Loc("world", 1.5D, 74D, -3.25D, 0F, 0F)) > 0D);
        check("原地跳 10 格：水平距离仍是 0",
                loc.flatDistance(new Wire.Loc("world", 1.5D, 74D, -3.25D, 0F, 0F)) == 0D);

        // ---- 颜色 ----
        final Component colored = cn.shijiu.vtpa.Colors.colorize("&c红&#FF0000色");
        check("& 颜色码真的上色了", hasColor(colored));
        check("十六进制颜色码也认", hasColor(cn.shijiu.vtpa.Colors.colorize("&#00FF00绿")));
        check("纯文本不丢字",
                PlainComponentSerializer.plain().serialize(colored).equals("红色"));

        // ---- 粒子特效 ----
        fxTests(map);

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部 " + passed + " 条断言通过 ✅");
        } else {
            System.out.println("❌ 失败 " + failures.size() + " 条：");
            failures.forEach(f -> System.out.println("   - " + f));
            System.exit(1);
        }
    }

    private static void check(final String name, final boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ✅ " + name);
        } else {
            failures.add(name);
            System.out.println("  ❌ " + name);
        }
    }

    /**
     * 一个假的玩家：只有 {@code node} 这一个权限节点有值，其余一律 UNDEFINED
     * —— 正好模拟「服主只给了 vtpa.to.survival」这种情况。
     */
    private static CommandSource sourceOf(final String node, final Tristate state) {
        return new CommandSource() {
            @Override
            public Tristate getPermissionValue(final String permission) {
                return node.equals(permission) ? state : Tristate.UNDEFINED;
            }

            @Override
            public void sendMessage(final Identity source, final Component message) {
                // 测试用的桩，消息不需要落地
            }
        };
    }

    /**
     * 移动取消有没有留权限后门：反射扫一遍权限类，看还认不认 {@code vtpa.move.bypass}。
     * 认（常量还在 / 值对得上）就说明还有人能绕过 —— 这不该发生。
     */
    private static boolean hasMoveBypassNode() {
        for (final java.lang.reflect.Field field : Permissions.class.getFields()) {
            final String name = field.getName();
            if (!name.endsWith("BYPASS")) {
                continue;
            }
            try {
                if ("vtpa.move.bypass".equals(field.get(null))) {
                    return true;
                }
            } catch (final Exception ignored) {
                // 静态常量读不出来就当没有，下一条断言会兜住
            }
        }
        return false;
    }

    /** legacy 反序列化出来的往往是一棵树（每段一个颜色），递归看看有没有真的上色。 */
    private static boolean hasColor(final Component component) {
        if (component.color() != null) {
            return true;
        }
        for (final Component child : component.children()) {
            if (hasColor(child)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 粒子特效（CMI 风格预设串 + OP_FX 编解码）
    // ------------------------------------------------------------------

    private static void fxTests(final Map<String, Object> map) throws Exception {
        // —— 线上 CMI 里 tpaWarmup 那一串，原样搬过来
        final String warmup = "circle;effect:flying_glyph;dur:5;pitchc:15;part:10;"
                + "offset:0,1.7,0;radius:0.5;yawc:12;color:rs;pitch:90";
        final FxSpec spec = FxSpec.parse(warmup);
        check("CMI tpaWarmup 串能解析", spec != null);
        check("解析出的粒子是 flying_glyph", "flying_glyph".equals(spec.particle()));
        check("part:10 → 每圈 10 个粒子", spec.count() == 10);
        check("radius:0.5 → 半径 0.5", spec.radius() == 0.5D);
        check("pitch:90 → 水平环", spec.pitch() == 90D);
        check("pitchc:15 → 每 tick 转 15 度", spec.pitchChange() == 15D);
        check("yawc:12 → 每 tick 转 12 度", spec.yawChange() == 12D);
        check("color:rs → 彩虹色", spec.colorMode() == FxSpec.ColorMode.RAINBOW);
        check("dur:5 → 100 tick", spec.durationTicks() == 100);
        check("offset 解析成 0,1.7,0", spec.offset()[0] == 0D && spec.offset()[1] == 1.7D
                && spec.offset()[2] == 0D);
        check("预设串原样保留（要发给子服）", warmup.equals(spec.raw()));

        // —— 环上的点：水平环应该是「y 不变、xz 绕圈」
        final double[] p0 = new double[3];
        spec.point(0, 0, 0D, 90D, p0);
        check("水平环第一个点在半径上", Math.abs(Math.hypot(p0[0], p0[2]) - 0.5D) < 1e-6);
        check("水平环不改变高度", Math.abs(p0[1]) < 1e-6);
        final double[] p1 = new double[3];
        spec.point(0, 1, 0D, 90D, p1);
        check("相邻两个点不是同一个点", Math.hypot(p0[0] - p1[0], p0[2] - p1[2]) > 1e-6);
        // 转一圈回到起点附近（part 个点正好绕一圈）
        final double[] back = new double[3];
        spec.point(0, spec.count(), 0D, 90D, back);
        check("绕满一圈回到原点", Math.hypot(back[0] - p0[0], back[2] - p0[2]) < 1e-6);

        // —— 竖直环（pitch:0）应该只在 XY 平面上动
        final double[] v = new double[3];
        FxSpec.parse("circle;part:4;radius:1;pitch:0").point(0, 1, 0D, 0D, v);
        check("pitch:0 是竖直环（z 不动）", Math.abs(v[2]) < 1e-6);

        // —— 彩虹色真的会变
        final FxSpec rainbow = FxSpec.parse("circle;effect:reddust;color:rs;part:3");
        check("彩虹：同一 tick 不同粒子颜色不同",
                rainbow.color(0, 0) != rainbow.color(0, 1));
        check("彩虹：同一粒子不同 tick 颜色不同",
                rainbow.color(0, 0) != rainbow.color(20, 0));

        // —— 固定色
        final FxSpec fixed = FxSpec.parse("circle;c:255,0,10;part:2");
        check("固定色解析成红", fixed.colorMode() == FxSpec.ColorMode.FIXED
                && fixed.red() == 255 && fixed.green() == 0 && fixed.blue() == 10);
        check("固定色不随时间变", fixed.color(0, 0) == fixed.color(30, 1));

        // —— 宽容：参数写坏只跳过这一个，不让整条失效
        final FxSpec broken = FxSpec.parse("circle;effect:heart;part:abc;radius:zzz;offset:1,x,3");
        check("写坏了也能解析出来（不整条废掉）", broken != null);
        check("写坏了会记下问题", !broken.problems().isEmpty());
        check("坏参数不影响好参数", "heart".equals(broken.particle()));
        check("空串 = 不播", FxSpec.parse("") == null && FxSpec.parse(null) == null
                && FxSpec.parse("   ") == null);

        // —— 关键字
        final FxSpec keys = FxSpec.parse("circle;effect:flame;twist;static;hwv");
        check("twist 生效", keys.twist());
        check("static 生效", keys.fixed());
        check("hwv 生效", keys.hideWhenVanished());
        check("认不出的关键字被忽略（不报错）",
                FxSpec.parse("circle;effect:flame;不认识的词").problems().isEmpty());

        // —— move：向上冲的光柱
        final FxSpec up = FxSpec.parse("circle;part:1;radius:0.5;pitch:90;move:0,0.33,0");
        final double[] low = new double[3];
        final double[] high = new double[3];
        up.point(0, 0, 0D, 90D, low);
        up.point(10, 0, 0D, 90D, high);
        check("move 让粒子往上飘", high[1] - low[1] > 3D);

        // —— 配置里的默认值就是 CMI 那三串
        final Configuration cfg = new Probe(map).unwrap();
        check("配置里倒计时特效默认开着", cfg.particleCountdown() != null);
        check("配置里出发特效默认开着", cfg.particleDepart() != null);
        check("配置里落地特效默认开着", cfg.particleArrive() != null);
        check("配置里打断特效默认关着", cfg.particleCancel() == null);
        check("粒子总开关默认开", cfg.particlesEnabled());
        check("默认只发给装了桥接的服", cfg.particlesRequireBridge());
        check("出发/落地默认 15 tick", cfg.departTicks() == 15 && cfg.arriveTicks() == 15);
        check("默认配置没有解析问题", cfg.particleProblems().isEmpty());

        // —— OP_FX 编解码（三种子类型都要能原样还原）
        final UUID uuid = UUID.randomUUID();
        final Wire.Packet follow = Wire.read(Wire.effectFollow(uuid, warmup, 70));
        check("FX_FOLLOW 解出 opcode", follow.op() == Wire.OP_FX);
        check("FX_FOLLOW 解出子类型", follow.sub() == Wire.FX_FOLLOW);
        check("FX_FOLLOW 带上玩家 UUID", uuid.equals(follow.uuid()));
        check("FX_FOLLOW 预设串原样传到子服", warmup.equals(follow.text()));
        check("FX_FOLLOW 时长 70 tick", follow.ticks() == 70);

        final Wire.Loc at = new Wire.Loc("world", 1.5D, 64D, -3.25D, 0F, 0F);
        final Wire.Packet stat = Wire.read(Wire.effectStatic(at, "circle;effect:flame;part:5", 15));
        check("FX_STATIC 解出子类型", stat.sub() == Wire.FX_STATIC);
        check("FX_STATIC 带着坐标", stat.loc() != null && stat.loc().x() == 1.5D
                && stat.loc().z() == -3.25D);
        check("FX_STATIC 世界名不丢", "world".equals(stat.loc().world()));
        check("FX_STATIC 预设串不丢", "circle;effect:flame;part:5".equals(stat.text()));

        final Wire.Packet stop = Wire.read(Wire.effectStop(uuid));
        check("FX_STOP 解出子类型", stop.sub() == Wire.FX_STOP);
        check("FX_STOP 带上玩家 UUID", uuid.equals(stop.uuid()));

        // —— 老 opcode 不能被这次改动搞坏
        final Wire.Packet tp = Wire.read(Wire.teleport(uuid, at));
        check("OP_TP 没被粒子改动影响", tp.op() == Wire.OP_TP && tp.loc().y() == 64D);
        check("OP_PING 仍然能解", Wire.read(Wire.ping()).op() == Wire.OP_PING);
        check("坏包返回 null 而不是抛异常", Wire.read(new byte[0]) == null
                && Wire.read(null) == null);

        // —— 1.1.0 新增的四个 opcode（移动监视 + 声音）
        final Wire.Packet watch = Wire.read(Wire.watch(uuid, 0.6D, Wire.WATCH_IGNORE_Y));
        check("WATCH 解出 opcode", watch.op() == Wire.OP_WATCH);
        check("WATCH 带上玩家 UUID", uuid.equals(watch.uuid()));
        check("WATCH 容差原样传到子服", watch.amount() == 0.6D);
        check("WATCH 的 ignoreY 标记不丢", (watch.sub() & Wire.WATCH_IGNORE_Y) != 0);

        final Wire.Packet unwatch = Wire.read(Wire.unwatch(uuid));
        check("UNWATCH 解出 opcode", unwatch.op() == Wire.OP_UNWATCH);
        check("UNWATCH 带上玩家 UUID", uuid.equals(unwatch.uuid()));

        final Wire.Packet moved = Wire.read(Wire.moved(uuid, at));
        check("MOVED 解出 opcode", moved.op() == Wire.OP_MOVED);
        check("MOVED 带上当前坐标", moved.loc() != null && moved.loc().x() == 1.5D);

        final Wire.Packet sound = Wire.read(Wire.sound(uuid, "block_anvil_land:0.5:2"));
        check("SOUND 解出 opcode", sound.op() == Wire.OP_SOUND);
        check("SOUND 的声音串不丢", "block_anvil_land:0.5:2".equals(sound.text()));
        check("SOUND 带上玩家 UUID", uuid.equals(sound.uuid()));

        // —— 版本协商：老桥接不能假装支持新协议
        check("1.1.0 支持子服端移动检测", Backend.atLeast("1.1.0", 1, 1, 0));
        check("1.0.0 不支持（要退回轮询）", !Backend.atLeast("1.0.0", 1, 1, 0));
        check("1.2.0 也算支持", Backend.atLeast("1.2.0", 1, 1, 0));
        check("2.0.0 也算支持", Backend.atLeast("2.0.0", 1, 1, 0));
        check("版本号认不出来时保守当不支持", !Backend.atLeast("", 1, 1, 0)
                && !Backend.atLeast(null, 1, 1, 0));
        check("带后缀的版本号能解（1.1.0-SNAPSHOT）", Backend.atLeast("1.1.0-SNAPSHOT", 1, 1, 0));
    }

    private static Map<String, Object> blacklistMap() {
        return TomlLite.parse("[servers]\nmode = \"blacklist\"\nlist = [\"bedwars\"]\n");
    }

    private static Map<String, Object> whitelistMap() {
        return TomlLite.parse("[servers]\nmode = \"whitelist\"\nlist = [\"lobby\"]\n");
    }

    /** 反射造 Configuration（构造器是私有的，测试里绕一下）。 */
    static final class Probe {
        private final Configuration value;

        Probe(final Map<String, Object> map) throws Exception {
            final java.lang.reflect.Constructor<Configuration> ctor =
                    Configuration.class.getDeclaredConstructor(Map.class);
            ctor.setAccessible(true);
            this.value = ctor.newInstance(map);
        }

        Configuration unwrap() {
            return value;
        }
    }
}
