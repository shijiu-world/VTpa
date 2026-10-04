package cn.shijiu.vtpa;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * 代理 ↔ 子服（VTpaBridge）的插件消息协议。
 *
 * <p>⚠️ 这份文件在 bridge 模块里有一份一模一样的拷贝（两个 jar 互相不依赖）。
 * 改了 opcode 或字段顺序，**两边都要改**，否则会解析出错。
 *
 * <p>编码：{@code [opcode:1][字段...]}，字符串用 {@code writeUTF}，坐标用 {@code double}。
 */
public final class Wire {

    /** 代理 → 子服：你在吗？ */
    public static final int OP_PING = 1;
    /** 子服 → 代理：我在（payload 里带桥接版本）。 */
    public static final int OP_PONG = 2;
    /** 代理 → 子服：告诉我这个玩家的坐标。 */
    public static final int OP_POS_REQ = 3;
    /** 子服 → 代理：这就是坐标。 */
    public static final int OP_POS_RES = 4;
    /** 子服 → 代理：这个人不在我这儿。 */
    public static final int OP_POS_NONE = 5;
    /** 代理 → 子服：把这个人传到这个坐标。 */
    public static final int OP_TP = 6;
    /** 代理 → 子服：播一段粒子特效（子命令见 {@link #FX_FOLLOW} 等）。 */
    public static final int OP_FX = 7;
    /** 代理 → 子服：盯住这个人的移动，动了就回 {@link #OP_MOVED}（1.1.0 起）。 */
    public static final int OP_WATCH = 8;
    /** 代理 → 子服：不用盯了（倒计时结束 / 被打断）（1.1.0 起）。 */
    public static final int OP_UNWATCH = 9;
    /** 子服 → 代理：这个人动了（1.1.0 起）。 */
    public static final int OP_MOVED = 10;
    /** 代理 → 子服：给这个玩家播个声音（1.1.0 起）。payload 是 CMI 那种 {@code 名字:音量:音调}。 */
    public static final int OP_SOUND = 11;

    /** 特效子类型：跟着某个玩家走（倒计时期间一直在他身上转）。 */
    public static final int FX_FOLLOW = 1;
    /** 特效子类型：钉在某个坐标上播（离开 / 落地那一下）。 */
    public static final int FX_STATIC = 2;
    /** 特效子类型：把这个人身上正在播的特效全停掉。 */
    public static final int FX_STOP = 3;

    /** {@link #OP_WATCH} 的 flags：只算水平距离（原地跳 / 被顶一下不算动）。 */
    public static final int WATCH_IGNORE_Y = 1;

    private Wire() {
    }

    /** 一个落点：世界名 + 坐标 + 朝向。 */
    public static final class Loc {
        private final String world;
        private final double x;
        private final double y;
        private final double z;
        private final float yaw;
        private final float pitch;

        public Loc(final String world, final double x, final double y, final double z,
                   final float yaw, final float pitch) {
            this.world = world == null ? "" : world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        public String world() {
            return world;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }

        public float yaw() {
            return yaw;
        }

        public float pitch() {
            return pitch;
        }

        /** 到另一个点的水平+垂直直线距离（方块）。 */
        public double distance(final Loc other) {
            final double dx = x - other.x();
            final double dy = y - other.y();
            final double dz = z - other.z();
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        /** 到另一个点的<b>水平</b>直线距离（方块，忽略高度差）。 */
        public double flatDistance(final Loc other) {
            final double dx = x - other.x();
            final double dz = z - other.z();
            return Math.sqrt(dx * dx + dz * dz);
        }

        @Override
        public String toString() {
            return world + " " + String.format("%.2f %.2f %.2f", x, y, z);
        }
    }

    /** 解出来的一包数据。用不到的字段是 null。 */
    public static final class Packet {
        private final int op;
        private final UUID uuid;
        private final Loc loc;
        private final String text;
        /** {@link #OP_FX} 的子类型（{@link #FX_FOLLOW} / {@link #FX_STATIC} / {@link #FX_STOP}）。 */
        private final int sub;
        /** {@link #OP_FX} 的播放时长（tick）；{@code FX_STOP} 时无意义。 */
        private final int ticks;
        /** {@link #OP_WATCH} 的移动容差（方块）。 */
        private final double amount;

        Packet(final int op, final UUID uuid, final Loc loc, final String text) {
            this(op, uuid, loc, text, 0, 0, 0D);
        }

        Packet(final int op, final UUID uuid, final Loc loc, final String text,
               final int sub, final int ticks) {
            this(op, uuid, loc, text, sub, ticks, 0D);
        }

        Packet(final int op, final UUID uuid, final Loc loc, final String text,
               final int sub, final int ticks, final double amount) {
            this.op = op;
            this.uuid = uuid;
            this.loc = loc;
            this.text = text;
            this.sub = sub;
            this.ticks = ticks;
            this.amount = amount;
        }

        public int op() {
            return op;
        }

        public UUID uuid() {
            return uuid;
        }

        public Loc loc() {
            return loc;
        }

        public String text() {
            return text;
        }

        public int sub() {
            return sub;
        }

        public int ticks() {
            return ticks;
        }

        public double amount() {
            return amount;
        }
    }

    // ------------------------------------------------------------------
    // 编码
    // ------------------------------------------------------------------

    public static byte[] ping() {
        return build(out -> out.writeByte(OP_PING));
    }

    public static byte[] pong(final String version) {
        return build(out -> {
            out.writeByte(OP_PONG);
            out.writeUTF(version == null ? "" : version);
        });
    }

    public static byte[] positionRequest(final UUID uuid) {
        return build(out -> {
            out.writeByte(OP_POS_REQ);
            out.writeUTF(uuid.toString());
        });
    }

    public static byte[] positionResponse(final UUID uuid, final Loc loc) {
        return build(out -> {
            out.writeByte(OP_POS_RES);
            out.writeUTF(uuid.toString());
            writeLoc(out, loc);
        });
    }

    public static byte[] positionNone(final UUID uuid) {
        return build(out -> {
            out.writeByte(OP_POS_NONE);
            out.writeUTF(uuid.toString());
        });
    }

    public static byte[] teleport(final UUID uuid, final Loc loc) {
        return build(out -> {
            out.writeByte(OP_TP);
            out.writeUTF(uuid.toString());
            writeLoc(out, loc);
        });
    }

    /** 跟着玩家走的特效：{@code [op][sub][preset][ticks][uuid]}。 */
    public static byte[] effectFollow(final UUID uuid, final String preset, final int ticks) {
        return build(out -> {
            out.writeByte(OP_FX);
            out.writeByte(FX_FOLLOW);
            out.writeUTF(preset == null ? "" : preset);
            out.writeInt(ticks);
            out.writeUTF(uuid.toString());
        });
    }

    /** 钉在坐标上的特效：{@code [op][sub][preset][ticks][loc]}。 */
    public static byte[] effectStatic(final Loc loc, final String preset, final int ticks) {
        return build(out -> {
            out.writeByte(OP_FX);
            out.writeByte(FX_STATIC);
            out.writeUTF(preset == null ? "" : preset);
            out.writeInt(ticks);
            writeLoc(out, loc);
        });
    }

    /** 停掉这个玩家身上的特效：{@code [op][sub][uuid]}。 */
    public static byte[] effectStop(final UUID uuid) {
        return build(out -> {
            out.writeByte(OP_FX);
            out.writeByte(FX_STOP);
            out.writeUTF(uuid.toString());
        });
    }

    /**
     * 开始盯这个人的移动：{@code [op][uuid][tolerance][flags]}。
     *
     * <p>子服收到时把玩家<b>当前</b>位置记成基准，之后玩家一动（超过 tolerance）
     * 就回一个 {@link #OP_MOVED}。这比代理端每隔几百毫秒问一次坐标实时得多，
     * 也是 CMI 的做法（它就在子服监听 PlayerMoveEvent）。
     */
    public static byte[] watch(final UUID uuid, final double tolerance, final int flags) {
        return build(out -> {
            out.writeByte(OP_WATCH);
            out.writeUTF(uuid.toString());
            out.writeDouble(tolerance);
            out.writeInt(flags);
        });
    }

    /** 别盯了：{@code [op][uuid]}。 */
    public static byte[] unwatch(final UUID uuid) {
        return build(out -> {
            out.writeByte(OP_UNWATCH);
            out.writeUTF(uuid.toString());
        });
    }

    /** 这个人动了：{@code [op][uuid][loc]}。 */
    public static byte[] moved(final UUID uuid, final Loc loc) {
        return build(out -> {
            out.writeByte(OP_MOVED);
            out.writeUTF(uuid.toString());
            writeLoc(out, loc);
        });
    }

    /** 给这个玩家播个声音：{@code [op][uuid][sound]}。sound 形如 {@code block_anvil_land:0.5:2}。 */
    public static byte[] sound(final UUID uuid, final String soundSpec) {
        return build(out -> {
            out.writeByte(OP_SOUND);
            out.writeUTF(uuid.toString());
            out.writeUTF(soundSpec == null ? "" : soundSpec);
        });
    }

    private static void writeLoc(final DataOutputStream out, final Loc loc) throws IOException {
        out.writeUTF(loc.world());
        out.writeDouble(loc.x());
        out.writeDouble(loc.y());
        out.writeDouble(loc.z());
        out.writeFloat(loc.yaw());
        out.writeFloat(loc.pitch());
    }

    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] build(final Writer writer) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writer.write(out);
        } catch (final IOException e) {
            // ByteArrayOutputStream 不会抛 IO 异常；真抛了就返回空包（对方解析失败会忽略）
            return new byte[0];
        }
        return bytes.toByteArray();
    }

    // ------------------------------------------------------------------
    // 解码
    // ------------------------------------------------------------------

    /** 解析一包数据；格式不对 / 版本不兼容就返回 null（调用方直接忽略）。 */
    public static Packet read(final byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            final int op = in.readByte() & 0xFF;
            switch (op) {
                case OP_PING:
                    return new Packet(op, null, null, null);
                case OP_PONG:
                    return new Packet(op, null, null, in.readUTF());
                case OP_POS_REQ:
                    return new Packet(op, UUID.fromString(in.readUTF()), null, null);
                case OP_POS_RES:
                    return new Packet(op, UUID.fromString(in.readUTF()), readLoc(in), null);
                case OP_POS_NONE:
                    return new Packet(op, UUID.fromString(in.readUTF()), null, null);
                case OP_TP:
                    return new Packet(op, UUID.fromString(in.readUTF()), readLoc(in), null);
                case OP_FX:
                    return readFx(in);
                case OP_WATCH: {
                    final UUID uuid = UUID.fromString(in.readUTF());
                    final double tolerance = in.readDouble();
                    final int flags = in.readInt();
                    return new Packet(op, uuid, null, null, flags, 0, tolerance);
                }
                case OP_UNWATCH:
                    return new Packet(op, UUID.fromString(in.readUTF()), null, null);
                case OP_MOVED:
                    return new Packet(op, UUID.fromString(in.readUTF()), readLoc(in), null);
                case OP_SOUND:
                    return new Packet(op, UUID.fromString(in.readUTF()), null, in.readUTF());
                default:
                    return null;
            }
        } catch (final Exception e) {
            return null;
        }
    }

    private static Packet readFx(final DataInputStream in) throws IOException {
        final int sub = in.readByte() & 0xFF;
        if (sub == FX_STOP) {
            return new Packet(OP_FX, UUID.fromString(in.readUTF()), null, null, sub, 0);
        }
        final String preset = in.readUTF();
        final int ticks = in.readInt();
        if (sub == FX_STATIC) {
            return new Packet(OP_FX, null, readLoc(in), preset, sub, ticks);
        }
        return new Packet(OP_FX, UUID.fromString(in.readUTF()), null, preset, sub, ticks);
    }

    private static Loc readLoc(final DataInputStream in) throws IOException {
        final String world = in.readUTF();
        final double x = in.readDouble();
        final double y = in.readDouble();
        final double z = in.readDouble();
        final float yaw = in.readFloat();
        final float pitch = in.readFloat();
        return new Loc(world, x, y, z, yaw, pitch);
    }
}
