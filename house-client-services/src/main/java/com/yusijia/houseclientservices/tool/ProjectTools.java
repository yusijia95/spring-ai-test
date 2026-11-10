package com.yusijia.houseclientservices.tool;

import com.yusijia.houseclientservices.domain.view.ProjectView;
import com.yusijia.houseclientservices.service.MasterDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 楼盘与房价查询工具。
 * <p>
 * 存在的理由：{@link SubscriptionTools#searchHouse} 是"生成认购前按坐落查具体房号"的工具，
 * 模型不会拿它回答"这个区域还有哪些住宅、价格怎么样"。缺了这层能力，
 * 客户问到住宅和价格时模型只能沉默略过——这正是改造前的表现。
 */
@Component
public class ProjectTools {

    private static final Logger log = LoggerFactory.getLogger(ProjectTools.class);

    /** 回复里出现这些词，就认为模型已经交代了楼盘/价格这一问 */
    private static final Pattern HOUSING_MENTION =
            Pattern.compile("住宅|楼盘|房价|均价|单价|在售|价格");

    private final MasterDataService masterDataService;

    /** 本次请求是否调用过 searchProjects */
    private final ThreadLocal<Boolean> called = ThreadLocal.withInitial(() -> false);
    /** 本次请求"查不到楼盘"时可以直接转述的事实行 */
    private final ThreadLocal<String> notFoundFact = new ThreadLocal<>();

    public ProjectTools(MasterDataService masterDataService) {
        this.masterDataService = masterDataService;
    }

    public void clear() {
        called.remove();
        notFoundFact.remove();
    }

    /**
     * 兜底：模型查了楼盘却把这一问吞掉时，补一段事实。
     * <p>
     * 实测 qwen-max 在"楼盘 + 周边环境"同时提问时，会把长篇周边设施写完就收尾，
     * 提示词里写了"必须先写住宅与价格小节"也不一定照做。这里做确定性补齐，
     * 保证客户问到的方面一定有交代。
     *
     * @return 需要补在回复前面的 Markdown 段落；无需补时返回 null
     */
    public String unansweredHousingNote(String reply) {
        try {
            if (!Boolean.TRUE.equals(called.get())) {
                return null;
            }
            String fact = notFoundFact.get();
            if (fact == null) {
                return null;
            }
            if (StringUtils.hasText(reply) && HOUSING_MENTION.matcher(reply).find()) {
                return null;
            }
            return "**住宅与价格**\n" + fact;
        } finally {
            clear();
        }
    }

    @Tool(description = "按区域、板块或楼盘名称查询在售楼盘，返回项目名、坐落、开发企业、在售套数、参考单价区间和建筑面积区间。"
            + "客户询问某区域有哪些住宅/楼盘/小区，或房价、均价、单价、在售房源、户型、面积时，必须先调用本工具。")
    public String searchProjects(
            @ToolParam(description = "区域、板块或楼盘名称关键字，如“江宁区”“联发云启”；留空则列出全部在售项目") String keyword) {
        called.set(true);
        List<ProjectView> projects = masterDataService.searchProjects(keyword);
        log.info("searchProjects keyword={}, hits={}", keyword, projects.size());
        if (projects.isEmpty()) {
            String fact = notFound(keyword);
            notFoundFact.set(fact);
            return "住宅与价格：" + fact;
        }
        return projects.stream().map(this::format).collect(Collectors.joining("\n"));
    }

    private String notFound(String keyword) {
        List<ProjectView> all = masterDataService.searchProjects(null);
        if (all.isEmpty()) {
            return "系统当前没有任何在售项目数据。";
        }
        String available = all.stream()
                .map(item -> item.projectName() + "（" + nullToDash(item.location()) + "）")
                .collect(Collectors.joining("、"));
        if (!StringUtils.hasText(keyword)) {
            return "未查到在售项目。当前系统收录：" + available + "。";
        }
        // 输出做成"可直接转述的一行事实"，而不是给模型的指令：
        // 模型会照抄工具返回的事实，但不会执行工具返回里的指令（这是正确的防注入行为）。
        return "未查到与“" + keyword.trim() + "”匹配的在售楼盘，该区域暂无在售项目数据。"
                + "当前系统仅收录：" + available + "。";
    }

    private String format(ProjectView item) {
        return "项目=" + item.projectName()
                + "，坐落=" + nullToDash(item.location())
                + "，开发企业=" + nullToDash(item.developerName())
                + "，在售=" + item.availableRooms() + "套/共" + item.totalRooms() + "套"
                + "，参考单价=" + priceRange(item.minUnitPrice(), item.maxUnitPrice()) + "元/㎡"
                + "，建筑面积=" + areaRange(item.minArea(), item.maxArea()) + "㎡";
    }

    private String priceRange(BigDecimal min, BigDecimal max) {
        if (min == null && max == null) {
            return "未登记";
        }
        if (min == null || max == null || min.compareTo(max) == 0) {
            return plain(min == null ? max : min);
        }
        return plain(min) + "~" + plain(max);
    }

    private String areaRange(BigDecimal min, BigDecimal max) {
        if (min == null && max == null) {
            return "未登记";
        }
        if (min == null || max == null || min.compareTo(max) == 0) {
            return plain(min == null ? max : min);
        }
        return plain(min) + "~" + plain(max);
    }

    private String plain(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    private String nullToDash(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }
}
