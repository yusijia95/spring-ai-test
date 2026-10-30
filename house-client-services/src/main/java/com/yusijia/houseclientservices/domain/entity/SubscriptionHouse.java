package com.yusijia.houseclientservices.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 认购房屋（含快照）
 */
@TableName("subscription_house")
public class SubscriptionHouse {

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
     * 房屋ID -> house_room.id（溯源键）
     */
    @TableField("house_id")
    private Long houseId;

    /**
     * 是否主房屋
     */
    @TableField("is_primary")
    private Boolean isPrimary;

    /**
     * 认购时完整坐落
     */
    @TableField("house_location_snap")
    private String houseLocationSnap;

    /**
     * 认购时幢号
     */
    @TableField("building_no_snap")
    private String buildingNoSnap;

    /**
     * 认购时房号
     */
    @TableField("room_no_snap")
    private String roomNoSnap;

    /**
     * 认购时建筑面积
     */
    @TableField("building_area_snap")
    private BigDecimal buildingAreaSnap;

    /**
     * 认购时套内面积
     */
    @TableField("inner_area_snap")
    private BigDecimal innerAreaSnap;

    /**
     * 认购时分摊面积
     */
    @TableField("shared_area_snap")
    private BigDecimal sharedAreaSnap;

    /**
     * 认购时单价
     */
    @TableField("unit_price_snap")
    private BigDecimal unitPriceSnap;

    /**
     * 认购时该房屋总价
     */
    @TableField("total_price_snap")
    private BigDecimal totalPriceSnap;

    /**
     * 认购时项目名称
     */
    @TableField("project_name_snap")
    private String projectNameSnap;

    /**
     * 认购时开发企业名称
     */
    @TableField("developer_name_snap")
    private String developerNameSnap;

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

    public SubscriptionHouse() {
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

    public Long getHouseId() {
        return houseId;
    }

    public void setHouseId(Long houseId) {
        this.houseId = houseId;
    }

    public Boolean getIsPrimary() {
        return isPrimary;
    }

    public void setIsPrimary(Boolean isPrimary) {
        this.isPrimary = isPrimary;
    }

    public String getHouseLocationSnap() {
        return houseLocationSnap;
    }

    public void setHouseLocationSnap(String houseLocationSnap) {
        this.houseLocationSnap = houseLocationSnap;
    }

    public String getBuildingNoSnap() {
        return buildingNoSnap;
    }

    public void setBuildingNoSnap(String buildingNoSnap) {
        this.buildingNoSnap = buildingNoSnap;
    }

    public String getRoomNoSnap() {
        return roomNoSnap;
    }

    public void setRoomNoSnap(String roomNoSnap) {
        this.roomNoSnap = roomNoSnap;
    }

    public BigDecimal getBuildingAreaSnap() {
        return buildingAreaSnap;
    }

    public void setBuildingAreaSnap(BigDecimal buildingAreaSnap) {
        this.buildingAreaSnap = buildingAreaSnap;
    }

    public BigDecimal getInnerAreaSnap() {
        return innerAreaSnap;
    }

    public void setInnerAreaSnap(BigDecimal innerAreaSnap) {
        this.innerAreaSnap = innerAreaSnap;
    }

    public BigDecimal getSharedAreaSnap() {
        return sharedAreaSnap;
    }

    public void setSharedAreaSnap(BigDecimal sharedAreaSnap) {
        this.sharedAreaSnap = sharedAreaSnap;
    }

    public BigDecimal getUnitPriceSnap() {
        return unitPriceSnap;
    }

    public void setUnitPriceSnap(BigDecimal unitPriceSnap) {
        this.unitPriceSnap = unitPriceSnap;
    }

    public BigDecimal getTotalPriceSnap() {
        return totalPriceSnap;
    }

    public void setTotalPriceSnap(BigDecimal totalPriceSnap) {
        this.totalPriceSnap = totalPriceSnap;
    }

    public String getProjectNameSnap() {
        return projectNameSnap;
    }

    public void setProjectNameSnap(String projectNameSnap) {
        this.projectNameSnap = projectNameSnap;
    }

    public String getDeveloperNameSnap() {
        return developerNameSnap;
    }

    public void setDeveloperNameSnap(String developerNameSnap) {
        this.developerNameSnap = developerNameSnap;
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
