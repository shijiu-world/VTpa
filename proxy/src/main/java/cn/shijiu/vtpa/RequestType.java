package cn.shijiu.vtpa;

/**
 * 请求的两种方向。
 *
 * <ul>
 *   <li>{@link #TPA} —— {@code /tpa}：我请求<b>传送到你那里</b>（动的是发起者）</li>
 *   <li>{@link #HERE} —— {@code /tpahere}、{@code /tpaall}：我请求<b>你传送到我这儿</b>（动的是被请求者）</li>
 * </ul>
 */
public enum RequestType {

    /** {@code /tpa}：发起者动。 */
    TPA,
    /** {@code /tpahere}、{@code /tpaall}：被请求者动。 */
    HERE;

    /** 谁要被搬走：{@code /tpa} 搬发起者，{@code /tpahere} 搬被请求者。 */
    public boolean movesRequester() {
        return this == TPA;
    }
}
