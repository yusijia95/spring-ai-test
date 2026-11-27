package com.yusijia.houseclientservices.controller;

import com.yusijia.houseclientservices.agent.memory.ChatMessageStore;
import com.yusijia.houseclientservices.agent.memory.UserProfileStore;
import com.yusijia.houseclientservices.security.UserContext;
import com.yusijia.houseclientservices.security.UserContextFilter;
import com.yusijia.houseclientservices.service.ConversationService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画像查询接口的权限与脱敏逻辑测试。
 * <p>
 * 用 fake store + dynamic proxy mock request 验证：
 * <ul>
 *   <li>客户只能查自己（忽略 userId 参数）</li>
 *   <li>坐席可查指定客户</li>
 *   <li>坐席不传 userId 时默认查自己</li>
 *   <li>返回值全部脱敏（手机号/证件号被 mask）</li>
 *   <li>无画像时返回空 Map</li>
 * </ul>
 */
class ConversationControllerProfileTest {

    private FakeProfileStore profileStore;
    private ConversationController controller;

    @BeforeEach
    void setUp() {
        profileStore = new FakeProfileStore();
        controller = new ConversationController(new NoopConversationService(), new NoopMessageStore(), profileStore);
    }

    @Test
    void 客户查自己() {
        profileStore.put("1", Map.of("budget", "300万左右", "districtPref", "江宁区"));
        ResponseEntity<Map<String, String>> resp = controller.profile(
                null, req(new UserContext(1L, "customer1", UserContext.ROLE_CUSTOMER, "客户一", 99L)));
        assertEquals(200, resp.getStatusCode().value());
        assertEquals(2, resp.getBody().size());
        assertEquals("300万左右", resp.getBody().get("budget"));
    }

    @Test
    void 客户不能查别人() {
        profileStore.put("1", Map.of("budget", "300万"));
        profileStore.put("2", Map.of("budget", "500万"));

        // 客户1 即使传 userId=2，也只能查到自己
        ResponseEntity<Map<String, String>> resp = controller.profile(
                "2", req(new UserContext(1L, "customer1", UserContext.ROLE_CUSTOMER, "客户一", 99L)));
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("300万", resp.getBody().get("budget"));
    }

    @Test
    void 坐席查指定客户() {
        profileStore.put("99", Map.of("purpose", "学区"));
        ResponseEntity<Map<String, String>> resp = controller.profile(
                "99", req(new UserContext(2L, "agent1", UserContext.ROLE_AGENT, "坐席一")));
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("学区", resp.getBody().get("purpose"));
    }

    @Test
    void 坐席不传userId默认查自己() {
        profileStore.put("2", Map.of("budget", "200万"));
        ResponseEntity<Map<String, String>> resp = controller.profile(
                null, req(new UserContext(2L, "agent1", UserContext.ROLE_AGENT, "坐席一")));
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("200万", resp.getBody().get("budget"));
    }

    @Test
    void 手机号被脱敏() {
        profileStore.put("1", Map.of("other", "联系电话13812345678"));
        ResponseEntity<Map<String, String>> resp = controller.profile(
                null, req(new UserContext(1L, "customer1", UserContext.ROLE_CUSTOMER, "客户一", 99L)));
        String value = resp.getBody().get("other");
        assertTrue(value.contains("****"), "手机号应被脱敏: " + value);
        assertTrue(!value.contains("13812345678"), "不应包含完整手机号");
    }

    @Test
    void 证件号被脱敏() {
        profileStore.put("1", Map.of("other", "证件330102199001011234"));
        ResponseEntity<Map<String, String>> resp = controller.profile(
                null, req(new UserContext(1L, "customer1", UserContext.ROLE_CUSTOMER, "客户一", 99L)));
        String value = resp.getBody().get("other");
        assertTrue(value.contains("****"), "证件号应被脱敏: " + value);
    }

    @Test
    void 无画像返回空Map() {
        ResponseEntity<Map<String, String>> resp = controller.profile(
                null, req(new UserContext(1L, "customer1", UserContext.ROLE_CUSTOMER, "客户一", 99L)));
        assertEquals(200, resp.getStatusCode().value());
        assertTrue(resp.getBody().isEmpty());
    }

    // ==================== Fakes ====================

    /** 内存版 UserProfileStore */
    static class FakeProfileStore implements UserProfileStore {
        private final java.util.concurrent.ConcurrentHashMap<String, Map<String, String>> data = new java.util.concurrent.ConcurrentHashMap<>();

        void put(String userId, Map<String, String> profile) {
            data.put(userId, profile);
        }

        @Override
        public Map<String, String> get(String userId) {
            return data.getOrDefault(userId, Map.of());
        }

        @Override
        public void upsert(String userId, String key, String value, String source) {
            data.compute(userId, (k, v) -> {
                java.util.LinkedHashMap<String, String> m = v == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(v);
                m.put(key, value);
                return m;
            });
        }
    }

    static class NoopConversationService extends ConversationService {
        NoopConversationService() {
            super(null);
        }
    }

    static class NoopMessageStore implements ChatMessageStore {
        @Override
        public void append(String conversationId, String userId, java.util.List<org.springframework.ai.chat.messages.Message> messages) {}

        @Override
        public java.util.List<org.springframework.ai.chat.messages.Message> recent(String conversationId, int limit) {
            return java.util.List.of();
        }

        @Override
        public void delete(String conversationId) {}

        @Override
        public java.util.List<StoredMessage> recentWithTime(String conversationId, int limit) {
            return java.util.List.of();
        }
    }

    /** 构造一个 HttpServletRequest，setAttribute/getAttribute 能存取 UserContext。 */
    static HttpServletRequest req(UserContext user) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            switch (name) {
                case "setAttribute":
                    // no-op
                    return null;
                case "getAttribute":
                    if (UserContextFilter.ATTR_USER.equals(args[0])) {
                        return user;
                    }
                    return null;
                case "getRequestURI":
                    return "/api/agent/profile";
                case "getMethod":
                    return "GET";
                case "toString":
                    return "MockRequest";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    // 返回合理的默认值
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == String.class) return null;
                    return null;
            }
        };
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                handler);
    }
}
