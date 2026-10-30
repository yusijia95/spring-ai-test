package com.yusijia.houseclientservices.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 认购人（含快照）
 */
@TableName("subscription_person")
public class SubscriptionPerson {

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
     * 个人ID -> person.id（溯源键）
     */
    @TableField("person_id")
    private Long personId;

    /**
     * 购买主体类型：1个人 2单位（预留）
     */
    @TableField("buyer_type")
    private Integer buyerType;

    /**
     * 是否主认购人
     */
    @TableField("is_primary")
    private Boolean isPrimary;

    /**
     * 产权份额（0.0000~1.0000）
     */
    @TableField("share_ratio")
    private BigDecimal shareRatio;

    /**
     * 认购时姓名快照
     */
    @TableField("person_name_snap")
    private String personNameSnap;

    /**
     * 认购时证件类别
     */
    @TableField("id_type_snap")
    private Integer idTypeSnap;

    /**
     * 认购时证件号码
     */
    @TableField("id_no_snap")
    private String idNoSnap;

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

    public SubscriptionPerson() {
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

    public Long getPersonId() {
        return personId;
    }

    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    public Integer getBuyerType() {
        return buyerType;
    }

    public void setBuyerType(Integer buyerType) {
        this.buyerType = buyerType;
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

    public String getPersonNameSnap() {
        return personNameSnap;
    }

    public void setPersonNameSnap(String personNameSnap) {
        this.personNameSnap = personNameSnap;
    }

    public Integer getIdTypeSnap() {
        return idTypeSnap;
    }

    public void setIdTypeSnap(Integer idTypeSnap) {
        this.idTypeSnap = idTypeSnap;
    }

    public String getIdNoSnap() {
        return idNoSnap;
    }

    public void setIdNoSnap(String idNoSnap) {
        this.idNoSnap = idNoSnap;
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
