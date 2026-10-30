package com.yusijia.houseclientservices.tool;

import com.yusijia.houseclientservices.domain.view.HouseView;
import com.yusijia.houseclientservices.domain.view.OrgView;
import com.yusijia.houseclientservices.domain.view.PersonView;
import com.yusijia.houseclientservices.domain.view.SubscriptionView;
import com.yusijia.houseclientservices.exception.BizException;
import com.yusijia.houseclientservices.service.MasterDataService;
import com.yusijia.houseclientservices.service.SubscriptionService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 提供给大模型的工具集。
 *
 * 契约：创建认购只接受真实主数据 ID，模型必须先用查询工具拿到 ID，
 * 避免把自由文本（编造的坐落/姓名）直接落库。
 */
@Component
public class SubscriptionTools {

    private final MasterDataService masterDataService;
    private final SubscriptionService subscriptionService;

    /**
     * 追踪当前请求中 createSubscription 是否被实际调用，用于检测模型幻觉。
     */
    private final ThreadLocal<Boolean> createSubscriptionCalled = ThreadLocal.withInitial(() -> false);

    public SubscriptionTools(MasterDataService masterDataService,
                             SubscriptionService subscriptionService) {
        this.masterDataService = masterDataService;
        this.subscriptionService = subscriptionService;
    }

    /**
     * 消费并重置标记：返回 createSubscription 是否被调用过，然后清除标记。
     */
    public boolean consumeCreateSubscriptionCalled() {
        Boolean result = createSubscriptionCalled.get();
        createSubscriptionCalled.remove();
        return result != null && result;
    }

    @Tool(description = "按关键字查询房屋。生成认购前必须先调用本工具获取真实房屋ID。关键字可以是坐落片段，如“文一西路”“1幢101”；留空则列出前20套。")
    public String searchHouse(
            @ToolParam(description = "房屋坐落关键字，可为空", required = false) String keyword) {
        // #region agent log
        try (java.io.FileWriter fw = new java.io.FileWriter("D:\\ideaSpace\\study\\spring-ai-test\\debug-be087e.log", true)) {
            fw.write("{\"sessionId\":\"be087e\",\"runId\":\"pre-fix\",\"hypothesisId\":\"H5\",\"location\":\"SubscriptionTools.java:searchHouse\",\"message\":\"searchHouse invoked\",\"timestamp\":" + System.currentTimeMillis() + ",\"data\":{\"keyword\":\"" + (keyword == null ? "" : keyword.replace("\"", "'")) + "\"}}\n");
        } catch (Exception ignored) {
        }
        // #endregion
        List<HouseView> houses = masterDataService.searchHouses(keyword);
        if (houses.isEmpty()) {
            return "未找到匹配的房屋。请让客户提供更精确的坐落（如小区名、幢号、房号）。";
        }
        return houses.stream()
                .map(item -> "ID=" + item.id()
                        + "，坐落=" + item.fullAddress()
                        + "，建筑面积=" + plain(item.buildingArea()) + "㎡"
                        + "，参考单价=" + plain(item.referenceUnitPrice()) + "元/㎡"
                        + "，状态=" + (item.available() ? "可售" : "已认购"))
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "按姓名或证件号码查询个人客户。生成认购前必须先调用本工具获取真实客户ID。")
    public String searchPerson(
            @ToolParam(description = "客户姓名以及完整证件号码") String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return "请提供客户姓名或证件号码。";
        }
        List<PersonView> persons = masterDataService.searchPersons(keyword);
        if (persons.isEmpty()) {
            return "未找到匹配的个人客户。该客户可能尚未建档，请让客户确认姓名和证件号码。";
        }
        return persons.stream()
                .map(item -> "ID=" + item.id()
                        + "，姓名=" + item.fullName()
                        + "，证件号码=" + item.idNoMasked()
                        + "，电话=" + (item.phone() == null ? "未登记" : item.phone()))
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "按名称查询单位客户（开发企业、物业等）。企业名义购房前必须先调用本工具获取真实单位ID。")
    public String searchOrg(
            @ToolParam(description = "单位全称或其关键字") String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return "请提供单位全称。";
        }
        List<OrgView> units = masterDataService.searchOrgs(keyword);
        if (units.isEmpty()) {
            return "未找到匹配的单位。请让客户确认单位全称。";
        }
        return units.stream()
                .map(item -> "ID=" + item.id()
                        + "，单位全称=" + item.fullName()
                        + "，类型=" + item.unitTypeText()
                        + "，证件号码=" + item.idNo())
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "生成认购业务。必须使用 searchHouse 返回的房屋ID，以及 searchPerson 或 searchOrg 返回的购买主体ID；严禁使用任何未经查询得到的ID。信息不全时禁止调用。")
    public String createSubscription(
            @ToolParam(description = "房屋ID，多个用英文逗号分隔，必须来自 searchHouse 结果") String houseIds,
            @ToolParam(description = "个人客户ID，多个用英文逗号分隔，必须来自 searchPerson 结果；企业购房时传空字符串", required = false) String personIds,
            @ToolParam(description = "单位客户ID，多个用英文逗号分隔，必须来自 searchOrg 结果；个人购房时传空字符串", required = false) String orgIds) {
        createSubscriptionCalled.set(true);
        try {
            SubscriptionView view = subscriptionService.create(
                    parseIds(houseIds), parseIds(personIds), parseIds(orgIds));
            return "认购业务已生成。编号=" + view.bizNo()
                    + "，房屋坐落=" + String.join("、", view.houseLocations())
                    + "，购买方=" + String.join("、", view.buyerNames())
                    + "，总价=" + plain(view.totalPrice()) + "元"
                    + "，单价=" + plain(view.unitPrice()) + "元/㎡";
        } catch (BizException e) {
            return "生成失败：" + e.getMessage();
        }
    }

    @Tool(description = "查询认购业务，仅用于撤销前核对。必须提供房屋坐落或购买方姓名至少一项。只返回编号、房屋坐落、购房人和状态，不执行撤销。")
    public String findSubscription(
            @ToolParam(description = "房屋坐落关键字，可与姓名二选一或同时提供", required = false) String houseLocation,
            @ToolParam(description = "购买方姓名或单位名称，可与坐落二选一或同时提供", required = false) String buyerName) {
        if (!StringUtils.hasText(houseLocation) && !StringUtils.hasText(buyerName)) {
            return "查询失败：请提供房屋坐落或购买方姓名。";
        }
        List<SubscriptionView> matches = subscriptionService.find(houseLocation, buyerName);
        if (matches.isEmpty()) {
            return "未找到匹配的认购业务。";
        }
        return matches.stream()
                .map(item -> "编号=" + item.bizNo()
                        + "，房屋坐落=" + String.join("、", item.houseLocations())
                        + "，购房人=" + String.join("、", item.buyerNames())
                        + "，状态=" + item.statusText())
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "撤销认购业务。必须使用 findSubscription 查询得到的编号，且仅当客户已明确确认撤销时将 confirmed 设为 true。未确认时禁止调用。")
    public String cancelSubscription(
            @ToolParam(description = "业务编号，必须来自 findSubscription 的查询结果") String bizNo,
            @ToolParam(description = "客户是否已确认撤销，必须为 true 才执行") boolean confirmed) {
        if (!confirmed) {
            return "撤销未执行：客户尚未确认。请先展示房屋坐落和购房人姓名，待客户确认后再调用，并将 confirmed 设为 true。";
        }
        try {
            SubscriptionView view = subscriptionService.cancel(bizNo, true);
            return "认购业务已撤销。编号=" + view.bizNo()
                    + "，房屋坐落=" + String.join("、", view.houseLocations())
                    + "，购房人=" + String.join("、", view.buyerNames());
        } catch (BizException e) {
            return "撤销失败：" + e.getMessage();
        }
    }

    private List<Long> parseIds(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,，\\s]+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(value -> {
                    try {
                        return Long.parseLong(value);
                    } catch (NumberFormatException e) {
                        throw new BizException("ID 格式不正确：" + value);
                    }
                })
                .toList();
    }

    private String plain(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }
}
