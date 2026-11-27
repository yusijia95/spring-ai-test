package com.yusijia.houseclientservices.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 技能目录：DB 运行时真相 + classpath SKILL.md 种子。
 * <p>
 * 启动时：
 * <ol>
 *   <li>从 classpath 加载 SKILL.md（种子）</li>
 *   <li>查 DB {@code agent_skill} 表，有 active 记录则用 DB 版本</li>
 *   <li>DB 无 active 技能时，自动把 classpath 种子写入 DB（首次初始化）</li>
 * </ol>
 * <p>
 * 灰度：同一技能可有多个版本并存。每个版本有灰度配置
 * （{@code canary_user_ids} 白名单 + {@code canary_percent} 百分比）。
 * 路由规则：白名单命中 → 该版本；否则按 userId 哈希百分比决定走新版还是旧版。
 * <p>
 * 运行时：缓存技能列表，{@link #reload()} 可手动刷新（DB 变更后调）。
 * 选择器每轮用 {@link #index()}，不查库。
 */
@Component
public class SkillCatalog {

    private static final Logger log = LoggerFactory.getLogger(SkillCatalog.class);

    private final String classpathPattern;
    private final JdbcTemplate jdbc;
    private final AtomicReference<List<Skill>> skills = new AtomicReference<>(List.of());

    /** 每个技能的灰度版本信息（loadFromDb 时填充） */
    private record CanaryConfig(String name, int baseVersion, int canaryVersion,
                                 Set<String> canaryUserIds, int canaryPercent) {}
    private final AtomicReference<List<CanaryConfig>> canaryConfigs = new AtomicReference<>(List.of());

    @org.springframework.beans.factory.annotation.Autowired
    public SkillCatalog(@Value("${agent.skills.pattern:classpath*:skills/*/SKILL.md}") String pattern,
                        org.springframework.jdbc.core.JdbcTemplate businessJdbc) {
        this.classpathPattern = pattern;
        this.jdbc = businessJdbc;
    }

    /** 兼容测试构造：无 DB，只从 classpath 加载 */
    SkillCatalog(String pattern) {
        this.classpathPattern = pattern;
        this.jdbc = null;
        List<Skill> classpathSkills = loadClasspathSkills(pattern);
        if (classpathSkills.isEmpty()) {
            throw new IllegalStateException("未加载到任何技能，请检查 src/main/resources/skills/<name>/SKILL.md 是否存在");
        }
        skills.set(classpathSkills);
        log.info("加载技能(测试模式): {}", classpathSkills.stream()
                .map(Skill::name).collect(Collectors.joining(", ")));
    }

    @PostConstruct
    void init() {
        if (jdbc == null) {
            return;
        }
        List<Skill> classpathSkills = loadClasspathSkills(classpathPattern);
        log.info("classpath 技能种子: {}", classpathSkills.stream()
                .map(s -> s.name()).collect(Collectors.joining(", ")));

        List<Skill> dbSkills = loadFromDb(null);
        if (!dbSkills.isEmpty()) {
            log.info("DB 技能已就绪: {}", dbSkills.stream()
                    .map(s -> s.name() + "(v" + s.version() + ")").collect(Collectors.joining(", ")));
            skills.set(dbSkills);
        } else {
            seedFromClasspath(classpathSkills);
            dbSkills = loadFromDb(null);
            skills.set(dbSkills);
            log.info("技能已从 classpath 种子到 DB: {}", dbSkills.stream()
                    .map(Skill::name).collect(Collectors.joining(", ")));
        }
    }

    // ==================== 运行时查询 ====================

    /**
     * 所有 active 技能（按 name 排序），默认灰度（userId=null → 走基础版本）。
     */
    public List<Skill> all() {
        return all(null);
    }

    /**
     * 按灰度规则解析后的 active 技能列表。
     *
     * @param userId 当前用户 ID（null 时走基础版本，用于选择器/测试）
     */
    public List<Skill> all(String userId) {
        List<Skill> base = skills.get();
        List<CanaryConfig> configs = canaryConfigs.get();
        if (configs.isEmpty() || userId == null) {
            return base;
        }
        // 对每个有灰度配置的技能，判断该用户是否命中新版
        Map<String, Integer> overrides = new LinkedHashMap<>();
        for (CanaryConfig cfg : configs) {
            if (shouldUseCanary(cfg, userId)) {
                overrides.put(cfg.name(), cfg.canaryVersion());
            }
        }
        if (overrides.isEmpty()) {
            return base;
        }
        // 替换命中灰度的技能为对应版本
        List<Skill> result = new ArrayList<>(base.size());
        for (Skill skill : base) {
            Integer canaryVer = overrides.get(skill.name());
            if (canaryVer != null && canaryVer != skill.version()) {
                // 加载灰度版本
                Skill canary = loadSkillVersion(skill.name(), canaryVer);
                result.add(canary != null ? canary : skill);
            } else {
                result.add(skill);
            }
        }
        return result;
    }

    /**
     * 按名称取技能（默认灰度）。
     */
    public List<Skill> byNames(Collection<String> names) {
        return byNames(names, null);
    }

    /**
     * 按名称取技能（灰度感知）。
     */
    public List<Skill> byNames(Collection<String> names, String userId) {
        Map<String, Skill> lookup = all(userId).stream()
                .collect(Collectors.toMap(Skill::name, s -> s, (a, b) -> a));
        List<Skill> result = new ArrayList<>();
        for (String name : new LinkedHashSet<>(names)) {
            Skill skill = lookup.get(name);
            if (skill != null) {
                result.add(skill);
            } else {
                log.warn("未知技能: {}", name);
            }
        }
        return result;
    }

    /**
     * 给选择器用的技能清单（默认灰度）。
     */
    public String index() {
        return index(null);
    }

    /**
     * 给选择器用的技能清单（灰度感知）。
     */
    public String index(String userId) {
        return all(userId).stream()
                .map(s -> "- " + s.name() + ": " + s.description())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 从 DB 重新加载技能 + 灰度配置（变更事件后调）。
     */
    public void reload() {
        List<Skill> dbSkills = loadFromDb(null);
        skills.set(dbSkills);
        log.info("技能目录已刷新: {} 个 active 技能", dbSkills.size());
    }

    // ==================== 灰度路由 ====================

    private boolean shouldUseCanary(CanaryConfig cfg, String userId) {
        // 白名单命中
        if (cfg.canaryUserIds() != null && !cfg.canaryUserIds().isEmpty()
                && cfg.canaryUserIds().contains(userId)) {
            return true;
        }
        // 百分比路由：userId 哈希取模
        int percent = cfg.canaryPercent();
        if (percent >= 100) {
            return true;
        }
        if (percent <= 0) {
            return false;
        }
        int bucket = Math.abs(userId.hashCode() % 100);
        return bucket < percent;
    }

    private Skill loadSkillVersion(String name, int version) {
        if (jdbc == null) {
            return null;
        }
        try {
            List<Skill> rows = jdbc.query(
                    "SELECT name, version, status, description, body, tools "
                            + "FROM agent_skill WHERE name = ? AND version = ? AND status = 'active'",
                    (rs, rowNum) -> new Skill(
                            rs.getString("name"),
                            rs.getString("description"),
                            parseCommaList(rs.getString("tools")),
                            rs.getString("body"),
                            List.of(),
                            rs.getInt("version"),
                            rs.getString("status")),
                    name, version);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException e) {
            log.warn("加载技能版本失败: {} v{}: {}", name, version, e.getMessage());
            return null;
        }
    }

    // ==================== 版本管理 ====================

    /**
     * 发布新版本：INSERT 新版本（status=draft），不自动激活。
     *
     * @return 新版本号
     */
    public int publish(String name, String description, String body, String tools,
                       String canaryUserIds, int canaryPercent, String operator) {
        if (jdbc == null) {
            throw new IllegalStateException("DB 不可用");
        }
        Integer maxVer = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM agent_skill WHERE name = ?",
                Integer.class, name);
        int newVersion = (maxVer == null ? 0 : maxVer) + 1;
        String toolsStr = tools != null ? tools.replace("[", "").replace("]", "") : "";

        jdbc.update(
                "INSERT INTO agent_skill (name, version, status, description, body, tools, "
                        + "canary_user_ids, canary_percent, author, approver) "
                        + "VALUES (?, ?, 'draft', ?, ?, ?, ?, ?, ?, ?)",
                name, newVersion, description, body, toolsStr,
                canaryUserIds, canaryPercent, operator, operator);
        audit(name, newVersion, "publish", operator,
                "published from " + (maxVer == null ? "0" : maxVer) + " → " + newVersion);
        log.info("技能 {} 已发布 v{} (draft), canary={}% users={}",
                name, newVersion, canaryPercent, canaryUserIds);
        return newVersion;
    }

    /**
     * 激活指定版本（设为 active + 100% 流量），其他版本不变。
     */
    public void activate(String name, int version, String operator) {
        if (jdbc == null) {
            throw new IllegalStateException("DB 不可用");
        }
        jdbc.update(
                "UPDATE agent_skill SET status = 'active', canary_percent = 100, "
                        + "activated_at = NOW(3), approver = ? WHERE name = ? AND version = ?",
                operator, name, version);
        audit(name, version, "activate", operator, "activated v" + version);
        reload();
        log.info("技能 {} v{} 已激活, operator={}", name, version, operator);
    }

    /**
     * 停用指定版本（设为 draft）。
     */
    public void deactivate(String name, int version, String operator) {
        if (jdbc == null) {
            throw new IllegalStateException("DB 不可用");
        }
        jdbc.update(
                "UPDATE agent_skill SET status = 'draft', canary_percent = 0 "
                        + "WHERE name = ? AND version = ?",
                name, version);
        audit(name, version, "deactivate", operator, "deactivated v" + version);
        reload();
        log.info("技能 {} v{} 已停用, operator={}", name, version, operator);
    }

    /**
     * 回滚：找到最近一个 active 版本并激活它。
     *
     * @return 回滚到的版本号
     */
    public int rollback(String name, String operator) {
        if (jdbc == null) {
            throw new IllegalStateException("DB 不可用");
        }
        // 找最近一个非当前 active 的版本
        Integer prevVersion = jdbc.queryForObject(
                "SELECT version FROM agent_skill WHERE name = ? AND status = 'active' "
                        + "ORDER BY version DESC LIMIT 1 OFFSET 1",
                Integer.class, name);
        if (prevVersion == null) {
            // 只有一个 active 版本 → 找最近一个 draft
            prevVersion = jdbc.queryForObject(
                    "SELECT version FROM agent_skill WHERE name = ? AND status = 'draft' "
                            + "ORDER BY version DESC LIMIT 1",
                    Integer.class, name);
        }
        if (prevVersion == null) {
            throw new IllegalStateException("技能 " + name + " 无可回滚版本");
        }
        activate(name, prevVersion, operator);
        audit(name, prevVersion, "rollback", operator, "rolled back to v" + prevVersion);
        return prevVersion;
    }

    /**
     * 更新灰度配置。
     */
    public void setCanary(String name, int version, String canaryUserIds, int canaryPercent,
                          String operator) {
        if (jdbc == null) {
            throw new IllegalStateException("DB 不可用");
        }
        int rows = jdbc.update(
                "UPDATE agent_skill SET canary_user_ids = ?, canary_percent = ? "
                        + "WHERE name = ? AND version = ? AND status = 'active'",
                canaryUserIds, canaryPercent, name, version);
        if (rows == 0) {
            throw new IllegalStateException("技能 " + name + " v" + version + " 不存在或非 active");
        }
        audit(name, version, "canary", operator,
                "canary set: percent=" + canaryPercent + " users=" + canaryUserIds);
        reload();
        log.info("技能 {} v{} 灰度已更新: percent={}%, users={}", name, version, canaryPercent, canaryUserIds);
    }

    // ==================== 同步工具（export / diff / publish） ====================

    /**
     * 导出当前 active 技能为 classpath SKILL.md 格式（用于 code review）。
     *
     * @return name → SKILL.md 内容 的映射
     */
    public Map<String, String> exportActive() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Skill skill : all()) {
            StringBuilder sb = new StringBuilder();
            sb.append("---\n");
            sb.append("name: ").append(skill.name()).append("\n");
            sb.append("description: ").append(skill.description().replace("\n", " ")).append("\n");
            sb.append("tools: ").append(String.join(", ", skill.tools())).append("\n");
            sb.append("---\n");
            sb.append(skill.body());
            result.put(skill.name(), sb.toString());
        }
        return result;
    }

    /**
     * 对比 classpath SKILL.md 与 DB active 版本的差异。
     *
     * @return name → 差异描述（null = 无差异）
     */
    public Map<String, String> diff() {
        Map<String, String> result = new LinkedHashMap<>();
        Map<String, Skill> classpathSkills = loadClasspathSkills(classpathPattern).stream()
                .collect(Collectors.toMap(Skill::name, s -> s));
        Map<String, Skill> dbSkills = all().stream()
                .collect(Collectors.toMap(Skill::name, s -> s));

        // 合并所有技能名
        Set<String> allNames = new LinkedHashSet<>();
        allNames.addAll(classpathSkills.keySet());
        allNames.addAll(dbSkills.keySet());

        for (String name : allNames) {
            Skill cp = classpathSkills.get(name);
            Skill db = dbSkills.get(name);
            if (cp == null) {
                result.put(name, "仅 DB 有（v" + (db != null ? db.version() : "?") + "）");
            } else if (db == null) {
                result.put(name, "仅 classpath 有");
            } else if (!cp.description().equals(db.description())
                    || !cp.tools().equals(db.tools())
                    || !cp.body().trim().equals(db.body().trim())) {
                StringBuilder diff = new StringBuilder();
                if (!cp.description().equals(db.description())) {
                    diff.append("description 不同\n  classpath: ").append(cp.description())
                            .append("\n  db: ").append(db.description()).append('\n');
                }
                if (!cp.tools().equals(db.tools())) {
                    diff.append("tools 不同\n  classpath: ").append(cp.tools())
                            .append("\n  db: ").append(db.tools()).append('\n');
                }
                if (!cp.body().trim().equals(db.body().trim())) {
                    diff.append("body 不同（长度 classpath=").append(cp.body().trim().length())
                            .append(", db=").append(db.body().trim().length()).append("）\n");
                }
                result.put(name, diff.toString());
            }
        }
        return result;
    }

    /**
     * 从 classpath 发布到 DB（publish + 可选 activate）。
     *
     * @param activate 是否立即激活
     * @param operator 操作人
     * @return 发布结果 name → 新版本号
     */
    public Map<String, Integer> publishFromClasspath(boolean activate, String operator) {
        Map<String, Integer> result = new LinkedHashMap<>();
        List<Skill> classpathSkills = loadClasspathSkills(classpathPattern);
        for (Skill skill : classpathSkills) {
            int ver = publish(skill.name(), skill.description(), skill.body(),
                    String.join(",", skill.tools()), null, 0, operator);
            result.put(skill.name(), ver);
            if (activate) {
                activate(skill.name(), ver, operator);
            }
        }
        return result;
    }

    // ==================== 审计 ====================

    private void audit(String name, int version, String action, String operator, String detail) {
        if (jdbc == null) {
            return;
        }
        try {
            jdbc.update(
                    "INSERT INTO agent_skill_audit (skill_name, version, action, operator, detail) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    name, version, action, operator, detail);
        } catch (RuntimeException e) {
            log.warn("技能审计写入失败: {} {} {}: {}", name, version, action, e.getMessage());
        }
    }

    // ==================== DB 读写 ====================

    /**
     * 从 DB 加载 active 技能 + 灰度配置。
     *
     * @param userId 灰度用户（null = 默认基础版本）
     */
    private List<Skill> loadFromDb(String userId) {
        if (jdbc == null) {
            return List.of();
        }
        try {
            // 1. 加载所有 active 技能
            List<Skill> dbSkills = jdbc.query(
                    "SELECT name, version, status, description, body, tools "
                            + "FROM agent_skill WHERE status = 'active' ORDER BY name",
                    (rs, rowNum) -> new Skill(
                            rs.getString("name"),
                            rs.getString("description"),
                            parseCommaList(rs.getString("tools")),
                            rs.getString("body"),
                            List.of(),
                            rs.getInt("version"),
                            rs.getString("status")));

            // 2. 加载灰度配置：找每个技能有多个 active 版本的情况
            List<CanaryConfig> configs = new ArrayList<>();
            List<Map<String, Object>> versions = jdbc.queryForList(
                    "SELECT name, version, canary_user_ids, canary_percent FROM agent_skill "
                            + "WHERE status = 'active' ORDER BY name, version");
            Map<String, List<Map<String, Object>>> byName = new LinkedHashMap<>();
            for (Map<String, Object> v : versions) {
                byName.computeIfAbsent((String) v.get("name"), k -> new ArrayList<>()).add(v);
            }
            for (Map.Entry<String, List<Map<String, Object>>> e : byName.entrySet()) {
                List<Map<String, Object>> list = e.getValue();
                if (list.size() < 2) {
                    continue;
                }
                // 最小版本 = 基础，最大版本 = 灰度
                Map<String, Object> base = list.get(0);
                Map<String, Object> canary = list.get(list.size() - 1);
                String canaryUserIdsStr = (String) canary.get("canary_user_ids");
                Set<String> canaryUsers = parseCommaList(canaryUserIdsStr)
                        .stream().collect(Collectors.toSet());
                int canaryPercent = canary.get("canary_percent") != null
                        ? ((Number) canary.get("canary_percent")).intValue() : 0;
                configs.add(new CanaryConfig(
                        e.getKey(),
                        ((Number) base.get("version")).intValue(),
                        ((Number) canary.get("version")).intValue(),
                        canaryUsers,
                        canaryPercent));
            }
            canaryConfigs.set(configs);

            // 3. 按灰度规则解析
            if (userId != null && !configs.isEmpty()) {
                Map<String, Integer> overrides = new LinkedHashMap<>();
                for (CanaryConfig cfg : configs) {
                    if (shouldUseCanary(cfg, userId)) {
                        overrides.put(cfg.name(), cfg.canaryVersion());
                    }
                }
                if (!overrides.isEmpty()) {
                    List<Skill> result = new ArrayList<>(dbSkills.size());
                    for (Skill skill : dbSkills) {
                        Integer canaryVer = overrides.get(skill.name());
                        if (canaryVer != null && canaryVer != skill.version()) {
                            Skill canary = loadSkillVersion(skill.name(), canaryVer);
                            result.add(canary != null ? canary : skill);
                        } else {
                            result.add(skill);
                        }
                    }
                    return result;
                }
            }
            return dbSkills;
        } catch (RuntimeException e) {
            log.warn("从 DB 加载技能失败: {}", e.getMessage());
            return List.of();
        }
    }

    private void seedFromClasspath(List<Skill> classpathSkills) {
        if (classpathSkills.isEmpty() || jdbc == null) {
            return;
        }
        try {
            for (Skill skill : classpathSkills) {
                String toolsStr = String.join(",", skill.tools());
                jdbc.update(
                        "INSERT INTO agent_skill (name, version, status, description, body, tools, "
                                + "canary_percent, author) "
                                + "VALUES (?, 1, 'active', ?, ?, ?, 100, 'system') "
                                + "ON DUPLICATE KEY UPDATE description = VALUES(description), "
                                + "body = VALUES(body), tools = VALUES(tools), status = 'active'",
                        skill.name(), skill.description(), skill.body(), toolsStr);
            }
            // 审计（每个技能一行）
            for (Skill skill : classpathSkills) {
                jdbc.update(
                        "INSERT INTO agent_skill_audit (skill_name, version, action, operator, detail) "
                                + "VALUES (?, 1, 'create', 'system', 'auto-seeded from classpath')",
                        skill.name());
            }
            log.info("已种子 {} 个技能到 DB", classpathSkills.size());
        } catch (RuntimeException e) {
            log.warn("技能种子写入 DB 失败（继续使用 classpath 版本）: {}", e.getMessage());
            skills.set(classpathSkills);
        }
    }

    // ==================== classpath 解析 ====================

    Skill parse(Resource resource) {
        List<String> lines;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            lines = reader.lines().map(l -> l.replace("\r", "")).collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 " + resource.getFilename(), e);
        }

        if (!lines.isEmpty() && lines.get(0).startsWith("\uFEFF")) {
            lines.set(0, lines.get(0).substring(1));
        }

        int firstDash = -1;
        for (int i = 0; i < lines.size(); i++) {
            if ("---".equals(lines.get(i).trim())) {
                firstDash = i;
                break;
            }
            if (!lines.get(i).trim().isEmpty()) {
                break;
            }
        }
        if (firstDash < 0) {
            throw new IllegalStateException("SKILL.md 缺少 frontmatter 起始分隔符 ---: " + resource.getFilename());
        }

        int secondDash = -1;
        for (int i = firstDash + 1; i < lines.size(); i++) {
            if ("---".equals(lines.get(i).trim())) {
                secondDash = i;
                break;
            }
        }
        if (secondDash < 0) {
            throw new IllegalStateException("SKILL.md 缺少 frontmatter 结束分隔符 ---: " + resource.getFilename());
        }

        Map<String, String> frontmatter = new LinkedHashMap<>();
        String currentKey = null;
        StringBuilder currentValue = null;
        for (int i = firstDash + 1; i < secondDash; i++) {
            String line = lines.get(i);
            int colonIdx = line.indexOf(":");
            if (colonIdx > 0 && !line.startsWith(" ") && !line.startsWith("\t")) {
                if (currentKey != null && currentValue != null) {
                    frontmatter.put(currentKey, currentValue.toString().trim());
                }
                String key = line.substring(0, colonIdx).trim();
                String value = line.substring(colonIdx + 1).trim();
                if (value.equals("|") || value.equals(">")) {
                    currentKey = key;
                    currentValue = new StringBuilder();
                } else if (value.isEmpty()) {
                    currentKey = key;
                    currentValue = new StringBuilder();
                } else {
                    frontmatter.put(key, value);
                    currentKey = null;
                    currentValue = null;
                }
            } else if (currentKey != null && currentValue != null) {
                if (currentValue.length() > 0) {
                    currentValue.append("\n");
                }
                currentValue.append(line);
            }
        }
        if (currentKey != null && currentValue != null) {
            frontmatter.put(currentKey, currentValue.toString().trim());
        }

        String name = frontmatter.get("name");
        String description = frontmatter.get("description");
        if (!StringUtils.hasText(name) || !StringUtils.hasText(description)) {
            throw new IllegalStateException("技能缺少 name 或 description: " + resource.getFilename());
        }

        List<String> tools = parseTools(frontmatter.get("tools"));

        StringBuilder body = new StringBuilder();
        for (int i = secondDash + 1; i < lines.size(); i++) {
            if (body.length() > 0) {
                body.append("\n");
            }
            body.append(lines.get(i));
        }

        return new Skill(name, description, tools, body.toString().trim());
    }

    private List<String> parseTools(String value) {
        return parseCommaList(value);
    }

    private List<String> parseCommaList(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        String clean = value.replace("[", "").replace("]", "")
                .replace("\"", "").replace("'", "");
        List<String> result = new ArrayList<>();
        for (String part : clean.split(",")) {
            String item = part.trim();
            if (!item.isEmpty()) {
                result.add(item);
            }
        }
        return result;
    }

    private List<Skill> loadClasspathSkills(String pattern) {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(pattern);
            List<Skill> list = new ArrayList<>();
            for (Resource resource : resources) {
                if (resource.exists()) {
                    list.add(parse(resource));
                }
            }
            list.sort(Comparator.comparing(Skill::name));
            return list;
        } catch (IOException e) {
            throw new IllegalStateException("扫描技能文件失败: " + pattern, e);
        }
    }
}
