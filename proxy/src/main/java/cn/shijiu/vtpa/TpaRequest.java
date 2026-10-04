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

    @Override
    public String toString() {
        return type + " " + requesterName + " -> " + targetName;
    }
}
