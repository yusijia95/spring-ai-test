package com.yusijia.houseclientservices.controller;

import com.yusijia.houseclientservices.domain.view.HouseView;
import com.yusijia.houseclientservices.domain.view.OrgView;
import com.yusijia.houseclientservices.domain.view.PersonView;
import com.yusijia.houseclientservices.service.MasterDataService;
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
    public List<HouseView> houses(@RequestParam(required = false) String keyword) {
        return masterDataService.searchHouses(keyword);
    }

    @GetMapping("/persons")
    public List<PersonView> persons(@RequestParam(required = false) String keyword) {
        return masterDataService.searchPersons(keyword);
    }

    @GetMapping("/orgs")
    public List<OrgView> orgs(@RequestParam(required = false) String keyword) {
        return masterDataService.searchOrgs(keyword);
    }
}
