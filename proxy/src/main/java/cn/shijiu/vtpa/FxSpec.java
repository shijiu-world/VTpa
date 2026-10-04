package cn.shijiu.vtpa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * CMI 风格粒子预设串的解析器（纯逻辑，不碰 Velocity 也不碰 Bukkit）。
 *
 * <p>语法跟 CMI 的 {@code Settings/ParticleEffects.yml} 一致，用 {@code ;} 分隔，
 * 每段是 {@code 键:值} 或者一个单独的关键字。所以线上 CMI 里现成那一串
 * （{@code circle;effect:flying_glyph;dur:5;pitchc:15;part:10;offset:0,1.7,0;radius:0.5;yawc:12;color:rs;pitch:90}）
 * 可以直接原样粘进 VTpa 的 config.toml 里用。
 *
 * <p>支持的键：
 * <pre>
 *   effect/ef      粒子名（flying_glyph / reddust / heart / crit / flame / portal …）
 *   c / color      颜色，"r,g,b" 或 "rs"（彩虹，随时间转色）
 *   part/particles 每一圈撒多少个粒子
 *   radius/r       半径（格）
 *   rc/radiuschange 每 tick 半径变化
 *   mr/maxradius   半径上限（配合 rc 用）
 *   offset/off     相对圆心的偏移 "x,y,z"
 *   move           每 tick 的漂移 "x,y,z"（做出向上冲的光柱）
 *   pitch/yaw/angle 环的朝向（度）。pitch:90 = 水平环
 *   pitchc/yawc    每 tick 转多少度（环转起来）
 *   dur/duration   预设自带的时长（秒）
 *   interval/tickinter 每几 tick 撒一次
 *   关键字          circle（形状，默认） / twist（扭转） / static|fixed（钉死不跟人）
 *                   hwv|hidewhenvanished（隐身时不播） / raindbow（同 color:rs）
 * </pre>
 *
 * <p>⚠️ 解析是**宽容**的：某一个参数写坏了只跳过这个参数、把问题记进
 * {@link #problems()}，不会让整条预设失效 —— 宁可效果差点，也不能一个字符写错就没特效。
 * 空串 / null 返回 {@code null}，表示「不播」。
 *
 * <p>⚠️ 这个文件在 bridge 模块里有一份拷贝（改了要同步过去），跟 Wire 一个套路。
 */
public final class FxSpec {

    /** 颜色怎么来。 */
    public enum ColorMode {
        /** 不指定颜色，用粒子自己的默认色。 */
        NONE,
        /** 固定 RGB。 */
        FIXED,
        /** 彩虹，颜色随时间和粒子序号转。 */
        RAINBOW
    }

    private static final double TAU = Math.PI * 2D;

    /** 原始预设串 —— 代理不解析粒子，只是把这串原样发给子服去播。 */
    private final String raw;
    private String particle = "flying_glyph";
    private String shape = "circle";
    private int count = 10;
    private double radius = 0.5D;
    private double radiusChange;
    private double maxRadius;
    private final double[] offset = new double[3];
    private final double[] move = new double[3];
    private double pitch;
    private double yaw;
    private double pitchChange;
    private double yawChange;
    private boolean followYaw;
    private boolean followPitch;
    private boolean twist;
    private boolean fixed;
    private boolean hideWhenVanished;
    private int interval = 1;
    private int durationTicks = 20;
    private ColorMode colorMode = ColorMode.NONE;
    private int red = 255;
    private int green = 255;
    private int blue = 255;
    private final List<String> problems = new ArrayList<>();

    private FxSpec(final String raw) {
        this.raw = raw;
    }

    /**
     * 解析一条预设串。
     *
     * @param raw CMI 风格的预设串；null / 空白返回 null（= 不播特效）
     */
    public static FxSpec parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        final FxSpec spec = new FxSpec(raw.trim());
        for (final String token : raw.split(";")) {
            final String piece = token.trim();
            if (piece.isEmpty()) {
                continue;
            }
            final int colon = piece.indexOf(':');
            if (colon < 0) {
                spec.keyword(piece);
                continue;
            }
            spec.option(piece.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    piece.substring(colon + 1).trim());
        }
        if (spec.count <= 0) {
            spec.count = 1;
        }
        if (spec.interval < 1) {
            spec.interval = 1;
        }
        if (spec.durationTicks < 1) {
            spec.durationTicks = 1;
        }
        return spec;
    }

    private void keyword(final String word) {
        switch (word.toLowerCase(Locale.ROOT)) {
            case "circle":
            case "sphere":
            case "ring":
                shape = word.toLowerCase(Locale.ROOT);
                break;
            case "twist":
                twist = true;
                break;
            case "static":
            case "fixed":
            case "locked":
                fixed = true;
                break;
            case "raindbow":
            case "rainbow":
            case "rfs":
                colorMode = ColorMode.RAINBOW;
                break;
            case "hwv":
            case "hidewhenvanished":
                hideWhenVanished = true;
                break;
            default:
                // 认不出来的关键字直接忽略 —— 别因为多写了个词就把特效废了
                break;
        }
    }

    private void option(final String key, final String value) {
        switch (key) {
            case "effect":
            case "ef":
                particle = value.toLowerCase(Locale.ROOT);
                break;
            case "part":
            case "particles":
                count = integer(value, count);
                break;
            case "radius":
            case "r":
                radius = Math.max(0D, decimal(value, radius));
                break;
            case "rc":
            case "radiuschange":
                radiusChange = decimal(value, radiusChange);
                break;
            case "mr":
            case "maxradius":
                maxRadius = decimal(value, maxRadius);
                break;
            case "offset":
            case "off":
                vector(value, offset);
                break;
            case "move":
                vector(value, move);
                break;
            case "pitch":
                applyAngle(value, true);
                break;
            case "yaw":
            case "angle":
            case "a":
                applyAngle(value, false);
                break;
            case "pitchc":
            case "pitchchange":
                pitchChange = decimal(value, pitchChange);
                break;
            case "yawc":
                yawChange = decimal(value, yawChange);
                break;
            case "dur":
            case "duration":
                // CMI 里是秒，这里换成 tick（-1 表示无限，等调用方自己掐掉）
                final double seconds = decimal(value, 2D);
                durationTicks = seconds < 0D ? -1 : (int) Math.max(1D, Math.round(seconds * 20D));
                break;
            case "interval":
            case "tickinter":
                interval = integer(value, interval);
                break;
            case "c":
            case "c1":
            case "color":
                applyColor(value);
                break;
            default:
                break;
        }
    }

    /** yaw / pitch 允许写成 {@code [playerName]} 或 {@code player}，意思是跟着玩家的朝向转。 */
    private void applyAngle(final String value, final boolean isPitch) {
        final String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("player")) {
            if (isPitch) {
                followPitch = true;
            } else {
                followYaw = true;
            }
            return;
        }
        final double degrees = decimal(value, isPitch ? pitch : yaw);
        if (isPitch) {
            pitch = degrees;
        } else {
            yaw = degrees;
        }
    }

    private void applyColor(final String value) {
        final String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("rs") || lower.equals("rainbow") || lower.equals("raindbow")) {
            colorMode = ColorMode.RAINBOW;
            return;
        }
        final String[] parts = value.split(",");
        if (parts.length < 3) {
            problems.add("颜色写法不对（要 r,g,b）：" + value);
            return;
        }
        try {
            red = clamp(Integer.parseInt(parts[0].trim()));
            green = clamp(Integer.parseInt(parts[1].trim()));
            blue = clamp(Integer.parseInt(parts[2].trim()));
            colorMode = ColorMode.FIXED;
        } catch (final NumberFormatException e) {
            problems.add("颜色不是数字：" + value);
        }
    }

    private static int clamp(final int value) {
        return Math.max(0, Math.min(255, value));
    }

    private void vector(final String value, final double[] target) {
        final String[] parts = value.split(",");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                target[i] = Double.parseDouble(parts[i].trim());
            } catch (final NumberFormatException e) {
                problems.add("向量里有不是数字的：" + value);
                return;
            }
        }
    }

    private int integer(final String value, final int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (final NumberFormatException e) {
            problems.add("这里该填整数：" + value);
            return fallback;
        }
    }

    private double decimal(final String value, final double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (final NumberFormatException e) {
            problems.add("这里该填数字：" + value);
            return fallback;
        }
    }

    // ------------------------------------------------------------------
    // 取值
    // ------------------------------------------------------------------

    /** 原始预设串（发给子服用的就是这个）。 */
    public String raw() {
        return raw;
    }

    public String particle() {
        return particle;
    }

    public String shape() {
        return shape;
    }

    public int count() {
        return count;
    }

    public double radius() {
        return radius;
    }

    public double radiusChange() {
        return radiusChange;
    }

    public double maxRadius() {
        return maxRadius;
    }

    public double[] offset() {
        return offset;
    }

    public double[] move() {
        return move;
    }

    public double pitch() {
        return pitch;
    }

    public double yaw() {
        return yaw;
    }

    public double pitchChange() {
        return pitchChange;
    }

    public double yawChange() {
        return yawChange;
    }

    /** yaw 要跟着玩家的朝向走吗（预设里写的 {@code yaw:[playerName]}）。 */
    public boolean followYaw() {
        return followYaw;
    }

    public boolean followPitch() {
        return followPitch;
    }

    public boolean twist() {
        return twist;
    }

    /** true = 钉在世界坐标上，不跟着玩家动（玩家跑了特效还留在原地）。 */
    public boolean fixed() {
        return fixed;
    }

    public boolean hideWhenVanished() {
        return hideWhenVanished;
    }

    public int interval() {
        return interval;
    }

    public int durationTicks() {
        return durationTicks;
    }

    public ColorMode colorMode() {
        return colorMode;
    }

    public int red() {
        return red;
    }

    public int green() {
        return green;
    }

    public int blue() {
        return blue;
    }

    /** 解析过程中攒下的问题（参数写坏了之类）；没有就是空 list。 */
    public List<String> problems() {
        return Collections.unmodifiableList(problems);
    }

    // ------------------------------------------------------------------
    // 算点
    // ------------------------------------------------------------------

    /**
     * 算第 {@code index} 个粒子相对**圆心**的偏移（不含 {@link #offset()}，
     * 那个由调用方加到圆心上）。
     *
     * @param tick    第几 tick（从 0 开始），用来算旋转和漂移
     * @param index   第几个粒子（0 … {@link #count()}-1）
     * @param yawDeg  这一刻的 yaw（度）——跟随玩家时由调用方把玩家朝向传进来
     * @param pitchDeg 这一刻的 pitch（度）
     * @param out     长度 ≥3 的数组，结果写进去（省得每 tick 造对象）
     */
    public void point(final int tick, final int index, final double yawDeg, final double pitchDeg,
                      final double[] out) {
        double r = radius + radiusChange * tick;
        if (maxRadius > 0D) {
            r = Math.min(r, maxRadius);
        }
        r = Math.max(0D, r);

        // twist：让相邻粒子错开一点相位，环就变成了螺旋（CMI 的 twist 就是这个效果）
        double theta = TAU * index / Math.max(1, count);
        if (twist) {
            theta += index * (Math.PI / Math.max(1, count));
        }

        // 局部坐标：先在 XY 平面上画个圆，再按 pitch / yaw 转过去
        double x = r * Math.cos(theta);
        double y = r * Math.sin(theta);
        double z = 0D;

        final double pitchRad = Math.toRadians(pitchDeg);
        final double cosP = Math.cos(pitchRad);
        final double sinP = Math.sin(pitchRad);
        double y1 = y * cosP - z * sinP;
        double z1 = y * sinP + z * cosP;

        final double yawRad = Math.toRadians(yawDeg);
        final double cosY = Math.cos(yawRad);
        final double sinY = Math.sin(yawRad);
        out[0] = x * cosY + z1 * sinY;
        out[1] = y1;
        out[2] = -x * sinY + z1 * cosY;

        // move：每 tick 往外飘一点 —— 向上就是 "0,0.33,0" 那种冲天光柱
        if (move[0] != 0D || move[1] != 0D || move[2] != 0D) {
            out[0] += move[0] * tick;
            out[1] += move[1] * tick;
            out[2] += move[2] * tick;
        }
    }

    /** 这一刻这个粒子该是什么颜色（RGB 打包成 0xRRGGBB）。 */
    public int color(final int tick, final int index) {
        if (colorMode == ColorMode.FIXED) {
            return (red << 16) | (green << 8) | blue;
        }
        if (colorMode == ColorMode.RAINBOW) {
            return hsb((tick * 0.02D) + (index * 0.08D));
        }
        return 0xFFFFFF;
    }

    /** HSB → RGB，彩虹色用（不引 java.awt，免得在无头环境里出幺蛾子）。 */
    private static int hsb(final double hueRaw) {
        final double hue = ((hueRaw % 1D) + 1D) % 1D;
        final double h = hue * 6D;
        final int sector = (int) Math.floor(h);
        final double f = h - sector;
        final int q = (int) Math.round(255D * (1D - f));
        final int t = (int) Math.round(255D * f);
        switch (sector) {
            case 0:
                return (255 << 16) | (t << 8) | 0;
            case 1:
                return (q << 16) | (255 << 8) | 0;
            case 2:
                return (0 << 16) | (255 << 8) | t;
            case 3:
                return (0 << 16) | (q << 8) | 255;
            case 4:
                return (t << 16) | (0 << 8) | 255;
            default:
                return (255 << 16) | (0 << 8) | q;
        }
    }

    @Override
    public String toString() {
        return particle + " x" + count + " r" + radius;
    }
}
