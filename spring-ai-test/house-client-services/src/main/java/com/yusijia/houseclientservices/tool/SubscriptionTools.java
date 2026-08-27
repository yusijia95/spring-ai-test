package com.yusijia.houseclientservices.tool;

import com.yusijia.houseclientservices.domain.Subscription;
import com.yusijia.houseclientservices.store.SubscriptionStore;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class SubscriptionTools {

    private final SubscriptionStore subscriptionStore;

    /**
     * 追踪当前请求中 createSubscription 是否被实际调用。
     * 用于检测模型幻觉：模型声称"认购已生成"但未真正调用工具。
     */
    private final ThreadLocal<Boolean> createSubscriptionCalled = ThreadLocal.withInitial(() -> false);

    public SubscriptionTools(SubscriptionStore subscriptionStore) {
        this.subscriptionStore = subscriptionStore;
    }

    /**
     * 消费并重置标记：返回 createSubscription 是否被调用过，然后清除标记。
     * 每次请求处理完毕后调用。
     */
    public boolean consumeCreateSubscriptionCalled() {
        Boolean result = createSubscriptionCalled.get();
        createSubscriptionCalled.remove();
        return result != null && result;
    }

    @Tool(description = "生成认购业务。认购时调用。必须同时提供房屋坐落、购买方姓名、购买方证件号码，缺一不可。信息不全时禁止调用。")
    public String createSubscription(
            @ToolParam(description = "房屋坐落，必须是完整地址或楼栋房号") String houseLocation,
            @ToolParam(description = "购买方真实姓名") String buyerName,
            @ToolParam(description = "购买方证件号码") String buyerIdNo) {
        createSubscriptionCalled.set(true);
        if (!StringUtils.hasText(houseLocation) || !StringUtils.hasText(buyerName) || !StringUtils.hasText(buyerIdNo)) {
            return "生成失败：房屋坐落、购买方姓名、购买方证件号码均不能为空。请向客户补齐后再调用。";
        }
        if (subscriptionStore.existsByLocationAndIdNo(houseLocation, buyerIdNo)) {
            return "生成失败：该房屋坐落与证件号码已存在认购记录，请勿重复办理。";
        }
        Subscription created = subscriptionStore.create(houseLocation, buyerName, buyerIdNo);
        return "认购业务已生成。编号=" + created.id()
                + "，房屋坐落=" + created.houseLocation()
                + "，购买方=" + created.buyerName()
                + "，证件号码=" + created.buyerIdNo();
    }

    @Tool(description = "查询认购业务，仅用于撤销前核对。必须提供房屋坐落或购买方姓名至少一项。只返回房屋坐落、购房人姓名和编号，不执行撤销。")
    public String findSubscription(
            @ToolParam(description = "房屋坐落，可与姓名二选一或同时提供", required = false) String houseLocation,
            @ToolParam(description = "购买方姓名，可与坐落二选一或同时提供", required = false) String buyerName) {
        if (!StringUtils.hasText(houseLocation) && !StringUtils.hasText(buyerName)) {
            return "查询失败：请提供房屋坐落或购买方姓名。";
        }
        List<Subscription> matches = subscriptionStore.find(houseLocation, buyerName);
        if (matches.isEmpty()) {
            return "未找到匹配的认购业务。";
        }
        return matches.stream()
                .map(item -> "编号=" + item.id()
                        + "，房屋坐落=" + item.houseLocation()
                        + "，购房人姓名=" + item.buyerName())
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "撤销认购业务。必须使用查询得到的编号，且仅当客户已明确确认撤销时将 confirmed 设为 true。未确认时禁止调用或必须 confirmed=false。")
    public String cancelSubscription(
            @ToolParam(description = "认购编号，必须来自 findSubscription 的查询结果") String id,
            @ToolParam(description = "客户是否已确认撤销，必须为 true 才执行删除") boolean confirmed) {
        if (!confirmed) {
            return "撤销未执行：客户尚未确认。请先展示房屋坐落和购房人姓名，待客户确认后再调用，并将 confirmed 设为 true。";
        }
        if (!StringUtils.hasText(id)) {
            return "撤销失败：缺少认购编号。";
        }
        Optional<Subscription> removed = subscriptionStore.deleteById(id.trim());
        if (removed.isEmpty()) {
            return "撤销失败：未找到该编号的认购业务。";
        }
        Subscription item = removed.get();
        return "认购业务已撤销。编号=" + item.id()
                + "，房屋坐落=" + item.houseLocation()
                + "，购房人姓名=" + item.buyerName();
    }
}
