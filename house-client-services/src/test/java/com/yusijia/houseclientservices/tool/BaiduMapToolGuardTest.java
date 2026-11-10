package com.yusijia.houseclientservices.tool;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证百度地图工具的输入护栏与输出压缩。
 * <p>
 * 夹具 {@code /baidu/directions_transit.json} 是一次真实调用（正方中路地铁站 → 华侨路37号，transit）
 * 的返回裁剪版：只保留了方案级的距离/耗时/票价和每段的 instruction，去掉了每段几十 KB 的 path 折线。
 * 原始响应 80KB，压缩后模型只看到 ~1KB 的行程文本。
 */
class BaiduMapToolGuardTest {

    private static String sample() throws IOException {
        try (InputStream in = BaiduMapToolGuardTest.class.getResourceAsStream("/baidu/directions_transit.json")) {
            assertNotNull(in, "缺少测试夹具 /baidu/directions_transit.json");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void compressesTransitRoutesIntoReadableTrip() throws IOException {
        String text = BaiduMapToolGuard.formatDirectionsOutput(sample());

        // 方案头：距离、耗时、票价
        assertTrue(text.startsWith("方案1：全程35.1公里，约1小时35分钟，票价6元"), text.substring(0, Math.min(80, text.length())));
        assertEquals(3, text.lines().filter(line -> line.startsWith("方案")).count());
        // 每段直接转述百度给的行程描述
        assertTrue(text.contains("- 正方中路站(1口)乘地铁S1号线(南京南站方向)经过1站到吉印大道站"));
        assertTrue(text.contains("- 站厅换乘 步行231米"));
        // 折线坐标不能进模型上下文
        assertFalse(text.contains("118.81224927336"));
        assertFalse(text.contains("\"path\""));
    }

    @Test
    void rejectsIdenticalOriginAndDestination() {
        String message = BaiduMapToolGuard.directionsGuard(
                "{\"origin\":\"31.85,118.79\",\"destination\":\"31.85,118.79\",\"model\":\"transit\"}");
        assertNotNull(message);
        assertTrue(message.contains("起点与终点坐标相同"));

        assertNull(BaiduMapToolGuard.directionsGuard(
                "{\"origin\":\"31.85,118.79\",\"destination\":\"32.05,118.78\",\"model\":\"transit\"}"));
        assertNull(BaiduMapToolGuard.directionsGuard(null));
    }

    @Test
    void reportsFailureStatus() {
        String text = BaiduMapToolGuard.formatDirectionsOutput("{\"status\":400,\"message\":\"参数错误\"}");
        assertTrue(text.startsWith("路线规划失败（status=400）：参数错误"));
    }

    @Test
    void fallsBackForNonJsonInput() {
        String text = BaiduMapToolGuard.formatDirectionsOutput("这不是 JSON");
        assertFalse(text.isEmpty());
    }
}
