package com.yusijia.houseclientservices.tool;

import com.yusijia.houseclientservices.agent.memory.UserProfileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * 用户画像写入工具（L3 语义记忆）。
 * <p>
 * 与 {@link SessionStateTools}（会话级、Redis、TTL 24h）的区别：
 * <ul>
 *   <li>画像按 userId 持久化在 PG，跨会话共享，无 TTL</li>
 *   <li>记录的是长期偏好（预算、区域、购房目的），不是当前在谈哪套房</li>
 *   <li>用户主动表达"我预算 300 万""我想买江宁区"时模型调这个工具</li>
 * </ul>
 */
@Component
public class UserProfileTools {

    private static final Logger log = LoggerFactory.getLogger(UserProfileTools.class);

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "budget", "districtPref", "roomPref", "purpose", "other");

    private final UserProfileStore store;

    public UserProfileTools(UserProfileStore store) {
        this.store = store;
    }

    @Tool(description = """
            记录客户的长期偏好画像，跨会话持久化。
            当客户明确说出预算、意向区域、户型偏好、购房目的等长期信息时调用。
            只记录客户明确说过的内容，不要记录猜测和闲聊。
            字段说明：
            - budget：客户预算（如"300万左右"）
            - districtPref：意向区域（如"江宁区，靠近地铁"）
            - roomPref：户型偏好（如"三室两厅"）
            - purpose：购房目的（如"学区""自住""投资"）
            - other：其他长期偏好
            注意：不要记录当前在谈的具体楼盘或房号（那是会话状态，用 rememberSessionFact）。
            """)
    public String rememberUserProfile(
            @ToolParam(description = "字段名，只能是 budget、districtPref、roomPref、purpose、other 之一") String key,
            @ToolParam(description = "要记住的偏好内容，尽量简短") String value,
            @ToolParam(description = "来源说明，如'客户主动提及'") String source,
            ToolContext toolContext) {
        ToolExecutionContext exec = ToolExecutionContext.of(toolContext);
        String userId = exec == null || exec.user() == null ? null : String.valueOf(exec.user().userId());
        if (!StringUtils.hasText(userId)) {
            return "未记录：当前没有绑定用户。";
        }
        String field = key == null ? "" : key.trim();
        if (!ALLOWED_KEYS.contains(field)) {
            return "未记录：字段名只能是 " + String.join("、", ALLOWED_KEYS) + "。";
        }
        if (!StringUtils.hasText(value)) {
            return "未记录：内容为空。";
        }
        store.upsert(userId, field, value, source);
        log.info("用户画像工具调用: userId={}, key={}, value={}", userId, field, value);
        return "已记录 " + field + "：" + value;
    }
}
