package com.yusijia.houseclientservices.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yusijia.houseclientservices.domain.entity.HouseBuilding;
import com.yusijia.houseclientservices.domain.entity.HouseProject;
import com.yusijia.houseclientservices.domain.entity.HouseRoom;
import com.yusijia.houseclientservices.domain.entity.OrgUnit;
import com.yusijia.houseclientservices.domain.entity.Person;
import com.yusijia.houseclientservices.domain.entity.PersonContact;
import com.yusijia.houseclientservices.domain.view.HouseView;
import com.yusijia.houseclientservices.domain.view.OrgView;
import com.yusijia.houseclientservices.domain.view.PersonView;
import com.yusijia.houseclientservices.domain.view.ProjectView;
import com.yusijia.houseclientservices.mapper.HouseBuildingMapper;
import com.yusijia.houseclientservices.mapper.HouseProjectMapper;
import com.yusijia.houseclientservices.mapper.HouseRoomMapper;
import com.yusijia.houseclientservices.mapper.OrgUnitMapper;
import com.yusijia.houseclientservices.mapper.PersonContactMapper;
import com.yusijia.houseclientservices.mapper.PersonMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 主数据查询：房屋、个人、单位。
 * 只读，供 AI 工具在生成认购前获取真实 ID。
 */
@Service
public class MasterDataService {

    private static final int LIMIT = 20;
    private static final long NOT_DELETED = 0L;

    private final HouseRoomMapper houseRoomMapper;
    private final HouseBuildingMapper houseBuildingMapper;
    private final HouseProjectMapper houseProjectMapper;
    private final OrgUnitMapper orgUnitMapper;
    private final PersonMapper personMapper;
    private final PersonContactMapper personContactMapper;

    public MasterDataService(HouseRoomMapper houseRoomMapper,
                             HouseBuildingMapper houseBuildingMapper,
                             HouseProjectMapper houseProjectMapper,
                             OrgUnitMapper orgUnitMapper,
                             PersonMapper personMapper,
                             PersonContactMapper personContactMapper) {
        this.houseRoomMapper = houseRoomMapper;
        this.houseBuildingMapper = houseBuildingMapper;
        this.houseProjectMapper = houseProjectMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.personMapper = personMapper;
        this.personContactMapper = personContactMapper;
    }

    public List<HouseView> searchHouses(String keyword) {
        String kw = trim(keyword);
        List<HouseRoom> rooms = houseRoomMapper.selectList(new LambdaQueryWrapper<HouseRoom>()
                .eq(HouseRoom::getDeletedFlag, NOT_DELETED)
                .like(StringUtils.hasText(kw), HouseRoom::getFullAddress, kw)
                .orderByAsc(HouseRoom::getId)
                .last("LIMIT " + LIMIT));
        if (rooms.isEmpty()) {
            return List.of();
        }

        Set<Long> buildingIds = rooms.stream()
                .map(HouseRoom::getBuildingId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, HouseBuilding> buildings = loadByIds(buildingIds,
                ids -> houseBuildingMapper.selectBatchIds(ids), HouseBuilding::getId);

        Set<Long> projectIds = buildings.values().stream()
                .map(HouseBuilding::getProjectId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, HouseProject> projects = loadByIds(projectIds,
                ids -> houseProjectMapper.selectBatchIds(ids), HouseProject::getId);

        List<HouseView> views = new ArrayList<>(rooms.size());
        for (HouseRoom room : rooms) {
            HouseBuilding building = buildings.get(room.getBuildingId());
            HouseProject project = building == null ? null : projects.get(building.getProjectId());
            views.add(new HouseView(
                    String.valueOf(room.getId()),
                    project == null ? null : project.getProjectName(),
                    building == null ? null : building.getBuildingNo(),
                    room.getRoomNo(),
                    room.getFullAddress(),
                    room.getBuildingArea(),
                    room.getReferenceUnitPrice(),
                    room.getPlannedUsage(),
                    room.getActiveSubscriptionId() == null));
        }
        return views;
    }

    /**
     * 按区域/板块/楼盘名查询在售楼盘及其价格、面积区间。
     * 关键字先整体匹配；匹配不到时退化为"末段"匹配（"南京市江宁区科技东路联发云启" -> "联发云启"），
     * 让模型不必猜客户到底想搜哪一段。
     */
    public List<ProjectView> searchProjects(String keyword) {
        String kw = trim(keyword);
        List<HouseProject> projects = queryProjects(kw);
        if (projects.isEmpty() && StringUtils.hasText(kw)) {
            String tail = tailSegment(kw);
            if (!tail.equals(kw)) {
                projects = queryProjects(tail);
            }
        }
        if (projects.isEmpty()) {
            return List.of();
        }

        Set<Long> projectIds = projects.stream().map(HouseProject::getId).collect(Collectors.toSet());

        Set<Long> developerIds = projects.stream()
                .map(HouseProject::getDeveloperId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, OrgUnit> developers = loadByIds(developerIds,
                ids -> orgUnitMapper.selectBatchIds(ids), OrgUnit::getId);

        List<HouseBuilding> buildings = houseBuildingMapper.selectList(new LambdaQueryWrapper<HouseBuilding>()
                .eq(HouseBuilding::getDeletedFlag, NOT_DELETED)
                .in(HouseBuilding::getProjectId, projectIds));
        Map<Long, Long> projectByBuilding = buildings.stream()
                .filter(building -> building.getId() != null && building.getProjectId() != null)
                .collect(Collectors.toMap(HouseBuilding::getId, HouseBuilding::getProjectId, (a, b) -> a));

        List<HouseRoom> rooms = projectByBuilding.isEmpty() ? List.of()
                : houseRoomMapper.selectList(new LambdaQueryWrapper<HouseRoom>()
                .eq(HouseRoom::getDeletedFlag, NOT_DELETED)
                .in(HouseRoom::getBuildingId, projectByBuilding.keySet()));

        Map<Long, List<HouseRoom>> roomsByProject = new HashMap<>();
        for (HouseRoom room : rooms) {
            Long projectId = projectByBuilding.get(room.getBuildingId());
            if (projectId != null) {
                roomsByProject.computeIfAbsent(projectId, key -> new ArrayList<>()).add(room);
            }
        }

        List<ProjectView> views = new ArrayList<>(projects.size());
        for (HouseProject project : projects) {
            List<HouseRoom> projectRooms = roomsByProject.getOrDefault(project.getId(), List.of());
            OrgUnit developer = project.getDeveloperId() == null ? null : developers.get(project.getDeveloperId());
            views.add(new ProjectView(
                    project.getProjectName(),
                    project.getLocation(),
                    developer == null ? null : developer.getFullName(),
                    projectRooms.size(),
                    (int) projectRooms.stream().filter(room -> room.getActiveSubscriptionId() == null).count(),
                    minOf(projectRooms, HouseRoom::getReferenceUnitPrice),
                    maxOf(projectRooms, HouseRoom::getReferenceUnitPrice),
                    minOf(projectRooms, HouseRoom::getBuildingArea),
                    maxOf(projectRooms, HouseRoom::getBuildingArea)));
        }
        return views;
    }

    private List<HouseProject> queryProjects(String kw) {
        return houseProjectMapper.selectList(new LambdaQueryWrapper<HouseProject>()
                .eq(HouseProject::getDeletedFlag, NOT_DELETED)
                .and(StringUtils.hasText(kw), wrapper -> wrapper
                        .like(HouseProject::getProjectName, kw)
                        .or()
                        .like(HouseProject::getLocation, kw))
                .orderByAsc(HouseProject::getId)
                .last("LIMIT " + LIMIT));
    }

    private static final Pattern ADDRESS_SEPARATOR =
            Pattern.compile("[省市区县镇乡街道号弄幢栋单元\\s]+");

    private String tailSegment(String keyword) {
        String[] parts = ADDRESS_SEPARATOR.split(keyword);
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i].trim();
            if (part.length() >= 2) {
                return part;
            }
        }
        return keyword;
    }

    private BigDecimal minOf(List<HouseRoom> rooms, Function<HouseRoom, BigDecimal> getter) {
        return rooms.stream().map(getter).filter(Objects::nonNull).min(BigDecimal::compareTo).orElse(null);
    }

    private BigDecimal maxOf(List<HouseRoom> rooms, Function<HouseRoom, BigDecimal> getter) {
        return rooms.stream().map(getter).filter(Objects::nonNull).max(BigDecimal::compareTo).orElse(null);
    }

    public List<PersonView> searchPersons(String keyword) {
        String kw = trim(keyword);
        List<Person> persons = personMapper.selectList(new LambdaQueryWrapper<Person>()
                .eq(Person::getDeletedFlag, NOT_DELETED)
                .and(StringUtils.hasText(kw), wrapper -> wrapper
                        .like(Person::getFullName, kw)
                        .or()
                        .eq(Person::getIdNo, kw))
                .orderByAsc(Person::getId)
                .last("LIMIT " + LIMIT));
        if (persons.isEmpty()) {
            return List.of();
        }

        Set<Long> personIds = persons.stream().map(Person::getId).collect(Collectors.toSet());
        List<PersonContact> contacts = personContactMapper.selectList(new LambdaQueryWrapper<PersonContact>()
                .eq(PersonContact::getDeletedFlag, NOT_DELETED)
                .in(PersonContact::getPersonId, personIds)
                .orderByAsc(PersonContact::getId));
        Map<Long, String> phoneByPerson = new HashMap<>();
        for (PersonContact contact : contacts) {
            phoneByPerson.putIfAbsent(contact.getPersonId(), contact.getContactValue());
        }

        List<PersonView> views = new ArrayList<>(persons.size());
        for (Person person : persons) {
            views.add(new PersonView(
                    String.valueOf(person.getId()),
                    person.getFullName(),
                    maskIdNo(person.getIdNo()),
                    phoneByPerson.get(person.getId())));
        }
        return views;
    }

    public List<OrgView> searchOrgs(String keyword) {
        String kw = trim(keyword);
        List<OrgUnit> units = orgUnitMapper.selectList(new LambdaQueryWrapper<OrgUnit>()
                .eq(OrgUnit::getDeletedFlag, NOT_DELETED)
                .like(StringUtils.hasText(kw), OrgUnit::getFullName, kw)
                .orderByAsc(OrgUnit::getId)
                .last("LIMIT " + LIMIT));
        return units.stream()
                .map(unit -> new OrgView(
                        String.valueOf(unit.getId()),
                        unit.getUnitType(),
                        unitTypeText(unit.getUnitType()),
                        unit.getFullName(),
                        unit.getIdNo(),
                        unit.getContact()))
                .toList();
    }

    private String unitTypeText(Integer unitType) {
        if (unitType == null) {
            return "未知";
        }
        return switch (unitType) {
            case 1 -> "开发企业";
            case 2 -> "物业";
            case 3 -> "中介";
            default -> "其他";
        };
    }

    private String maskIdNo(String idNo) {
        if (idNo == null || idNo.length() < 8) {
            return idNo;
        }
        return idNo.substring(0, 4) + "**********" + idNo.substring(idNo.length() - 4);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private <T> Map<Long, T> loadByIds(Collection<Long> ids,
                                       java.util.function.Function<Collection<Long>, List<T>> loader,
                                       java.util.function.Function<T, Long> idGetter) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return loader.apply(ids).stream().collect(Collectors.toMap(idGetter, item -> item, (a, b) -> a));
    }
}
