package com.yusijia.houseclientservices.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 接口级权限判定（P0-1 骨架）。
 * <p>
 * 角色 × 权限点矩阵写死在这里：
 * <ul>
 *   <li>{@code customer}：chat:use、chat:conversation:read、data:house:search、
 *       data:person:search、data:org:search、subscription:read</li>
 *   <li>{@code agent}：上面全部 + subscription:create、subscription:cancel</li>
 * </ul>
 * <p>
 * 控制器里调用 {@link #requirePermission(HttpServletRequest, String)}，
 * 无登录 / 无权限抛 {@link AccessDeniedException}（由全局异常处理翻译成 403）。
 * 这是骨架阶段唯一需要"每接口加一行"的地方，换来的是判定位置显式、可审计。
 */
public final class Authorization {

    private static final Logger log = LoggerFactory.getLogger(Authorization.class);

    private static final Set<String> CUSTOMER = Set.of(
            PermissionPoints.CHAT_USE,
            PermissionPoints.CHAT_CONVERSATION,
            PermissionPoints.DATA_HOUSE_SEARCH,
            PermissionPoints.DATA_PERSON_SEARCH,
            PermissionPoints.DATA_ORG_SEARCH,
            PermissionPoints.SUBSCRIPTION_READ
    );

    private static final Set<String> AGENT = Set.of(
            PermissionPoints.CHAT_USE,
            PermissionPoints.CHAT_CONVERSATION,
            PermissionPoints.DATA_HOUSE_SEARCH,
            PermissionPoints.DATA_PERSON_SEARCH,
            PermissionPoints.DATA_ORG_SEARCH,
            PermissionPoints.SUBSCRIPTION_READ,
            PermissionPoints.SUBSCRIPTION_CREATE,
            PermissionPoints.SUBSCRIPTION_CANCEL
    );

    private Authorization() {
    }

    /** 要求已登录，否则 401（抛 AccessDeniedException，控制器可区分未登录/无权限）。 */
    public static UserContext requireLogin(HttpServletRequest request) {
        UserContext user = UserContextFilter.fromRequest(request);
        if (user == null) {
            throw new AccessDeniedException("未登录或会话已过期");
        }
        return user;
    }

    /** 要求已登录且持有指定权限点，否则 403。 */
    public static UserContext requirePermission(HttpServletRequest request, String permission) {
        UserContext user = requireLogin(request);
        if (!hasPermission(user, permission)) {
            log.warn("auth: 权限不足 userId={} role={} 缺 {} path={}",
                    user.userId(), user.role(), permission, request.getRequestURI());
            throw new AccessDeniedException("当前角色无权执行该操作");
        }
        return user;
    }

    public static boolean hasPermission(UserContext user, String permission) {
        if (user == null) {
            return false;
        }
        Set<String> granted = user.isAgent() ? AGENT : CUSTOMER;
        return granted.contains(permission);
    }
}
