package com.yusijia.houseclientservices.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.yusijia.houseclientservices.domain.entity.HouseBuilding;
import com.yusijia.houseclientservices.domain.entity.HouseProject;
import com.yusijia.houseclientservices.domain.entity.HouseRoom;
import com.yusijia.houseclientservices.domain.entity.OrgUnit;
import com.yusijia.houseclientservices.domain.entity.Person;
import com.yusijia.houseclientservices.domain.entity.PersonContact;
import com.yusijia.houseclientservices.domain.entity.Subscription;
import com.yusijia.houseclientservices.domain.entity.SubscriptionHouse;
import com.yusijia.houseclientservices.domain.entity.SubscriptionOrg;
import com.yusijia.houseclientservices.domain.entity.SubscriptionPerson;
import com.yusijia.houseclientservices.domain.view.SubscriptionView;
import com.yusijia.houseclientservices.exception.BizException;
import com.yusijia.houseclientservices.mapper.HouseBuildingMapper;
import com.yusijia.houseclientservices.mapper.HouseProjectMapper;
import com.yusijia.houseclientservices.mapper.HouseRoomMapper;
import com.yusijia.houseclientservices.mapper.OrgUnitMapper;
import com.yusijia.houseclientservices.mapper.PersonContactMapper;
import com.yusijia.houseclientservices.mapper.PersonMapper;
import com.yusijia.houseclientservices.mapper.SubscriptionHouseMapper;
import com.yusijia.houseclientservices.mapper.SubscriptionMapper;
import com.yusijia.houseclientservices.mapper.SubscriptionOrgMapper;
import com.yusijia.houseclientservices.mapper.SubscriptionPersonMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 认购业务服务：创建、查询、撤销。
 *
 * 关键约束：
 * 1. 创建时先插入业务主表，再通过 house_room.active_subscription_id 抢占房屋，
 *    抢占失败（affected = 0）立即抛异常回滚，靠唯一索引兜底并发。
 * 2. 购买方、房屋信息在创建事务内一次性写入快照列，之后不再刷新。
 * 3. 撤销只改状态并释放房屋，不物理删除业务凭证。
 */
@Service
public class SubscriptionService {

    private static final int STATUS_DRAFT = 0;
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_DONE = 2;
    private static final int STATUS_CANCELLED = 3;

    private final SubscriptionMapper subscriptionMapper;
    private final SubscriptionHouseMapper subscriptionHouseMapper;
    private final SubscriptionPersonMapper subscriptionPersonMapper;
    private final SubscriptionOrgMapper subscriptionOrgMapper;
    private final HouseRoomMapper houseRoomMapper;
    private final HouseBuildingMapper houseBuildingMapper;
    private final HouseProjectMapper houseProjectMapper;
    private final PersonMapper personMapper;
    private final PersonContactMapper personContactMapper;
    private final OrgUnitMapper orgUnitMapper;

    public SubscriptionService(SubscriptionMapper subscriptionMapper,
                               SubscriptionHouseMapper subscriptionHouseMapper,
                               SubscriptionPersonMapper subscriptionPersonMapper,
                               SubscriptionOrgMapper subscriptionOrgMapper,
                               HouseRoomMapper houseRoomMapper,
                               HouseBuildingMapper houseBuildingMapper,
                               HouseProjectMapper houseProjectMapper,
                               PersonMapper personMapper,
                               PersonContactMapper personContactMapper,
                               OrgUnitMapper orgUnitMapper) {
        this.subscriptionMapper = subscriptionMapper;
        this.subscriptionHouseMapper = subscriptionHouseMapper;
        this.subscriptionPersonMapper = subscriptionPersonMapper;
        this.subscriptionOrgMapper = subscriptionOrgMapper;
        this.houseRoomMapper = houseRoomMapper;
        this.houseBuildingMapper = houseBuildingMapper;
        this.houseProjectMapper = houseProjectMapper;
        this.personMapper = personMapper;
        this.personContactMapper = personContactMapper;
        this.orgUnitMapper = orgUnitMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public SubscriptionView create(List<Long> houseIds, List<Long> personIds, List<Long> orgIds) {
        List<Long> roomIds = distinct(houseIds);
        List<Long> buyerIds = distinct(personIds);
        List<Long> unitIds = distinct(orgIds);
        if (roomIds.isEmpty()) {
            throw new BizException("请至少选择一套房屋。");
        }
        if (buyerIds.isEmpty() && unitIds.isEmpty()) {
            throw new BizException("请至少选择一个购买主体。");
        }

        List<HouseRoom> rooms = houseRoomMapper.selectBatchIds(roomIds);
        if (rooms.size() != roomIds.size()) {
            throw new BizException("存在无效的房屋ID，请重新查询房屋。");
        }
        List<Person> persons = buyerIds.isEmpty() ? List.of() : personMapper.selectBatchIds(buyerIds);
        if (persons.size() != buyerIds.size()) {
            throw new BizException("存在无效的客户ID，请重新查询客户。");
        }
        List<OrgUnit> units = unitIds.isEmpty() ? List.of() : orgUnitMapper.selectBatchIds(unitIds);
        if (units.size() != unitIds.size()) {
            throw new BizException("存在无效的单位ID，请重新查询单位。");
        }

        for (HouseRoom room : rooms) {
            if (room.getDeletedFlag() != null && room.getDeletedFlag() != 0L) {
                throw new BizException("房屋已下架：" + room.getFullAddress());
            }
            if (room.getActiveSubscriptionId() != null) {
                throw new BizException("房屋已被认购：" + room.getFullAddress());
            }
        }

        BigDecimal totalArea = BigDecimal.ZERO;
        BigDecimal totalPrice = BigDecimal.ZERO;
        for (HouseRoom room : rooms) {
            BigDecimal area = room.getBuildingArea() == null ? BigDecimal.ZERO : room.getBuildingArea();
            BigDecimal price = room.getReferenceUnitPrice() == null ? BigDecimal.ZERO : room.getReferenceUnitPrice();
            totalArea = totalArea.add(area);
            totalPrice = totalPrice.add(area.multiply(price));
        }
        totalPrice = totalPrice.setScale(2, RoundingMode.HALF_UP);
        BigDecimal unitPrice = totalArea.signum() > 0
                ? totalPrice.divide(totalArea, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        long subscriptionId = IdWorker.getId();
        Subscription subscription = new Subscription();
        subscription.setId(subscriptionId);
        subscription.setBizNo("RZ" + subscriptionId);
        subscription.setStatus(STATUS_ACTIVE);
        subscription.setBizType(1);
        subscription.setTotalPrice(totalPrice);
        subscription.setUnitPrice(unitPrice);
        subscriptionMapper.insert(subscription);

        Map<Long, HouseBuilding> buildings = loadBuildings(rooms);
        Map<Long, HouseProject> projects = loadProjects(buildings.values());
        Map<Long, OrgUnit> developers = loadDevelopers(projects.values());

        for (HouseRoom room : rooms) {
            int locked = houseRoomMapper.update(null, new LambdaUpdateWrapper<HouseRoom>()
                    .set(HouseRoom::getActiveSubscriptionId, subscriptionId)
                    .eq(HouseRoom::getId, room.getId())
                    .isNull(HouseRoom::getActiveSubscriptionId));
            if (locked == 0) {
                throw new BizException("房屋已被他人认购，请重新选择：" + room.getFullAddress());
            }

            HouseBuilding building = buildings.get(room.getBuildingId());
            HouseProject project = building == null ? null : projects.get(building.getProjectId());
            OrgUnit developer = project == null ? null : developers.get(project.getDeveloperId());

            SubscriptionHouse snapshot = new SubscriptionHouse();
            snapshot.setSubscriptionId(subscriptionId);
            snapshot.setHouseId(room.getId());
            snapshot.setHouseLocationSnap(room.getFullAddress());
            snapshot.setBuildingNoSnap(building == null ? null : building.getBuildingNo());
            snapshot.setRoomNoSnap(room.getRoomNo());
            snapshot.setBuildingAreaSnap(room.getBuildingArea());
            snapshot.setInnerAreaSnap(room.getInnerArea());
            snapshot.setSharedAreaSnap(room.getSharedArea());
            snapshot.setUnitPriceSnap(room.getReferenceUnitPrice());
            snapshot.setTotalPriceSnap(room.getReferenceUnitPrice() == null || room.getBuildingArea() == null
                    ? null
                    : room.getReferenceUnitPrice().multiply(room.getBuildingArea()).setScale(2, RoundingMode.HALF_UP));
            snapshot.setProjectNameSnap(project == null ? null : project.getProjectName());
            snapshot.setDeveloperNameSnap(developer == null ? null : developer.getFullName());
            subscriptionHouseMapper.insert(snapshot);
        }

        Map<Long, String> phones = loadPhones(buyerIds);
        for (Person person : persons) {
            SubscriptionPerson snapshot = new SubscriptionPerson();
            snapshot.setSubscriptionId(subscriptionId);
            snapshot.setPersonId(person.getId());
            snapshot.setBuyerType(1);
            snapshot.setPersonNameSnap(person.getFullName());
            snapshot.setIdTypeSnap(person.getIdType());
            snapshot.setIdNoSnap(person.getIdNo());
            snapshot.setContactSnap(phones.get(person.getId()));
            subscriptionPersonMapper.insert(snapshot);
        }

        for (OrgUnit unit : units) {
            SubscriptionOrg snapshot = new SubscriptionOrg();
            snapshot.setSubscriptionId(subscriptionId);
            snapshot.setOrgId(unit.getId());
            snapshot.setUnitNameSnap(unit.getFullName());
            snapshot.setUnitIdNoSnap(unit.getIdNo());
            snapshot.setContactSnap(unit.getContact());
            subscriptionOrgMapper.insert(snapshot);
        }

        Subscription saved = subscriptionMapper.selectById(subscriptionId);
        return toViews(List.of(saved == null ? subscription : saved)).get(0);
    }

    public List<SubscriptionView> list() {
        List<Subscription> subscriptions = subscriptionMapper.selectList(new LambdaQueryWrapper<Subscription>()
                .orderByDesc(Subscription::getCreatedAt)
                .last("LIMIT 100"));
        return toViews(subscriptions);
    }

    public List<SubscriptionView> find(String houseLocation, String buyerName) {
        Set<Long> matched = null;

        if (StringUtils.hasText(houseLocation)) {
            Set<Long> byHouse = subscriptionHouseMapper.selectList(new LambdaQueryWrapper<SubscriptionHouse>()
                            .eq(SubscriptionHouse::getStatus, 1)
                            .like(SubscriptionHouse::getHouseLocationSnap, houseLocation.trim()))
                    .stream().map(SubscriptionHouse::getSubscriptionId).collect(Collectors.toSet());
            matched = byHouse;
        }

        if (StringUtils.hasText(buyerName)) {
            Set<Long> byBuyer = new LinkedHashSet<>();
            byBuyer.addAll(subscriptionPersonMapper.selectList(new LambdaQueryWrapper<SubscriptionPerson>()
                            .eq(SubscriptionPerson::getStatus, 1)
                            .like(SubscriptionPerson::getPersonNameSnap, buyerName.trim()))
                    .stream().map(SubscriptionPerson::getSubscriptionId).toList());
            byBuyer.addAll(subscriptionOrgMapper.selectList(new LambdaQueryWrapper<SubscriptionOrg>()
                            .eq(SubscriptionOrg::getStatus, 1)
                            .like(SubscriptionOrg::getUnitNameSnap, buyerName.trim()))
                    .stream().map(SubscriptionOrg::getSubscriptionId).toList());
            matched = matched == null ? byBuyer : intersect(matched, byBuyer);
        }

        if (matched == null || matched.isEmpty()) {
            return List.of();
        }

        List<Subscription> subscriptions = subscriptionMapper.selectBatchIds(matched).stream()
                .filter(item -> item.getStatus() != null
                        && (item.getStatus() == STATUS_DRAFT || item.getStatus() == STATUS_ACTIVE))
                .sorted(Comparator.comparing(Subscription::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return toViews(subscriptions);
    }

    @Transactional(rollbackFor = Exception.class)
    public SubscriptionView cancel(String bizNo, boolean confirmed) {
        if (!confirmed) {
            throw new BizException("客户尚未确认撤销。");
        }
        if (!StringUtils.hasText(bizNo)) {
            throw new BizException("请提供业务编号。");
        }
        Subscription subscription = subscriptionMapper.selectOne(new LambdaQueryWrapper<Subscription>()
                .eq(Subscription::getBizNo, bizNo.trim())
                .last("LIMIT 1"));
        if (subscription == null) {
            throw new BizException("未找到业务编号：" + bizNo);
        }
        if (Integer.valueOf(STATUS_CANCELLED).equals(subscription.getStatus())) {
            throw new BizException("该业务已撤销，无需重复操作。");
        }

        // 撤销前先取快照信息，撤销后快照失效会查不到
        SubscriptionView before = toViews(List.of(subscription)).get(0);

        subscription.setStatus(STATUS_CANCELLED);
        subscription.setCancelledAt(LocalDateTime.now());
        subscriptionMapper.updateById(subscription);

        houseRoomMapper.update(null, new LambdaUpdateWrapper<HouseRoom>()
                .set(HouseRoom::getActiveSubscriptionId, null)
                .eq(HouseRoom::getActiveSubscriptionId, subscription.getId()));

        subscriptionHouseMapper.update(null, new LambdaUpdateWrapper<SubscriptionHouse>()
                .set(SubscriptionHouse::getStatus, 0)
                .eq(SubscriptionHouse::getSubscriptionId, subscription.getId()));

        return new SubscriptionView(
                before.id(),
                before.bizNo(),
                STATUS_CANCELLED,
                statusText(STATUS_CANCELLED),
                before.totalPrice(),
                before.unitPrice(),
                before.buyerNames(),
                before.buyers(),
                before.houseLocations(),
                before.developerNames(),
                before.createdAt(),
                subscription.getCancelledAt());
    }

    private List<SubscriptionView> toViews(List<Subscription> subscriptions) {
        if (subscriptions == null || subscriptions.isEmpty()) {
            return List.of();
        }
        List<Long> ids = subscriptions.stream().map(Subscription::getId).toList();

        Map<Long, List<SubscriptionHouse>> houseRows = groupByRows(
                subscriptionHouseMapper.selectList(new LambdaQueryWrapper<SubscriptionHouse>()
                        .in(SubscriptionHouse::getSubscriptionId, ids)
                        .orderByAsc(SubscriptionHouse::getId)),
                SubscriptionHouse::getSubscriptionId);

        Map<Long, List<SubscriptionPerson>> personRows = groupByRows(
                subscriptionPersonMapper.selectList(new LambdaQueryWrapper<SubscriptionPerson>()
                        .in(SubscriptionPerson::getSubscriptionId, ids)
                        .orderByAsc(SubscriptionPerson::getId)),
                SubscriptionPerson::getSubscriptionId);

        Map<Long, List<SubscriptionOrg>> orgRows = groupByRows(
                subscriptionOrgMapper.selectList(new LambdaQueryWrapper<SubscriptionOrg>()
                        .in(SubscriptionOrg::getSubscriptionId, ids)
                        .orderByAsc(SubscriptionOrg::getId)),
                SubscriptionOrg::getSubscriptionId);

        List<SubscriptionView> views = new ArrayList<>(subscriptions.size());
        for (Subscription item : subscriptions) {
            // 优先只取有效快照；若为空（业务已撤销、快照全员置 0）则回退到全部快照
            List<SubscriptionHouse> houses = preferActive(
                    houseRows.get(item.getId()), SubscriptionHouse::getStatus);
            List<SubscriptionPerson> persons = preferActive(
                    personRows.get(item.getId()), SubscriptionPerson::getStatus);
            List<SubscriptionOrg> orgs = preferActive(
                    orgRows.get(item.getId()), SubscriptionOrg::getStatus);

            List<String> houseLocations = houses.stream()
                    .map(SubscriptionHouse::getHouseLocationSnap)
                    .filter(Objects::nonNull)
                    .toList();
            List<String> developerNames = houses.stream()
                    .map(SubscriptionHouse::getDeveloperNameSnap)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();

            List<SubscriptionView.Buyer> buyers = new ArrayList<>();
            for (SubscriptionPerson person : persons) {
                buyers.add(new SubscriptionView.Buyer(
                        person.getPersonNameSnap(),
                        person.getIdTypeSnap(),
                        person.getIdNoSnap(),
                        person.getContactSnap()));
            }
            for (SubscriptionOrg org : orgs) {
                buyers.add(new SubscriptionView.Buyer(
                        org.getUnitNameSnap(),
                        null,
                        org.getUnitIdNoSnap(),
                        org.getContactSnap()));
            }

            views.add(new SubscriptionView(
                    String.valueOf(item.getId()),
                    item.getBizNo(),
                    item.getStatus(),
                    statusText(item.getStatus()),
                    item.getTotalPrice(),
                    item.getUnitPrice(),
                    buyers.stream().map(SubscriptionView.Buyer::name).filter(Objects::nonNull).toList(),
                    buyers,
                    houseLocations,
                    developerNames,
                    item.getCreatedAt(),
                    item.getFinishedAt()));
        }
        return views;
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case STATUS_DRAFT -> "草稿";
            case STATUS_ACTIVE -> "有效";
            case STATUS_DONE -> "已完成";
            case STATUS_CANCELLED -> "已撤销";
            default -> "未知";
        };
    }

    /**
     * 优先返回 status=1 的快照行；若一行为空（例如业务已撤销、快照被置 0），
     * 则回退到全部快照行，保证撤销后仍能展示房屋与购买方信息。
     */
    private <T> List<T> preferActive(List<T> rows, Function<T, Integer> statusGetter) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<T> active = rows.stream()
                .filter(row -> Integer.valueOf(STATUS_ACTIVE).equals(statusGetter.apply(row)))
                .toList();
        return active.isEmpty() ? rows : active;
    }

    private <T> Map<Long, List<T>> groupByRows(List<T> rows, Function<T, Long> keyGetter) {
        Map<Long, List<T>> result = new LinkedHashMap<>();
        for (T row : rows) {
            Long key = keyGetter.apply(row);
            if (key == null) {
                continue;
            }
            result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        return result;
    }

    private Map<Long, HouseBuilding> loadBuildings(List<HouseRoom> rooms) {
        Set<Long> ids = rooms.stream().map(HouseRoom::getBuildingId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return houseBuildingMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(HouseBuilding::getId, item -> item, (a, b) -> a));
    }

    private Map<Long, HouseProject> loadProjects(Collection<HouseBuilding> buildings) {
        Set<Long> ids = buildings.stream().map(HouseBuilding::getProjectId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return houseProjectMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(HouseProject::getId, item -> item, (a, b) -> a));
    }

    private Map<Long, OrgUnit> loadDevelopers(Collection<HouseProject> projects) {
        Set<Long> ids = projects.stream().map(HouseProject::getDeveloperId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return orgUnitMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(OrgUnit::getId, item -> item, (a, b) -> a));
    }

    private Map<Long, String> loadPhones(List<Long> personIds) {
        if (personIds.isEmpty()) {
            return Map.of();
        }
        List<PersonContact> contacts = personContactMapper.selectList(new LambdaQueryWrapper<PersonContact>()
                .in(PersonContact::getPersonId, personIds)
                .orderByAsc(PersonContact::getId));
        Map<Long, String> phones = new HashMap<>();
        for (PersonContact contact : contacts) {
            phones.putIfAbsent(contact.getPersonId(), contact.getContactValue());
        }
        return phones;
    }

    private List<Long> distinct(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    private Set<Long> intersect(Set<Long> left, Set<Long> right) {
        Set<Long> result = new LinkedHashSet<>(left);
        result.retainAll(right);
        return result;
    }
}
