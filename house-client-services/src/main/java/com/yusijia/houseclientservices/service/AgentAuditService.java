package com.yusijia.houseclientservices.service;

import com.yusijia.houseclientservices.domain.audit.ToolCallAudit;
import com.yusijia.houseclientservices.domain.entity.AgentAudit;
import com.yusijia.houseclientservices.mapper.AgentAuditMapper;
import com.yusijia.houseclientservices.security.PiiMasker;
import com.yusijia.houseclientservices.security.UserContext;
import com.yusijia.houseclientservices.tool.ToolExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 智能体审计落表（P0-5，每轮一行，只追加）。
 * <p>
 * 写入约定：
 * <ul>
 *   * <b>同步写</b>：请求线程内 insert，不用 MQ——"请求线程发完消息就 200、进程崩在落盘前"
 *       是审计最不能接受的状态；量级（坐席 × 会话，个位到几十 QPS）一张 MySQL 表直接写即可；</li>
 *   * <b>旁路</b>：写失败只 warn 不抛出，不影响主流程对话（审计是旁路，业务写操作不适用旁路）；</li>
 *   * <b>脱敏</b>：args / result 落库前 {@link PiiMasker#mask} + 截断，禁止 PII 明文入库；</li>
 *   * <b>turn</b>：{@code MAX(turn)+1}，会话内自增。</li>
 * </ul>
 */
@Service
public class AgentAuditService {

    private static final Logger log = LoggerFactory.getLogger(AgentAuditService.class);

    private static final int ARGS_MAX = 4000;
    private static final int RESULT_MAX = 1000;

    private final AgentAuditMapper auditMapper;

    public AgentAuditService(AgentAuditMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    /**
     * 记一轮审计。调用方在请求结束（同步路径返回前 / 流式路径 doOnComplete）时调用。
     *
     * @param user      本轮登录用户
     * @param cid       会话ID
     * @param skills    本轮命中技能名（可为空列表）
     * @param exec      本轮工具上下文（提供工具调用审计 + confirmed 标记），可为 null
     * @param model     本轮 chat 模型名
     * @param latencyMs 本轮端到端耗时
     */
    public void record(UserContext user, String cid, List<String> skills,
                       ToolExecutionContext exec, String model, long latencyMs) {
        if (user == null || !StringUtils.hasText(cid)) {
            return;
        }
        try {
            List<ToolCallAudit> calls = exec == null ? List.of() : exec.auditCalls();
            AgentAudit row = new AgentAudit();
            row.setCid(cid);
            row.setUserId(user.userId());
            row.setTurn(nextTurn(cid));
            row.setSkills(String.join(",", skills));
            row.setTool(calls.stream().map(ToolCallAudit::tool).collect(Collectors.joining(",")));
            row.setArgs(PiiMasker.truncate(maskCalls(calls, true), ARGS_MAX));
            row.setResult(PiiMasker.truncate(maskCalls(calls, false), RESULT_MAX));
            row.setModel(model);
            row.setLatencyMs((int) latencyMs);
            row.setConfirmed(exec != null && exec.writeConfirmed());
            auditMapper.insert(row);
        } catch (Exception e) {
            // 审计是旁路：写失败不中断对话（写成功才算 200 的业务写操作不走这里）
            log.warn("审计写入失败: cid={}, userId={}, error={}", cid, user.userId(), e.getMessage());
        }
    }

    /** 该会话下一轮 turn 号（无记录 = 1）。 */
    private int nextTurn(String cid) {
        return auditMapper.maxTurn(cid) + 1;
    }

    /**
     * 多个工具调用按 {@code tool: args\n...} 形态拼接成单段文本，整体脱敏后落库。
     * 无调用时返回 null（列留空，表示本轮未调工具）。
     */
    private String maskCalls(List<ToolCallAudit> calls, boolean argsSide) {
        if (calls.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (ToolCallAudit call : calls) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(call.tool()).append(": ");
            sb.append(argsSide ? call.args() : call.result());
        }
        return PiiMasker.mask(sb.toString());
    }
}
