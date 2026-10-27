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
import com.yusijia.houseclientservices.mapper.HouseBuildingMapper;
import com.yusijia.houseclientservices.mapper.HouseProjectMapper;
import com.yusijia.houseclientservices.mapper.HouseRoomMapper;
import com.yusijia.houseclientservices.mapper.OrgUnitMapper;
import com.yusijia.houseclientservices.mapper.PersonContactMapper;
import com.yusijia.houseclientservices.mapper.PersonMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
