package com.yusijia.houseclientservices.security;

import java.util.Collection;
import java.util.List;

/**
 * 数据范围校验（自助客户 vs 客服坐席的<b>真正</b>区别）。
 * <p>
 * 两个角色的权限点几乎相同，差别不在"能不能做这个动作"，而在"能在谁的数据上做"：
 * <ul>
 *   <li>自助客户：业务只能落在本人（{@link UserContext#personId()}）身上</li>
 *   <li>客服坐席：可代任意客户操作</li>
 * </ul>
 * <p>
 * 本类是纯函数，不依赖 Spring/DB，便于单测。返回 {@code null} 表示放行，
 * 返回非空字符串表示拒绝原因（调用方决定抛什么异常 / 怎么回话）。
 * <p>
 * 为什么不用抛 {@link AccessDeniedException} 直接走 403：这些校验发生在 AI 工具内部，
 * 工具把失败原因回给模型、由模型转述给客户，比直接中断成 500/403 体验更好。
 */
public final class DataScope {

    private DataScope() {
    }

    /**
     * 校验"这一笔认购的购买主体"是否允许当前用户操作。
     *
     * @param user      当前登录用户，null 视为未登录（调用方应已拦截）
     * @param personIds 购买个人 ID 列表
     * @param orgIds    购买单位 ID 列表
     * @return null 表示允许；否则为拒绝原因
     */
    public static String createViolation(UserContext user, List<Long> personIds, List<Long> orgIds) {
        if (user == null || !user.isCustomer()) {
            // 坐席不限制；未登录的拦截在接口层已完成，这里不重复
            return null;
        }
        if (user.personId() == null) {
            return "当前账号未关联个人档案，暂时无法办理认购，请联系客服";
        }
        if (orgIds != null && !orgIds.isEmpty()) {
            return "自助客户只能以本人名义办理认购，企业购房请联系客服坐席";
        }
        if (personIds == null || personIds.isEmpty()) {
            return "请提供购买人信息";
        }
        boolean onlySelf = personIds.size() == 1 && user.personId().equals(personIds.get(0));
        if (!onlySelf) {
            // 把本人客户 ID 告诉模型：否则模型拿不到正确 ID 时只能反复失败或编造 ID
            return "自助客户只能为本人办理认购（您的客户ID是 " + user.personId() + "，请使用该ID）";
        }
        return null;
    }

    /**
     * 校验"这笔已有认购"是否归当前用户所有（撤销前调用）。
     *
     * @param user            当前登录用户
     * @param buyerPersonIds  该认购单下的个人购买方 ID 集合
     * @return null 表示允许；否则为拒绝原因
     */
    public static String cancelViolation(UserContext user, Collection<Long> buyerPersonIds) {
        if (user == null || !user.isCustomer()) {
            return null;
        }
        if (user.personId() == null) {
            return "当前账号未关联个人档案，无法撤销认购，请联系客服";
        }
        if (buyerPersonIds == null || !buyerPersonIds.contains(user.personId())) {
            return "只能撤销本人名下的认购（您的客户ID是 " + user.personId() + "）";
        }
        return null;
    }

    /**
     * 查询认购单时的归属人过滤。
     *
     * @return 需要过滤到的 personId；null 表示不过滤（坐席）
     */
    public static Long queryOwnerFilter(UserContext user) {
        if (user != null && user.isCustomer()) {
            return user.personId();
        }
        return null;
    }
}
