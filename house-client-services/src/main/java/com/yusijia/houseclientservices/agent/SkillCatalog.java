package com.yusijia.houseclientservices.agent;

import com.yusijia.houseclientservices.db.PostgresDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * 运行时：缓存技能列表，{@link #reload()} 可手动刷新（DB 变更后调）。
 * 选择器每轮用 {@link #index()}，不查库。
 */
@Component
public class SkillCatalog {

    private static final Logger log = LoggerFactory.getLogger(SkillCatalog.class);

    private final String classpathPattern;
    private final JdbcTemplate jdbc;
    private final AtomicReference<List<Skill>> skills = new AtomicReference<>(List.of());

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
        // 无 DB 时直接从 classpath 加载
        List<Skill> classpathSkills = loadClasspathSkills(pattern);
        if (classpathSkills.isEmpty()) {
            throw new IllegalStateException("未加载到任何技能，请检查 src/main/resources/skills/<name>/SKILL.md 是否存在");
        }
        skills.set(classpathSkills);
        log.info("加载技能(测试模式): {}", classpathSkills.stream()
                .map(Skill::name).collect(Collectors.joining(", ")));
    }

    /**
     * 启动时加载：classpath 种子 → DB 覆盖 → DB 为空时写种子。
     */
    @PostConstruct
    void init() {
        if (jdbc == null) {
            // 测试模式：构造器已从 classpath 加载，跳过 DB 初始化
            return;
        }
        List<Skill> classpathSkills = loadClasspathSkills(classpathPattern);
        log.info("classpath 技能种子: {}", classpathSkills.stream()
                .map(s -> s.name()).collect(Collectors.joining(", ")));

        // 查 DB 是否有 active 技能
        List<Skill> dbSkills = loadFromDb();
        if (!dbSkills.isEmpty()) {
            log.info("DB 技能已就绪: {}", dbSkills.stream()
                    .map(s -> s.name() + "(v" + s.version() + ")").collect(Collectors.joining(", ")));
            skills.set(dbSkills);
        } else {
            // DB 无 active 技能 → 写入 classpath 种子
            seedFromClasspath(classpathSkills);
            dbSkills = loadFromDb();
            skills.set(dbSkills);
            log.info("技能已从 classpath 种子到 DB: {}", dbSkills.stream()
                    .map(Skill::name).collect(Collectors.joining(", ")));
        }
    }

    /**
     * 所有 active 技能（按 name 排序）。
     */
    public List<Skill> all() {
        return skills.get();
    }

    /**
     * 按名称取技能，按入参顺序去重返回。
     */
    public List<Skill> byNames(Collection<String> names) {
        Map<String, Skill> lookup = skills.get().stream().collect(Collectors.toMap(Skill::name, s -> s));
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
     * 给选择器用的技能清单，每行 {@code - name: description}。
     */
    public String index() {
        return skills.get().stream()
                .map(s -> "- " + s.name() + ": " + s.description())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 从 DB 重新加载技能（变更事件后调）。
     */
    public void reload() {
        List<Skill> dbSkills = loadFromDb();
        skills.set(dbSkills);
        log.info("技能目录已刷新: {} 个 active 技能", dbSkills.size());
    }

    // ==================== DB 读写 ====================

    private List<Skill> loadFromDb() {
        try {
            return jdbc.query(
                    "SELECT name, version, status, description, body, tools "
                            + "FROM agent_skill WHERE status = 'active' ORDER BY name",
                    (rs, rowNum) -> {
                        String toolsStr = rs.getString("tools");
                        List<String> tools = parseCommaList(toolsStr);
                        return new Skill(
                                rs.getString("name"),
                                rs.getString("description"),
                                tools,
                                rs.getString("body"),
                                List.of(), // permissionPoints 暂从 DB 的 tools 字段推导
                                rs.getInt("version"),
                                rs.getString("status"));
                    });
        } catch (RuntimeException e) {
            log.warn("从 DB 加载技能失败: {}", e.getMessage());
            return List.of();
        }
    }

    private void seedFromClasspath(List<Skill> classpathSkills) {
        if (classpathSkills.isEmpty()) {
            return;
        }
        try {
            for (Skill skill : classpathSkills) {
                String toolsStr = String.join(",", skill.tools());
                jdbc.update(
                        "INSERT INTO agent_skill (name, version, status, description, body, tools, author) "
                                + "VALUES (?, 1, 'active', ?, ?, ?, 'system') "
                                + "ON DUPLICATE KEY UPDATE description = VALUES(description), "
                                + "body = VALUES(body), tools = VALUES(tools), status = 'active'",
                        skill.name(), skill.description(), skill.body(), toolsStr);
            }
            // 审计
            jdbc.update(
                    "INSERT INTO agent_skill_audit (skill_name, version, action, operator, detail) "
                            + "VALUES (?, 1, 'create', 'system', 'auto-seeded from classpath')");
            log.info("已种子 {} 个技能到 DB", classpathSkills.size());
        } catch (RuntimeException e) {
            log.warn("技能种子写入 DB 失败（继续使用 classpath 版本）: {}", e.getMessage());
            skills.set(classpathSkills);
        }
    }

    // ==================== classpath 解析 ====================

    /**
     * 解析单个 SKILL.md 资源为 Skill 对象。
     */
    Skill parse(Resource resource) {
        List<String> lines;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            lines = reader.lines().map(l -> l.replace("\r", "")).collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 " + resource.getFilename(), e);
        }

        // 去掉 UTF-8 BOM
        if (!lines.isEmpty() && lines.get(0).startsWith("\uFEFF")) {
            lines.set(0, lines.get(0).substring(1));
        }

        // 找到首个非空行必须是 ---
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

        // 找到第二个 --- 作为 frontmatter 结束
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

        // 解析 frontmatter
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

    /**
     * 解析 tools 字段：支持 "a, b, c" 和 "[a, b, c]" 两种写法。
     */
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
