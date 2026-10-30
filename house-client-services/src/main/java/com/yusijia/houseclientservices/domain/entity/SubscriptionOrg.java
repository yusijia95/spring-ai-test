package com.yusijia.houseclientservices.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 认购单位（含快照）
 */
@TableName("subscription_org")
public class SubscriptionOrg {

    /**
     * 关联ID（雪花）
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 业务号 -> subscription.id
     */
    @TableField("subscription_id")
    private Long subscriptionId;

    /**
     * 单位ID -> org_unit.id（溯源键）
     */
    @TableField("org_id")
    private Long orgId;

    /**
     * 是否主购买主体
     */
    @TableField("is_primary")
    private Boolean isPrimary;

    /**
     * 产权份额
     */
    @TableField("share_ratio")
    private BigDecimal shareRatio;

    /**
     * 认购时单位全称
     */
    @TableField("unit_name_snap")
    private String unitNameSnap;

    /**
     * 认购时单位证件号码
     */
    @TableField("unit_id_no_snap")
    private String unitIdNoSnap;

    /**
     * 认购时联系电话
     */
    @TableField("contact_snap")
    private String contactSnap;

    /**
     * 快照时间
     */
    @TableField("snapshot_at")
    private LocalDateTime snapshotAt;

    /**
     * 1有效 0已移除
     */
    @TableField("status")
    private Integer status;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    public SubscriptionOrg() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSubscriptionId() {
        return subscriptionId;
    }

    public void setSubscriptionId(Long subscriptionId) {
        this.subscriptionId = subscriptionId;
    }

    public Long getOrgId() {
        return orgId;
    }

    public void setOrgId(Long orgId) {
        this.orgId = orgId;
    }

    public Boolean getIsPrimary() {
        return isPrimary;
    }

    public void setIsPrimary(Boolean isPrimary) {
        this.isPrimary = isPrimary;
    }

    public BigDecimal getShareRatio() {
        return shareRatio;
    }

    public void setShareRatio(BigDecimal shareRatio) {
        this.shareRatio = shareRatio;
    }

    public String getUnitNameSnap() {
        return unitNameSnap;
    }

    public void setUnitNameSnap(String unitNameSnap) {
        this.unitNameSnap = unitNameSnap;
    }

    public String getUnitIdNoSnap() {
        return unitIdNoSnap;
    }

    public void setUnitIdNoSnap(String unitIdNoSnap) {
        this.unitIdNoSnap = unitIdNoSnap;
    }

    public String getContactSnap() {
        return contactSnap;
    }

    public void setContactSnap(String contactSnap) {
        this.contactSnap = contactSnap;
    }

    public LocalDateTime getSnapshotAt() {
        return snapshotAt;
    }

    public void setSnapshotAt(LocalDateTime snapshotAt) {
        this.snapshotAt = snapshotAt;
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
}
