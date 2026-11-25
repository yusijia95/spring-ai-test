package com.yusijia.houseclientservices.controller;

import com.yusijia.houseclientservices.domain.view.HouseView;
import com.yusijia.houseclientservices.domain.view.OrgView;
import com.yusijia.houseclientservices.domain.view.PersonView;
import com.yusijia.houseclientservices.security.Authorization;
import com.yusijia.houseclientservices.security.PermissionPoints;
import com.yusijia.houseclientservices.security.UserContext;
import com.yusijia.houseclientservices.service.MasterDataService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/master")
public class MasterDataController {

    private final MasterDataService masterDataService;

    public MasterDataController(MasterDataService masterDataService) {
        this.masterDataService = masterDataService;
    }

    @GetMapping("/houses")
    public List<HouseView> houses(@RequestParam(required = false) String keyword, HttpServletRequest request) {
        Authorization.requirePermission(request, PermissionPoints.DATA_HOUSE_SEARCH);
        return masterDataService.searchHouses(keyword);
    }

    @GetMapping("/persons")
    public List<PersonView> persons(@RequestParam(required = false) String keyword, HttpServletRequest request) {
        UserContext user = Authorization.requirePermission(request, PermissionPoints.DATA_PERSON_SEARCH);
        boolean phoneVisible = Authorization.hasPermission(user, PermissionPoints.DATA_PERSON_PHONE_READ);
        if (user.isCustomer()) {
            // 自助客户只能看本人档案：忽略 keyword，避免用关键字扫出他人（姓名/证件号掩码也算 PII）
            Long self = user.personId();
            if (self == null) {
                return List.of();
            }
            return masterDataService.searchPersonsByIds(List.of(self), phoneVisible);
        }
        return masterDataService.searchPersons(keyword, phoneVisible);
    }

    @GetMapping("/orgs")
    public List<OrgView> orgs(@RequestParam(required = false) String keyword, HttpServletRequest request) {
        Authorization.requirePermission(request, PermissionPoints.DATA_ORG_SEARCH);
        return masterDataService.searchOrgs(keyword);
    }
}
