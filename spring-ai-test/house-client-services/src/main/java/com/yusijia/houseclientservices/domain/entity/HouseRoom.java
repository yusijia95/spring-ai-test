package com.yusijia.houseclientservices.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 房屋
 */
@TableName("house_room")
public class HouseRoom {

    /**
     * 房屋ID（雪花）
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 幢ID
     */
    @TableField("building_id")
    private Long buildingId;

    /**
     * 房号
     */
    @TableField("room_no")
    private String roomNo;

    /**
     * 房屋坐落（幢坐落 + 房号）
     */
    @TableField("full_address")
    private String fullAddress;

    /**
     * 建筑面积（㎡）
     */
    @TableField("building_area")
    private BigDecimal buildingArea;

    /**
     * 套内建筑面积（㎡）
     */
    @TableField("inner_area")
    private BigDecimal innerArea;

    /**
     * 分摊建筑面积（㎡）
     */
    @TableField("shared_area")
    private BigDecimal sharedArea;

    /**
     * 规划用途：1住宅 2商业 3办公 4车位 9其他
     */
    @TableField("planned_usage")
    private Integer plannedUsage;

    /**
     * 当前有效认购ID（空=可售）
     */
    @TableField("active_subscription_id")
    private Long activeSubscriptionId;

    /**
     * 参考单价（元/㎡）
     */
    @TableField("reference_unit_price")
    private BigDecimal referenceUnitPrice;

    /**
     * 建立时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除标志
     */
    @TableField("deleted_flag")
    private Long deletedFlag;

    public HouseRoom() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBuildingId() {
        return buildingId;
    }

    public void setBuildingId(Long buildingId) {
        this.buildingId = buildingId;
    }

    public String getRoomNo() {
        return roomNo;
    }

    public void setRoomNo(String roomNo) {
        this.roomNo = roomNo;
    }

    public String getFullAddress() {
        return fullAddress;
    }

    public void setFullAddress(String fullAddress) {
        this.fullAddress = fullAddress;
    }

    public BigDecimal getBuildingArea() {
        return buildingArea;
    }

    public void setBuildingArea(BigDecimal buildingArea) {
        this.buildingArea = buildingArea;
    }

    public BigDecimal getInnerArea() {
        return innerArea;
    }

    public void setInnerArea(BigDecimal innerArea) {
        this.innerArea = innerArea;
    }

    public BigDecimal getSharedArea() {
        return sharedArea;
    }

    public void setSharedArea(BigDecimal sharedArea) {
        this.sharedArea = sharedArea;
    }

    public Integer getPlannedUsage() {
        return plannedUsage;
    }

    public void setPlannedUsage(Integer plannedUsage) {
        this.plannedUsage = plannedUsage;
    }

    public Long getActiveSubscriptionId() {
        return activeSubscriptionId;
    }

    public void setActiveSubscriptionId(Long activeSubscriptionId) {
        this.activeSubscriptionId = activeSubscriptionId;
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

    public Long getDeletedFlag() {
        return deletedFlag;
    }

    public void setDeletedFlag(Long deletedFlag) {
        this.deletedFlag = deletedFlag;
    }

    public BigDecimal getReferenceUnitPrice() {
        return referenceUnitPrice;
    }

    public void setReferenceUnitPrice(BigDecimal referenceUnitPrice) {
        this.referenceUnitPrice = referenceUnitPrice;
    }
}
