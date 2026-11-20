package com.yusijia.houseclientservices.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 会话（一人多会话，P0-1 归属校验用）。
 * <p>
 * {@code id} 即前端传给 /api/agent/chat 的 conversationId，
 * 也是记忆层（Redis/PG）的 conversationId。
 */
@TableName("conversation")
public class Conversation {

    /** 会话ID（雪花，应用生成）。 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 归属用户ID（user_account.id）。 */
    @TableField("owner_user_id")
    private Long ownerUserId;

    /** 会话标题（可后补）。 */
    @TableField("title")
    private String title;

    /** 状态：1进行中 0已归档。 */
    @TableField("status")
    private Integer status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
