package cn.shijiu.vtpa;

import java.util.UUID;

/**
 * 一条传送请求。
 *
 * <p>只存纯数据（UUID、名字、时间），不存 Velocity 的 {@code Player} 对象 ——
 * 一是掉线了引用会失效，二是这样 {@link RequestStore} 可以脱离 Velocity 单独跑测试。
 */
public final class TpaRequest {

    private final UUID requesterId;
    private final String requesterName;
    private final UUID targetId;
    private final String targetName;
    private final RequestType type;
    private final long createdAt;
    private final long expiresAt;

    public TpaRequest(final UUID requesterId, final String requesterName,
                      final UUID targetId, final String targetName,
                      final RequestType type, final long createdAt, final long expiresAt) {
        this.requesterId = requesterId;
        this.requesterName = requesterName;
        this.targetId = targetId;
        this.targetName = targetName;
        this.type = type;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID requesterId() {
        return requesterId;
    }

    public String requesterName() {
        return requesterName;
    }

    public UUID targetId() {
        return targetId;
    }

    public String targetName() {
        return targetName;
    }

    public RequestType type() {
        return type;
    }

    public long createdAt() {
        return createdAt;
    }

    public long expiresAt() {
        return expiresAt;
    }

    public boolean isExpired(final long now) {
        return now >= expiresAt;
    }

    /** 还剩多少毫秒失效（已经过期就是 0）。 */
    public long remainingMillis(final long now) {
        return Math.max(0L, expiresAt - now);
    }

    /** 还剩多少秒失效（向上取整，用于提示语）。 */
    public long remainingSeconds(final long now) {
        return (remainingMillis(now) + 999L) / 1000L;
    }

    /** 另一个当事人：给发起者看就是目标，给目标看就是发起者。 */
    public UUID other(final UUID me) {
        return requesterId.equals(me) ? targetId : requesterId;
    }

    /** 另一个当事人的名字。 */
    public String otherName(final UUID me) {
        return requesterId.equals(me) ? targetName : requesterName;
    }

    /**
     * 真正<b>被搬走</b>的那个人 —— {@code /tpa} 搬发起者，{@code /tpahere} 搬被请求者。
     *
     * <p>判断「两条请求结果是不是一样」全靠它和 {@link #destinationId()}：
     * 方向和发起者都可能不同，但动的人、落点都一样的话，玩家那边看到的结果是一模一样的。
     */
    public UUID moverId() {
        return type.movesRequester() ? requesterId : targetId;
    }

    /** 落点：被搬去谁那儿。 */
    public UUID destinationId() {
        return type.movesRequester() ? targetId : requesterId;
    }

    public String moverName() {
        return type.movesRequester() ? requesterName : targetName;
    }

    public String destinationName() {
        return type.movesRequester() ? targetName : requesterName;
    }

    /**
     * 另一条请求跟这条的<b>结果是不是一模一样</b>：动的是同一个人、落点也是同一个人。
     *
     * <pre>
     *   A /tpa B     → 动 A、落点 B
     *   B /tpahere A → 动 A、落点 B     ✅ 一样（这就是「互相请求」）
     *
     *   A /tpa B     → 动 A、落点 B
     *   B /tpa A     → 动 B、落点 A     ❌ 相反（一个想去对方那儿，不是一回事）
     * </pre>
     */
    public boolean sameOutcomeAs(final TpaRequest other) {
        return other != null
                && moverId().equals(other.moverId())
                && destinationId().equals(other.destinationId());
    }

    @Override
    public String toString() {
        return type + " " + requesterName + " -> " + targetName;
    }
}
