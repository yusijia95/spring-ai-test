package com.yusijia.houseclientservices;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yusijia.houseclientservices.domain.entity.HouseRoom;
import com.yusijia.houseclientservices.domain.entity.SubscriptionHouse;
import com.yusijia.houseclientservices.domain.entity.SubscriptionPerson;
import com.yusijia.houseclientservices.domain.view.SubscriptionView;
import com.yusijia.houseclientservices.exception.BizException;
import com.yusijia.houseclientservices.mapper.HouseRoomMapper;
import com.yusijia.houseclientservices.mapper.SubscriptionHouseMapper;
import com.yusijia.houseclientservices.mapper.SubscriptionPersonMapper;
import com.yusijia.houseclientservices.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 认购主链路集成测试：抢占房屋、写快照、撤销释放。
 * 依赖 V3 种子数据（房屋 4001/4002，个人 5001）。
 * 每个用例在事务内执行并在结束时回滚，不污染本地库。
 */
@SpringBootTest
@Transactional
class SubscriptionFlowTest {

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private HouseRoomMapper houseRoomMapper;

    @Autowired
    private SubscriptionHouseMapper subscriptionHouseMapper;

    @Autowired
    private SubscriptionPersonMapper subscriptionPersonMapper;

    @Test
    void createShouldOccupyHouseAndWriteSnapshots() {
        SubscriptionView view = subscriptionService.create(List.of(4001L), List.of(5001L), null);

        assertThat(view.id()).isNotBlank();
        assertThat(view.bizNo()).startsWith("RZ");
        assertThat(view.status()).isEqualTo(1);
        assertThat(view.statusText()).isEqualTo("有效");
        assertThat(view.totalPrice()).isEqualByComparingTo("2864000.00");
        assertThat(view.unitPrice()).isEqualByComparingTo("32000.00");
        assertThat(view.buyerNames()).containsExactly("张三");
        assertThat(view.buyers()).hasSize(1);
        assertThat(view.buyers().get(0).name()).isEqualTo("张三");
        assertThat(view.buyers().get(0).idNo()).isEqualTo("330106199001011234");
        assertThat(view.developerNames()).containsExactly("杭州示例置业有限公司");

        HouseRoom room = houseRoomMapper.selectById(4001L);
        assertThat(room.getActiveSubscriptionId()).isEqualTo(Long.valueOf(view.id()));

        SubscriptionHouse houseSnap = subscriptionHouseMapper.selectOne(
                new LambdaQueryWrapper<SubscriptionHouse>()
                        .eq(SubscriptionHouse::getSubscriptionId, Long.valueOf(view.id())));
        assertThat(houseSnap).isNotNull();
        assertThat(houseSnap.getHouseId()).isEqualTo(4001L);
        assertThat(houseSnap.getUnitPriceSnap()).isEqualByComparingTo("32000.00");
        assertThat(houseSnap.getTotalPriceSnap()).isEqualByComparingTo("2864000.00");
        assertThat(houseSnap.getBuildingAreaSnap()).isEqualByComparingTo("89.50");
        assertThat(houseSnap.getProjectNameSnap()).isEqualTo("示例花园");
        assertThat(houseSnap.getDeveloperNameSnap()).isEqualTo("杭州示例置业有限公司");
        assertThat(houseSnap.getHouseLocationSnap()).isNotBlank();

        SubscriptionPerson personSnap = subscriptionPersonMapper.selectOne(
                new LambdaQueryWrapper<SubscriptionPerson>()
                        .eq(SubscriptionPerson::getSubscriptionId, Long.valueOf(view.id())));
        assertThat(personSnap).isNotNull();
        assertThat(personSnap.getPersonId()).isEqualTo(5001L);
        assertThat(personSnap.getPersonNameSnap()).isEqualTo("张三");
        assertThat(personSnap.getIdNoSnap()).isEqualTo("330106199001011234");
        assertThat(personSnap.getContactSnap()).isEqualTo("13800000001");
    }

    @Test
    void createShouldRejectAlreadyOccupiedHouse() {
        subscriptionService.create(List.of(4001L), List.of(5001L), null);

        assertThatThrownBy(() -> subscriptionService.create(List.of(4001L), List.of(5002L), null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已被认购");
    }

    @Test
    void cancelShouldReleaseHouseAndRevokeSnapshot() {
        SubscriptionView view = subscriptionService.create(List.of(4002L), List.of(5002L), null);

        SubscriptionView cancelledView = subscriptionService.cancel(view.bizNo(), true);
        assertThat(cancelledView.status()).isEqualTo(3);
        assertThat(cancelledView.statusText()).isEqualTo("已撤销");
        assertThat(cancelledView.houseLocations()).hasSize(1);
        assertThat(cancelledView.houseLocations().get(0)).contains("102");
        assertThat(cancelledView.buyerNames()).containsExactly("李四");
        assertThat(cancelledView.buyers()).hasSize(1);
        assertThat(cancelledView.buyers().get(0).idNo()).isEqualTo("330106199202022345");
        assertThat(cancelledView.developerNames()).containsExactly("杭州示例置业有限公司");

        HouseRoom room = houseRoomMapper.selectById(4002L);
        assertThat(room.getActiveSubscriptionId()).isNull();

        SubscriptionHouse houseSnap = subscriptionHouseMapper.selectOne(
                new LambdaQueryWrapper<SubscriptionHouse>()
                        .eq(SubscriptionHouse::getSubscriptionId, Long.valueOf(view.id())));
        assertThat(houseSnap.getStatus()).isEqualTo(0);

        SubscriptionView cancelled = subscriptionService.list().stream()
                .filter(item -> item.id().equals(view.id()))
                .findFirst().orElseThrow();
        assertThat(cancelled.status()).isEqualTo(3);
        assertThat(cancelled.statusText()).isEqualTo("已撤销");
        // 快照行已置 0，列表仍需回退展示房屋与购买方信息
        assertThat(cancelled.houseLocations()).hasSize(1);
        assertThat(cancelled.houseLocations().get(0)).contains("102");
        assertThat(cancelled.buyers()).hasSize(1);
        assertThat(cancelled.buyers().get(0).idNo()).isEqualTo("330106199202022345");
    }

    @Test
    void searchShouldMatchBuyerSnapshot() {
        subscriptionService.create(List.of(4003L), List.of(5003L), null);

        List<SubscriptionView> found = subscriptionService.find(null, "王五");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).houseLocations()).hasSize(1);
        assertThat(found.get(0).houseLocations().get(0)).contains("201");
    }
}
