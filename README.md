# VTpa —— 跨服传送请求（/tpa /tpahere /tpaall）

给「拾玖世界」群组服写的跨服 TPA。玩家在任何子服都能给任何子服的玩家发传送请求，
对方点一下聊天里的 **[接受]** 就能把人传过去（带 3 秒倒计时，动了就作废）。

---

## ⚠️ 先说清楚：为什么是两个 jar

| jar | 装在哪 | 干什么 |
| --- | --- | --- |
| `VTpa-1.1.0.jar` | **代理（Velocity）** | 命令、请求账本、倒计时、子服名单、提示语、切服 |
| `VTpaBridge-1.1.0.jar` | **每个要用 TPA 的子服** | 报坐标、落地传送、播粒子与声音、**实时盯移动** |

⚠️ 两个 jar **版本要配套**：移动检测是 1.1.0 起才有的协议。子服还停在 1.0.0 时
不会报错，只是自动退回代理轮询（日志会提示「版本偏老」），但判定会慢半拍。

**代理拿不到坐标，也挪不动人** —— Velocity 只知道「谁在哪个服」。它能做的只有
把玩家从 A 服切到 B 服（落到 B 服的出生点）。要精确到「传送到张三脚下」，必须有
子服那一半帮忙。所以：

| 情况 | 效果 |
| --- | --- |
| 两边都装了 | 完整功能：跨服也能传到对方脚下 |
| 只有代理端 | 跨服只能切过去落出生点（配置 `bridge.missing = "switch"`），同服则直接拒绝 |
| 只有子服端 | 没用，桥接只是被动应答 |

两个 jar 之间用插件消息通信（默认通道 `vtpa:main`），**不需要**装 LuckPerms、
PAPI、PAPIProxyBridge 任何东西，也不依赖数据库。

---

## 安装

```bash
# 1. 构建
./build.sh          # 或：JAVA_HOME=... mvn -B -o package

# 2. 丢 jar
proxy/target/VTpa-1.1.0.jar          → 代理的 plugins/
bridge/target/VTpaBridge-1.1.0.jar   → 每个要参与的子服的 plugins/

# 3. 重启（子服和代理都要重启），会自动生成 plugins/vtpa/config.toml
# 4. 改配置后 /vtpa reload（需要 vtpa.reload）
```

⚠️ **代理端会接管 `/tpa` `/tpahere` `/tpaccept` `/tpadeny` 这些短命令**，
子服里 CMI 自带的同名命令会被完全盖掉（Velocity 上谁注册命令谁说了算）。
带前缀的写法（`/cmi tpa`）不受影响。不想接管哪条就把 `config.toml` 里
`[shortcuts]` 对应的那一行删掉（改 `[shortcuts]` 要重启代理）。

---

## 命令

顶层短命令（默认全部接管）：

| 命令 | 作用 |
| --- | --- |
| `/tpa <玩家>` | 请求**传送到对方那里**（动的是我） |
| `/tpahere <玩家>` | 请求**对方传送到我这儿**（动的是对方） |
| `/tpaall` | 请求**全服在线玩家**传送到我这儿（要 `vtpa.all` 权限） |
| `/tpaccept [玩家]` | 接受请求。有多条待处理时不写名字会让你选一个 |
| `/tpadeny [玩家]` | 拒绝请求 |
| `/tpacancel [玩家]` | 撤回我发出去的请求（不用干等 3 分钟） |
| `/tpatoggle [on\|off]` | 关掉 / 恢复「接收别人的请求」 |

主命令（这些在上面没有短命令）：

| 命令 | 作用 |
| --- | --- |
| `/vtpa reload` | 重载配置（别名 `/vt`，可在 `[commands] root` 改） |
| `/vtpa version` | 显示版本 |
| `/vtpa help` | 帮助 |

用法示例：

```
/tpa 小明                     → 小明收到 "[接受] [拒绝]"，点了之后我这边倒数 3 秒传过去
/tpaccept 小明                → 只接受小明发来的那一条
/tpacancel                    → 只挂了一条，直接撤回
```

### 权限

```
vtpa.use        发请求 / 接受 / 拒绝（默认人人有）
vtpa.here       /tpahere
vtpa.accept     /tpaccept
vtpa.deny       /tpadeny
vtpa.cancel     /tpacancel
vtpa.toggle     /tpatoggle
vtpa.all        🔴 /tpaall —— 必须显式给，allow-by-default 对它无效
vtpa.reload     /vtpa reload
vtpa.toggle.bypass    能发给关掉接收的人
vtpa.cooldown.bypass  不受发起冷却
vtpa.server.bypass    不受子服黑白名单
vtpa.limit.bypass     不受「最多挂几个请求」限制
vtpa.move.bypass      倒计时期间动一下也不取消
```

```bash
/lp group default permission set vtpa.use true
/lp group vip   permission set vtpa.all true
/lp group admin permission set vtpa.* true
```

---

## 请求的一生（以及所有会拦它的情况）

```
/tpa 小明
  ↓ 检查：不是自己 → 没在冷却 → 双方子服都在名单里 → 允许跨服/同服
         → 小明没关接收 → 两人之间没有未处理的请求 → 我挂着的请求没超上限
         → 小明没在传送中 → 落点那个服装了桥接
  ↓
小明收到："小明 请求传送到你这里! [接受] [拒绝]"
  ↓
小明点 [接受]（或敲 /tpaccept）
  ↓
我这边开始 3 秒倒计时，屏幕中央显示 3 / 2 / 1
  ↓ 每秒检查：我还在线？没换服？没动超过 0.6 格？
  ↓
倒计时结束 → 问小明当前坐标 → 把我传过去（跨服就先切服再落地）
```

**请求会在这些时候完结**（完结之后可以立刻给同一个人发新的）：
被接受 / 被拒绝 / 发起者 `/tpacancel` / 超时（默认 180 秒）/ 任意一方掉线。

### 完整边界情况清单

| 情况 | 处理 |
| --- | --- |
| 发给自己 | 提示「不能向自己发送」 |
| 名字打错 / 有多个人匹配 | 「找不到在线玩家」/「有多个人名匹配，多打几个字」 |
| 对方还在登录中（没进任何服） | 「还在登录中，等他进服了再试」 |
| 冷却中 | 「慢一点，还要 N 秒」（有 bypass 权限的不受影响） |
| 自己或对方在不允许的子服 | 「你所在的服务器（xxx）不能使用传送请求」/「xxx 所在的服务器不能使用传送请求」 |
| 关了跨服 / 同服 | 「没有开启跨服传送请求」/「没有开启同服传送请求」 |
| 对方关了接收 | 「对方关掉了传送请求」（有 toggle.bypass 的可强发） |
| 两人之间已经挂着请求 | 「你已经给他发过请求了，还剩 N 秒」或「他已经给你发过一个了」 |
| 我挂着的请求超过上限 | 「你挂着的请求太多了」 |
| 对方 / 我正在倒计时 | 「对方正在传送倒计时中」/「你正在传送倒计时中」 |
| 落点那个服没装桥接 | 按 `bridge.missing` 处理：拒绝，或跨服只切服（会提示） |
| 3 分钟没人理 | 自动作废，按 `notify-on-expire` 通知双方 |
| 请求挂着时某人掉线 | 自动作废，按 `notify-on-disconnect` 通知另一方 |
| 接受时对方已下线 | 「对方已经下线，传送请求作废」 |
| 接受时某一方换到了名单外的服 | 再查一次名单，拦下来 |
| 倒计时期间移动超过容差 | 取消，双方都收到提示 |
| 倒计时期间换服 / 掉线 | 取消 |
| 倒计时期间换了世界 | 取消（可关 `movement.cancel-on-world-change`） |
| 传送瞬间对方掉线 | 「对方已经下线」 |
| 跨服切服失败（服满 / 被拦） | 「传送失败：<状态>」，落点作废，不会乱传 |
| 切服最终落到别的服 | 落点作废并打 WARN，不会把坐标用在错误的服上 |
| 点了按钮但请求已经没了 | 「你没有待处理的传送请求」 |
| `/tpaall` 遇到不合适的人 | 静默跳过，最后只回一条「发出 N 条，跳过 M 名」 |
| 控制台敲 /tpa | 「这条命令只有玩家能用」 |
| config.toml 写错了 | **保留旧配置**并 WARN，绝不让插件变成半成品 |

---

## 配置（`plugins/vtpa/config.toml`）

### 1. 子服名单

```toml
[servers]
mode = "blacklist"    # blacklist = 名单里的不能用；whitelist = 只有名单里的能用
list = ["bedwars"]    # 服名写 velocity.toml [servers] 里的名字，大小写不敏感
```

判定是**双向**的：发起者所在的服和目标所在的服都得通过。只要有一边不在名单里，
请求就发不出去，并且有对应的提示语（`messages.server-denied-self` /
`messages.server-denied-target`）。有 `vtpa.server.bypass` 的人不受限制。

### 2. 请求文本 + 按钮（你要的那个）

```toml
[request]
tpa  = "&e%player% &7请求传送到你这里! #accept# #deny#"
here = "&e%player% &7请求你传送到他那里! #accept# #deny#"

[buttons]
accept-text    = "&a&l[接受]"
accept-hover   = "&a点击接受 &e%player% &a的传送请求\n&7也可以自己敲 /tpaccept %player%"
accept-command = "/tpaccept %player%"
deny-text      = "&c&l[拒绝]"
deny-hover     = "&c点击拒绝 &e%player% &c的传送请求\n&7也可以自己敲 /tpadeny %player%"
deny-command   = "/tpadeny %player%"
cancel-text    = "&e&l[撤回]"
cancel-hover   = "&e点一下撤回发给 %player% 的请求"
cancel-command = "/tpacancel %player%"

[messages]
request-sent = "&e请求已发送给 &6#target# #cancel#"
```

- `%player%`（也认 `#player#`）= 发起者名字；`%server%` = 发起者所在服；`%time%` = 剩余秒数
- `#accept#` / `#deny#` 会变成可点击组件，**位置随便放**，写几个就出现几个
- `#cancel#` 是发给**发起者**那条「已发送请求」里的撤回按钮 —— 点了等于自己敲
  `/tpacancel 对方`。⚠️ 那条消息里 `%player%` 指的是**被请求的人**（撤回得说清楚撤回给谁的）
- `*-command` 里带 `%player%`，所以按钮点的永远是 `/tpaccept 小明` 这种**带名字**的，
  同时有多个请求也绝不会点错
- `*-hover` 里写 `\n` 换行
- **任何一条文本配成空串（`""`）就等于「这条别发」**，连 `messages.prefix` 都不会带 ——
  不会发出一条孤零零的 `[传送] `。只剩颜色码（比如 `&c`）也算空

### 3. 时效 / 倒计时 / 移动检测

```toml
[general]
request-timeout-seconds = 180      # 请求 3 分钟没人理就作废
teleport-delay-seconds  = 3        # 同意后倒数 3 秒
cooldown-seconds        = 5        # 两次发起之间隔 5 秒，0 = 不限
max-outgoing-requests   = 3        # 一个人最多同时挂 3 个未处理的
allow-cross-server      = true
allow-same-server       = true
notify-on-expire        = true     # 过期时通知双方
notify-on-disconnect    = true     # 掉线时通知另一方

[countdown]
enabled = true
mode    = "title"                  # title（屏幕中央大字）/ actionbar / both / none
title     = "&b#seconds#"
subtitle  = "&7正在传送，请不要移动"
actionbar = "&b#seconds# &7秒后传送到 &e#player# &7那里，请不要移动"

[movement]
enabled               = true
backend-detection     = true        # 让子服盯（实时、准，跟 CMI 一个路子）
tolerance             = 0.6        # 位移超过多少格算「动了」
ignore-y              = true        # 只算水平距离：原地跳一下不算动
poll-interval-millis  = 250        # 桥接太老时退回轮询，多久问一次坐标
cancel-on-world-change = true
```

**移动检测放在子服做**（`backend-detection = true`）：子服监听 `PlayerMoveEvent`，
玩家每动一下立刻就知道；代理端轮询是每 250 毫秒问一次坐标、还得等一次网络往返，慢半拍。
CMI 也是这么干的。子服桥接版本 < 1.1.0 时会自动退回轮询，日志里会说。

⚠️ 子服没装桥接时这一项自动失效（等于不检测）—— 宁可不拦，也不能把站着不动的人判成动了。

### 3.5 声音（移植自 CMI 的 `Sounds:` 段）

格式跟 CMI 一模一样：`名字:音量:音调`。下面这几个默认值**就是线上 CMI 现在用的**。

```toml
[sounds]
enabled        = true
request        = "block_anvil_land:0.5:2"          # 对方收到请求时（播给被请求的人）
countdown      = "blockrespawnanchorcharge:1:1"    # 倒计时开始（播给被传送的人）
countdown-tick = "blockrespawnanchorcharge:1:1"    # 倒计时每过一秒滴一下
depart         = "entity_enderman_teleport:2:1"    # 出发那一瞬间
arrive         = "entity_enderman_teleport:0.2:1"  # 落地那一瞬间
cancel         = ""                                # 倒计时被打断
deny           = ""                                # 请求被拒绝（播给发起者）
fail           = "entity_villager_no:2:1"          # 传送失败
```

写空串就是不播。声音名**运行时解析**（跟粒子名一样），1.20.5 之后改过名的老名字也认；
认不出来只记一条警告、跳过，**不影响传送**。

### 4. 粒子特效（移植自 CMI 的 tpaWarmup / TeleportEffects）

```toml
[particles]
enabled = true
require-bridge = true    # 只给确认装了桥接的子服发（没装就静默跳过）

# 倒计时期间在被传送者身上一直转的那圈 —— 下面这串就是 CMI 里 tpaWarmup 的原值
countdown = "circle;effect:flying_glyph;dur:5;pitchc:15;part:10;offset:0,1.7,0;radius:0.5;yawc:12;color:rs;pitch:90"
# 传送瞬间在出发地撒（CMI 的 TpUp）
depart    = "circle;c:200,50,210;twist;part:5;r:0.5;pitch:90;move:0,0.33,0;offset:0,-0.2,0"
# 落地瞬间在落点撒（CMI 的 TpDown）
arrive    = "circle;c:150,50,10;part:5;r:0.5;pitch:90;move:0,-0.33,0;offset:0,2.2,0"
# 倒计时被打断（移动 / 取消 / 失败）时撒一下；空串 = 不播
cancel    = ""

depart-ticks = 15        # 20 tick = 1 秒。countdown 不用管，自动按倒计时秒数算
arrive-ticks = 15
cancel-ticks = 10
max-per-player = 4
```

**写法跟 CMI 的 `Settings/ParticleEffects.yml` 一致**，用 `;` 分隔，所以线上 CMI 里现成那几串
可以直接粘过来（上面三行就是 CMI 默认文件里 `tpaWarmup` / `TpUp` / `TpDown` 的原值）：

| 键 | 意思 |
| --- | --- |
| `effect` / `ef` | 粒子名。常用：`flying_glyph`（符文）、`reddust`（红石粉）、`heart`、`crit`、`flame`、`portal`、`glow`、`smoke` |
| `c` / `color` | 颜色 `r,g,b`；写 `rs` 是彩虹色（随时间转） |
| `part` | 每一圈撒几个粒子 |
| `radius` / `r` | 半径（格） |
| `rc` / `mr` | 每 tick 半径变化 / 半径上限 |
| `offset` | 相对玩家的偏移 `x,y,z`（`0,1.7,0` ≈ 头顶） |
| `move` | 每 tick 漂移（`0,0.33,0` = 往上冲的光柱） |
| `pitch` | 环的朝向，**90 = 水平环**（套在玩家身上那种） |
| `pitchc` / `yawc` | 每 tick 转多少度，环就转起来了 |
| `dur` | 时长（秒） |
| `interval` | 每几 tick 撒一次（省性能） |
| 关键字 | `twist` 扭转、`static` 钉在原地不跟人、`hwv` 隐身时不播 |

几个要点：

- **粒子是子服撒的**，代理撒不了。所以子服必须装 `VTpaBridge`，否则没特效（传送照常）。
- 1.20.5 之后 Mojang 改过粒子名（`ENCHANTMENT_TABLE→ENCHANT`、`REDSTONE→DUST`），
  这里是**运行时按别名表解析**的，CMI 那套老名字（`flying_glyph`、`reddust`…）照样能用。
- 预设串写坏了（比如 `part:abc`）不会整条失效 —— 只跳过那一个参数，
  并在**起服 / reload 的日志里**打 WARN，不会等到有人传送才发现。
- 跨服传送时：出发地特效在**旧服**撒，落地特效在**新服**撒，各管一段。

### 5. 桥接

```toml
[bridge]
channel               = "vtpa:main"   # 改了要重启，且子服同步改（子服的通道目前写死在代码里）
missing               = "switch"      # deny = 拒绝；switch = 跨服只切服（落出生点）
timeout-millis        = 1200          # 问坐标最多等多久
ping-interval-seconds = 30            # 心跳间隔，用来判断哪个服装了桥接
```

### 6. 命令名

```toml
[commands]
root = ["vt"]        # /vtpa 的别名
tpahere = ["tpask", "tphere"]
...

[shortcuts]          # 注册成顶层短命令，会盖掉子服同名的命令。改这里要重启代理
tpa = "tpa"
tpahere = "tpahere"
tpaccept = "tpaccept"
tpadeny = "tpadeny"
tpaall = "tpaall"
tpacancel = "tpacancel"
tpatoggle = "tpatoggle"
```

---

## 编译 & 测试

```bash
./build.sh        # 构建两个 jar（离线，依赖都在 ~/.m2）
./run-tests.sh    # 154 条断言：TOML / 配置 / 请求账本 / 插件消息协议 / 颜色 / 粒子预设
                  #             / 空文本不发送 / 声音 / 版本协商 / 新增的 4 个 opcode
```

要求：JDK 17+（用 `--release 17` 编，Velocity 3.4~4.x 都跑得动）、Maven 3.9。
`Wire.java` 和 `FxSpec.java` 在 proxy 和 bridge 里**各有一份一模一样的拷贝**，
改协议 / 改粒子语法两边都要改（sync 的办法见 `build.sh` 注释）。

---

## 排查

| 现象 | 看什么 |
| --- | --- |
| 一直提示「没有装 VTpaBridge」 | 代理日志里有没有 `子服 xxx 的桥接组件已就位`。没有 → 子服 jar 没装 / 通道名不一致 / 那个服还没人进去过（心跳只发给有人的服） |
| 能发请求、接受后没动静 | 看代理日志，`bridge.missing` 是不是 `deny`；或者目标服没装桥接 |
| 跨服传送后落在出生点 | 目标服没装桥接，且 `bridge.missing = "switch"` |
| 倒计时一动就取消 | `movement.tolerance` 调大，或给 `vtpa.move.bypass` |
| `/tpa` 提示命令不存在 | 被别的插件占了。代理日志会打 `快捷命令 /tpa 没注册上`，改用 `/vtpa tpa` |
| 想要子服自己的 /tpa | 把 `[shortcuts]` 里那几行删掉，重启代理 |

起服时日志会把关键配置全打一遍（时效 / 倒计时 / 名单 / 桥接策略），
先对着看一眼，省得改了半天不知道生没生效。
