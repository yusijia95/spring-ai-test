package com.yusijia.houseclientservices.controller;

import com.yusijia.houseclientservices.agent.Skill;
import com.yusijia.houseclientservices.agent.SkillCatalog;
import com.yusijia.houseclientservices.security.Authorization;
import com.yusijia.houseclientservices.security.PermissionPoints;
import com.yusijia.houseclientservices.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能管理（P1-4 同步工具 + 版本灰度）。
 * <p>
 * 坐席/开发者用：
 * <ul>
 *   <li>{@code GET  /api/agent/skills} — 列出所有技能（含版本/状态/灰度）</li>
 *   <li>{@code GET  /api/agent/skills/diff} — 对比 classpath 与 DB 的差异</li>
 *   <li>{@code GET  /api/agent/skills/export} — 导出 DB active 为 SKILL.md 格式</li>
 *   <li>{@code POST /api/agent/skills/publish} — 从 classpath 发布到 DB</li>
 *   <li>{@code POST /api/agent/skills/{name}/publish} — 发布单个技能新版本</li>
 *   <li>{@code POST /api/agent/skills/{name}/activate/{version}} — 激活版本</li>
 *   <li>{@code POST /api/agent/skills/{name}/deactivate/{version}} — 停用版本</li>
 *   <li>{@code POST /api/agent/skills/{name}/rollback} — 回滚到上一版本</li>
 *   <li>{@code POST /api/agent/skills/{name}/canary/{version}} — 更新灰度配置</li>
 *   <li>{@code POST /api/agent/skills/reload} — 手动刷新技能缓存</li>
 * </ul>
 * <p>
 * 权限：仅坐席（{@code AUDIT_READ} 权限点，与审计查询同级）。
 */
@RestController
@RequestMapping("/api/agent/skills")
public class SkillAdminController {

    private final SkillCatalog catalog;

    public SkillAdminController(SkillCatalog catalog) {
        this.catalog = catalog;
    }

    private UserContext requireAdmin(HttpServletRequest http) {
        return Authorization.requirePermission(http, PermissionPoints.AUDIT_READ);
    }

    /** 列出所有 active 技能（含版本/状态/工具/灰度信息）。 */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(HttpServletRequest http) {
        requireAdmin(http);
        List<Skill> skills = catalog.all();
        List<Map<String, Object>> result = skills.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("version", s.version());
            m.put("status", s.status());
            m.put("description", s.description());
            m.put("tools", s.tools());
            return m;
        }).toList();
        return ResponseEntity.ok(result);
    }

    /** 对比 classpath SKILL.md 与 DB active 的差异。 */
    @GetMapping("/diff")
    public ResponseEntity<Map<String, String>> diff(HttpServletRequest http) {
        requireAdmin(http);
        return ResponseEntity.ok(catalog.diff());
    }

    /** 导出 DB active 技能为 SKILL.md 格式（用于 code review）。 */
    @GetMapping("/export")
    public ResponseEntity<Map<String, String>> export(HttpServletRequest http) {
        requireAdmin(http);
        return ResponseEntity.ok(catalog.exportActive());
    }

    /** 从 classpath 发布到 DB（publish + 可选 activate）。 */
    @PostMapping("/publish")
    public ResponseEntity<Map<String, Object>> publishFromClasspath(
            @RequestBody PublishRequest body, HttpServletRequest http) {
        UserContext user = requireAdmin(http);
        Map<String, Integer> result = catalog.publishFromClasspath(
                body.activate(), user.username());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("published", result);
        resp.put("operator", user.username());
        return ResponseEntity.ok(resp);
    }

    /** 发布单个技能新版本。 */
    @PostMapping("/{name}/publish")
    public ResponseEntity<Map<String, Object>> publishOne(
            @PathVariable String name,
            @RequestBody SkillPublishRequest body,
            HttpServletRequest http) {
        UserContext user = requireAdmin(http);
        int version = catalog.publish(name, body.description(), body.body(),
                body.tools(), body.canaryUserIds(), body.canaryPercent(), user.username());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("name", name);
        resp.put("version", version);
        resp.put("status", "draft");
        resp.put("operator", user.username());
        return ResponseEntity.ok(resp);
    }

    /** 激活指定版本。 */
    @PostMapping("/{name}/activate/{version}")
    public ResponseEntity<Map<String, String>> activate(
            @PathVariable String name, @PathVariable int version,
            HttpServletRequest http) {
        UserContext user = requireAdmin(http);
        catalog.activate(name, version, user.username());
        return ResponseEntity.ok(Map.of("name", name, "version", String.valueOf(version),
                "action", "activated"));
    }

    /** 停用指定版本。 */
    @PostMapping("/{name}/deactivate/{version}")
    public ResponseEntity<Map<String, String>> deactivate(
            @PathVariable String name, @PathVariable int version,
            HttpServletRequest http) {
        UserContext user = requireAdmin(http);
        catalog.deactivate(name, version, user.username());
        return ResponseEntity.ok(Map.of("name", name, "version", String.valueOf(version),
                "action", "deactivated"));
    }

    /** 回滚到上一版本。 */
    @PostMapping("/{name}/rollback")
    public ResponseEntity<Map<String, Object>> rollback(
            @PathVariable String name, HttpServletRequest http) {
        UserContext user = requireAdmin(http);
        int version = catalog.rollback(name, user.username());
        return ResponseEntity.ok(Map.of("name", name, "rolledBackTo", version));
    }

    /** 更新灰度配置。 */
    @PostMapping("/{name}/canary/{version}")
    public ResponseEntity<Map<String, Object>> setCanary(
            @PathVariable String name, @PathVariable int version,
            @RequestBody CanaryRequest body,
            HttpServletRequest http) {
        UserContext user = requireAdmin(http);
        catalog.setCanary(name, version, body.canaryUserIds(), body.canaryPercent(), user.username());
        return ResponseEntity.ok(Map.of("name", name, "version", version,
                "canaryPercent", body.canaryPercent()));
    }

    /** 手动刷新技能缓存。 */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, String>> reload(HttpServletRequest http) {
        requireAdmin(http);
        catalog.reload();
        return ResponseEntity.ok(Map.of("status", "reloaded",
                "activeSkills", String.valueOf(catalog.all().size())));
    }

    // ==================== 请求体 ====================

    public record PublishRequest(boolean activate) {
        public PublishRequest() { this(false); }
    }

    public record SkillPublishRequest(String description, String body, String tools,
                                       String canaryUserIds, int canaryPercent) {
        public SkillPublishRequest() {
            this(null, null, null, null, 0);
        }
    }

    public record CanaryRequest(String canaryUserIds, int canaryPercent) {}
}
