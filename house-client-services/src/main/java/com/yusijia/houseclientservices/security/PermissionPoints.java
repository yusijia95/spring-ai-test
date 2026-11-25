package com.yusijia.houseclientservices.security;

/**
 * 权限点常量（P0-1 骨架）。
 * <p>
 * 这里只定义"有哪些权限点"和"角色默认持有哪些"，不做数据级（行级）校验。
 * 接口级校验在 {@code AgentController}/{@code MasterDataController} 里显式调用
 * {@code requirePermission(PermissionPoints.XXX)}，骨架可读、可审计，
 * 将来换成注解或 Spring Security 时只需移动判定位置，权限点本身不变。
 * <p>
 * 命名约定：{@code <域>:<资源>:<动作>}。
 */
public final class PermissionPoints {

    private PermissionPoints() {
    }

    // ---- 聊天 / 会话 ----
    /** 发起智能体对话（两种角色都有）。 */
    public static final String CHAT_USE = "chat:use";
    /** 读写自己的会话列表与记忆（两种角色都有，归属校验另行兜底）。 */
    public static final String CHAT_CONVERSATION = "chat:conversation:read";

    // ---- 主数据查询 ----
    /** 查房屋（两种角色都有；坐席帮客户查房也走这个）。 */
    public static final String DATA_HOUSE_SEARCH = "data:house:search";
    /** 查个人客户（含证件号，敏感；两种角色都有，坐席办业务需要）。 */
    public static final String DATA_PERSON_SEARCH = "data:person:search";
    /**
     * 查看个人客户手机号明文（仅坐席）。
     * <p>
     * 手机号是直接触达手段，比证件号更"可滥用"：拿到即可骚扰/撞库。默认一律掩码，
     * 只有明确持有本权限点时才返回原值——接口与 AI 工具两侧都必须显式判定，
     * 不能靠"调用的地方记得掩码"。
     */
    public static final String DATA_PERSON_PHONE_READ = "data:person:phone:read";
    /** 查单位客户。 */
    public static final String DATA_ORG_SEARCH = "data:org:search";

    // ---- 认购业务 ----
    /** 查询认购单（两种角色都有）。 */
    public static final String SUBSCRIPTION_READ = "subscription:read";
    /** 创建认购（坐席代办 + 客户自助都可能需要，按角色矩阵开放）。 */
    public static final String SUBSCRIPTION_CREATE = "subscription:create";
    /** 撤销认购（仅坐席）。 */
    public static final String SUBSCRIPTION_CANCEL = "subscription:cancel";

    // ---- 审计 ----
    /**
     * 查询智能体审计记录（仅坐席，P0-5）。
     * <p>
     * 审计里含他人会话的工具入参/结果（脱敏），客户角色不应能按 conversationId 翻别人的操作记录，
     * 所以只给坐席；坐席按 conversationId 反查"哪一轮、哪些技能、调了什么工具、是否经确认"。
     */
    public static final String AUDIT_READ = "audit:read";
}
