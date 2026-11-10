package com.yusijia.houseclientservices.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Value;

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
import java.util.stream.Collectors;

/**
 * 技能目录：启动时扫描 classpath 下的 SKILL.md，解析 frontmatter，提供索引与查询。
 * <p>
 * 新增能力只需在 skills 目录加一个子目录 + SKILL.md，无需改任何 Java 代码。
 */
@Component
public class SkillCatalog {

    private static final Logger log = LoggerFactory.getLogger(SkillCatalog.class);

    private final List<Skill> skills;

    public SkillCatalog(@Value("${agent.skills.pattern:classpath*:skills/*/SKILL.md}") String pattern) {
        this.skills = loadSkills(pattern);
        if (this.skills.isEmpty()) {
            throw new IllegalStateException("未加载到任何技能，请检查 src/main/resources/skills/<name>/SKILL.md 是否存在");
        }
        log.info("加载技能: {}", this.skills.stream()
                .map(s -> s.name() + "(" + s.tools().size() + "个工具)")
                .collect(Collectors.joining(", ")));
    }

    /**
     * 所有技能（按 name 排序，顺序稳定）。
     */
    public List<Skill> all() {
        return skills;
    }

    /**
     * 按名称取技能，按入参顺序去重返回。
     * 未知名字记 warn 后跳过。
     */
    public List<Skill> byNames(Collection<String> names) {
        Map<String, Skill> lookup = skills.stream().collect(Collectors.toMap(Skill::name, s -> s));
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
     * 给选择器用的技能清单，每行 `- name: description`。
     */
    public String index() {
        return skills.stream()
                .map(s -> "- " + s.name() + ": " + s.description())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 解析单个 SKILL.md 资源为 Skill 对象。
     * 不引入 snakeyaml：手写解析 frontmatter（--- 包裹的 key: value）。
     */
    private Skill parse(Resource resource) {
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
            // 检查是否是新 key: value
            int colonIdx = line.indexOf(":");
            if (colonIdx > 0 && !line.startsWith(" ") && !line.startsWith("\t")) {
                // 保存上一个 key 的多行值
                if (currentKey != null && currentValue != null) {
                    frontmatter.put(currentKey, currentValue.toString().trim());
                }
                String key = line.substring(0, colonIdx).trim();
                String value = line.substring(colonIdx + 1).trim();
                if (value.equals("|") || value.equals(">")) {
                    currentKey = key;
                    currentValue = new StringBuilder();
                } else if (value.isEmpty()) {
                    // 值为空，可能是多行文本的开始
                    currentKey = key;
                    currentValue = new StringBuilder();
                } else {
                    frontmatter.put(key, value);
                    currentKey = null;
                    currentValue = null;
                }
            } else if (currentKey != null && currentValue != null) {
                // 缩进行，属于上一个 key 的多行值
                if (currentValue.length() > 0) {
                    currentValue.append("\n");
                }
                currentValue.append(line);
            }
        }
        // 保存最后一个 key
        if (currentKey != null && currentValue != null) {
            frontmatter.put(currentKey, currentValue.toString().trim());
        }

        String name = frontmatter.get("name");
        String description = frontmatter.get("description");
        if (!StringUtils.hasText(name) || !StringUtils.hasText(description)) {
            throw new IllegalStateException("技能缺少 name 或 description: " + resource.getFilename());
        }

        // 解析 tools
        List<String> tools = parseTools(frontmatter.get("tools"));

        // 解析 body（第二个 --- 之后的内容）
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

    private List<Skill> loadSkills(String pattern) {
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
