package cn.shijiu.vtpa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传送请求的账本。
 *
 * <p>存两张表（发起者视角 / 被请求者视角），同一份 {@link TpaRequest} 对象两边都放一份，
 * 这样「我发出去了几个」和「谁在等我答复」都能 O(1) 查到，取消时也能一次摘干净。
 *
 * <p>⚠️ 全是纯数据操作，不碰 Velocity —— 可以脱离服务器单独跑单测（见 tests/SmokeTest）。
 *
 * <p>⚠️ 两层都是并发容器：写方是命令线程，读 + 删方是 {@code VTpa} 每秒一次的过期清扫，
 * 两边会同时动手。内层用 {@link ConcurrentHashMap}（丢掉插入序没关系 ——
 * 返回前 {@link #sorted} 会按 {@code createdAt} 重排），迭代时拿到的是弱一致快照，
 * 不会抛 {@code ConcurrentModificationException}，也不会把内部链表写坏导致条目丢失。
 */
public final class RequestStore {

    /** 发起者 →（目标 → 请求）。 */
    private final Map<UUID, Map<UUID, TpaRequest>> outgoing = new ConcurrentHashMap<>();
    /** 被请求者 →（发起者 → 请求）。 */
    private final Map<UUID, Map<UUID, TpaRequest>> incoming = new ConcurrentHashMap<>();

    /**
     * 查两个人之间有没有还挂着的请求 —— <b>双向都算</b>。
     *
     * <p>为什么要双向：A 给 B 发了一个还没处理的请求，B 再给 A 发一个，
     * 两边屏幕上都挂着一个「等对方答复」，非常容易让人以为对方不理自己。
     * 直接按「两个人之间只能有一个未处理的请求」处理，提示语也更说得通。
     */
    public TpaRequest findBetween(final UUID a, final UUID b) {
        final Map<UUID, TpaRequest> fromA = outgoing.get(a);
        if (fromA != null) {
            final TpaRequest hit = fromA.get(b);
            if (hit != null) {
                return hit;
            }
        }
        final Map<UUID, TpaRequest> fromB = outgoing.get(b);
        if (fromB != null) {
            return fromB.get(a);
        }
        return null;
    }

    /** 某人收到的全部待处理请求（按发起时间从早到晚）。 */
    public List<TpaRequest> incomingTo(final UUID target) {
        final Map<UUID, TpaRequest> map = incoming.get(target);
        if (map == null || map.isEmpty()) {
            return Collections.emptyList();
        }
        return sorted(new ArrayList<>(map.values()));
    }

    /** 某人发出的全部待处理请求。 */
    public List<TpaRequest> outgoingFrom(final UUID requester) {
        final Map<UUID, TpaRequest> map = outgoing.get(requester);
        if (map == null || map.isEmpty()) {
            return Collections.emptyList();
        }
        return sorted(new ArrayList<>(map.values()));
    }

    public int outgoingCount(final UUID requester) {
        final Map<UUID, TpaRequest> map = outgoing.get(requester);
        return map == null ? 0 : map.size();
    }

    /** 放进账本（两张表都写）。 */
    public void put(final TpaRequest request) {
        outgoing.computeIfAbsent(request.requesterId(), k -> new ConcurrentHashMap<>())
                .put(request.targetId(), request);
        incoming.computeIfAbsent(request.targetId(), k -> new ConcurrentHashMap<>())
                .put(request.requesterId(), request);
    }

    /** 从账本里摘掉（两张表都清；表空了顺手删掉那个键，别留一堆空 Map）。 */
    public void remove(final TpaRequest request) {
        removeFrom(outgoing, request.requesterId(), request.targetId());
        removeFrom(incoming, request.targetId(), request.requesterId());
    }

    /** 某人相关的请求全部清空（掉线、换服、被踢时用）。返回被清掉的那几条。 */
    public List<TpaRequest> removeAllFor(final UUID player) {
        final List<TpaRequest> removed = new ArrayList<>();
        final Map<UUID, TpaRequest> out = outgoing.remove(player);
        if (out != null) {
            for (final TpaRequest request : out.values()) {
                removeFrom(incoming, request.targetId(), request.requesterId());
                removed.add(request);
            }
        }
        final Map<UUID, TpaRequest> in = incoming.remove(player);
        if (in != null) {
            for (final TpaRequest request : in.values()) {
                removeFrom(outgoing, request.requesterId(), request.targetId());
                // 上面 remove(player) 已经拿走了自己那张表，这里只补对面的
                if (!removed.contains(request)) {
                    removed.add(request);
                }
            }
        }
        return removed;
    }

    /**
     * 把已经到点的请求摘出来返回（调用方负责通知双方）。
     *
     * @return 被清掉的过期请求
     */
    public List<TpaRequest> removeExpired(final long now) {
        final List<TpaRequest> expired = new ArrayList<>();
        for (final Map<UUID, TpaRequest> map : outgoing.values()) {
            for (final TpaRequest request : new ArrayList<>(map.values())) {
                if (request.isExpired(now)) {
                    expired.add(request);
                }
            }
        }
        for (final TpaRequest request : expired) {
            remove(request);
        }
        return expired;
    }

    /** 调试 / 日志用：当前挂着多少条。 */
    public int size() {
        int total = 0;
        for (final Map<UUID, TpaRequest> map : outgoing.values()) {
            total += map.size();
        }
        return total;
    }

    private static void removeFrom(final Map<UUID, Map<UUID, TpaRequest>> table,
                                   final UUID first, final UUID second) {
        final Map<UUID, TpaRequest> map = table.get(first);
        if (map == null) {
            return;
        }
        map.remove(second);
        // ⚠️ 只在「还是我刚看的这张表」时才删键 —— 期间可能已经有新请求把它又建起来了
        if (map.isEmpty()) {
            table.remove(first, map);
        }
    }

    private static List<TpaRequest> sorted(final List<TpaRequest> list) {
        list.sort((a, b) -> Long.compare(a.createdAt(), b.createdAt()));
        return list;
    }
}
