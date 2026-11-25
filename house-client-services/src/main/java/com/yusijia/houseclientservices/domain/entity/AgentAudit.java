package com.yusijia.houseclientservices.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 智能体审计（P0-5，每轮一行，只追加）。
 * <p>
 * 任一认购单可反查：哪一轮、哪些技能、调了什么工具、参数（脱敏）、耗时、是否经确认。
 * {@code args}/{@code result} 落库前必须经过 {@code PiiMasker} 脱敏 + 截断。
 */
@TableName("agent_audit")
public class AgentAudit {

    /** 审计ID（雪花，应用生成）。 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 会话ID（conversation.id）。 */
    @TableField("cid")
    private String cid;

    /** 发起用户ID（user_account.id）。 */
    @TableField("user_id")
    private Long userId;

    /** 该会话内的轮次序号（自增）。 */
    @TableField("turn")
    private Integer turn;

    /** 本轮命中技能，逗号分隔（可空）。 */
    @TableField("skills")
    private String skills;

    /** 本轮调用的工具，逗号分隔（可空）。 */
    @TableField("tool")
    private String tool;

    /** 工具入参（脱敏+截断）。 */
    @TableField("args")
    private String args;

    /** 工具返回值（脱敏+截断）。 */
    @TableField("result")
    private String result;

    /** 本轮使用的 chat 模型名。 */
    @TableField("model")
    private String model;

    /** 本轮端到端耗时（毫秒）。 */
    @TableField("latency_ms")
    private Integer latencyMs;

    /** 本轮写操作是否经客户二次确认。 */
    @TableField("confirmed")
    private Boolean confirmed;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCid() {
        return cid;
    }

    public void setCid(String cid) {
        this.cid = cid;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getTurn() {
        return turn;
    }

    public void setTurn(Integer turn) {
        this.turn = turn;
    }

    public String getSkills() {
        return skills;
    }

    public void setSkills(String skills) {
        this.skills = skills;
    }

    public String getTool() {
        return tool;
    }

    public void setTool(String tool) {
        this.tool = tool;
    }

    public String getArgs() {
        return args;
    }

    public void setArgs(String args) {
        this.args = args;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Boolean getConfirmed() {
        return confirmed;
    }

    public void setConfirmed(Boolean confirmed) {
        this.confirmed = confirmed;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
