package com.yusijia.houseclientservices.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yusijia.houseclientservices.domain.entity.AgentAudit;
import com.yusijia.houseclientservices.mapper.AgentAuditMapper;
import com.yusijia.houseclientservices.security.Authorization;
import com.yusijia.houseclientservices.security.PermissionPoints;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 智能体审计查询（P0-5）。
 * <p>
 * 仅坐席（{@code audit:read}）：按 conversationId 倒序查最近 100 轮，
 * 用于反查"哪一轮、哪些技能、调了什么工具、参数（脱敏）、耗时、是否经确认"。
 * 先不建 UI，前端/坐席用接口或 SQL 模板即可。
 */
@RestController
@RequestMapping("/api/agent/audit")
public class AuditController {

    private static final int PAGE_SIZE = 100;

    private final AgentAuditMapper auditMapper;

    public AuditController(AgentAuditMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    @GetMapping
    public List<AgentAudit> list(@RequestParam String conversationId, HttpServletRequest request) {
        Authorization.requirePermission(request, PermissionPoints.AUDIT_READ);
        return auditMapper.selectList(new LambdaQueryWrapper<AgentAudit>()
                .eq(AgentAudit::getCid, conversationId)
                .orderByDesc(AgentAudit::getTurn)
                .last("LIMIT " + PAGE_SIZE));
    }
}
