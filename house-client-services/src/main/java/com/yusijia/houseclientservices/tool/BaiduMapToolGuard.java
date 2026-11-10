package com.yusijia.houseclientservices.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纠正模型对百度地图工具参数的误用：必须使用最近一次地理编码坐标，
 * 且将逗号拼接的 query 拆成多次检索。
 */
public final class BaiduMapToolGuard {

    private static final ThreadLocal<String> LAST_GEOCODE_LOCATION = new ThreadLocal<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern LAT = Pattern.compile("['\"]lat['\"]\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern LNG = Pattern.compile("['\"]lng['\"]\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");

    private BaiduMapToolGuard() {
    }

    public static void clear() {
        LAST_GEOCODE_LOCATION.remove();
    }

    public static String lastLocation() {
        return LAST_GEOCODE_LOCATION.get();
    }

    /**
     * Spring AI 1.1.2 的 SyncMcpToolCallback#call 返回的是整个 content 列表的 JSON，
     * 形如 [{"type":"text","text":"{\"status\":0,...}"}]，而不是 text 字段里的业务 JSON。
     * 这里统一剥掉这一层包装，取出真正的工具输出文本。
     */
    public static String unwrapMcpContent(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            JsonNode content = node.isArray() ? node : node.path("content");
            if (content.isArray() && !content.isEmpty() && content.get(0).has("text")) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : content) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(block.path("text").asText(""));
                }
                String text = sb.toString().trim();
                return text.isEmpty() ? raw : text;
            }
            if (node.isObject() && node.path("text").isTextual()) {
                return node.path("text").asText();
            }
            return raw;
        } catch (Exception e) {
            return raw;
        }
    }

    public static void rememberGeocode(String toolOutput) {
        if (toolOutput == null || toolOutput.isBlank()) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(toolOutput);
            String lat = null, lng = null;
            // 尝试多种 JSON 路径提取坐标
            JsonNode loc = root.path("result").path("location");
            if (loc.isObject() && !loc.isMissingNode()) {
                lat = loc.path("lat").asText(null);
                lng = loc.path("lng").asText(null);
            }
            if (lat == null || lng == null) {
                loc = root.path("location");
                if (loc.isObject() && !loc.isMissingNode()) {
                    lat = loc.path("lat").asText(null);
                    lng = loc.path("lng").asText(null);
                }
            }
            if (lat == null || lng == null) {
                lat = root.path("lat").asText(null);
                lng = root.path("lng").asText(null);
            }
            if (lat != null && lng != null) {
                LAST_GEOCODE_LOCATION.set(lat + "," + lng);
            }
        } catch (Exception e) {
            // JSON 解析失败，保留原正则回退
            Matcher lat = LAT.matcher(toolOutput);
            Matcher lng = LNG.matcher(toolOutput);
            if (lat.find() && lng.find()) {
                LAST_GEOCODE_LOCATION.set(lat.group(1) + "," + lng.group(1));
            }
        }
    }

    public static String rewriteSearchPlacesInput(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return toolInput;
        }
        String forced = LAST_GEOCODE_LOCATION.get();
        try {
            JsonNode node = MAPPER.readTree(toolInput);
            if (!(node instanceof ObjectNode obj)) {
                return toolInput;
            }
            if (forced != null && !forced.isBlank()) {
                obj.put("location", forced);
            }
            return MAPPER.writeValueAsString(obj);
        } catch (Exception ignored) {
            return toolInput;
        }
    }

    public static List<String> splitQueries(String toolInput) {
        List<String> queries = new ArrayList<>();
        try {
            JsonNode node = MAPPER.readTree(toolInput);
            if (node == null || !node.has("query")) {
                return queries;
            }
            String query = node.get("query").asText("");
            for (String part : query.split("[,，]")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    queries.add(trimmed);
                }
            }
        } catch (Exception ignored) {
        }
        return queries;
    }

    public static String withQuery(String toolInput, String query) {
        try {
            JsonNode node = MAPPER.readTree(toolInput);
            if (!(node instanceof ObjectNode obj)) {
                return toolInput;
            }
            obj.put("query", query);
            return MAPPER.writeValueAsString(obj);
        } catch (Exception ignored) {
            return toolInput;
        }
    }

    /**
     * 将 map_geocode 的原始 JSON 结果格式化为 LLM 可读的纯文本。
     * 支持多种 JSON 路径结构。
     */
    public static String formatGeocodeOutput(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return rawJson;
        }
        try {
            JsonNode root = MAPPER.readTree(rawJson);
            int status = root.path("status").asInt(1);
            if (status != 0) {
                return "地理编码失败（status=" + status + "）：" + root.path("message").asText("未知错误")
                        + "，原始响应：" + truncate(rawJson, 300);
            }
            String lng = "", lat = "", addr = "";
            // 路径1: result.location.lat/lng + result.formatted_address
            JsonNode result = root.path("result");
            if (result.isObject() && !result.isMissingNode()) {
                JsonNode loc = result.path("location");
                if (loc.isObject() && !loc.isMissingNode()) {
                    lat = loc.path("lat").asText("");
                    lng = loc.path("lng").asText("");
                }
                addr = result.path("formatted_address").asText("");
            }
            // 路径2: location.lat/lng (顶层)
            if (lat.isEmpty() && lng.isEmpty() && root.has("location")) {
                JsonNode loc = root.get("location");
                lat = loc.path("lat").asText("");
                lng = loc.path("lng").asText("");
            }
            // 路径3: lat/lng (最顶层)
            if (lat.isEmpty() && lng.isEmpty() && root.has("lat") && root.has("lng")) {
                lat = root.get("lat").asText("");
                lng = root.get("lng").asText("");
            }
            // 路径4: 地址的其他可能位置
            if (addr.isEmpty()) {
                addr = root.path("formatted_address").asText("");
                if (addr.isEmpty()) addr = root.path("address").asText("");
            }
            if (lat.isEmpty() || lng.isEmpty()) {
                return "地理编码返回了空坐标，原始响应：" + truncate(rawJson, 300);
            }
            String coord = lat + "," + lng;
            return addr.isBlank() ? "地理坐标：" + coord : "地理坐标：" + coord + "，解析地址：" + addr;
        } catch (Exception e) {
            return "地理编码解析失败：" + rawJson.substring(0, Math.min(200, rawJson.length()));
        }
    }

    /**
     * 将 map_search_places 的原始 JSON 结果格式化为 LLM 可读的纯文本。
     * 最多返回 5 条结果，含名称、类别、地址、距离。
     * 如果输入已不是 JSON（如多查询合并后的格式化文本），直接返回不做处理。
     */
    public static String formatSearchPlacesOutput(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        try {
            JsonNode root = MAPPER.readTree(input);
            int status = root.path("status").asInt(0);
            if (status != 0) {
                return "搜索失败（status=" + status + "）：" + root.path("message").asText("未知错误")
                        + "，原始响应：" + truncate(input, 300);
            }
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                return "未找到相关地点。";
            }

            // 每类最多 5 条：模型不一定遵守提示词里的"最多 5 条"，这里做确定性截断
            int limit = Math.min(5, results.size());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < limit; i++) {
                JsonNode item = results.get(i);
                JsonNode detail = item.path("detail_info");

                List<String> parts = new ArrayList<>();
                String type = poiType(detail);
                String name = item.path("name").asText("");
                parts.add(type.isEmpty() ? name : name + "（" + type + "）");

                String addr = item.path("address").asText("");
                if (addr.isEmpty() && detail.isObject()) {
                    addr = detail.path("address").asText("");
                }
                if (!addr.isBlank()) {
                    // 公交站的 address 是线路串，全列出来会把回复撑成一堵墙，只留前 3 条
                    if (type.contains("公交") && addr.split(";").length > 3) {
                        String[] lines = addr.split(";");
                        addr = String.join(";", lines[0], lines[1], lines[2]) + "等";
                    }
                    parts.add(addr);
                }

                String distStr = detail.path("distance").asText("");
                if (!distStr.isEmpty() && !"null".equals(distStr)) {
                    try {
                        int meters = Integer.parseInt(distStr);
                        parts.add(meters < 1000 ? meters + "米" : String.format("%.1fkm", meters / 1000.0));
                    } catch (NumberFormatException ignored) {
                    }
                }
                sb.append("- ").append(String.join("，", parts)).append("\n");
            }
            return sb.toString();
        } catch (Exception ignored) {
            // 输入不是 JSON（如已格式化文本），直接返回不做处理
            return truncate(input, 1200);
        }
    }

    /**
     * 起点与终点坐标相同时直接拦下。
     * <p>
     * 这是路线规划最容易翻车的点：模型连续对起点、终点做地理编码，然后把最后一次的坐标
     * 同时填进 origin 和 destination，工具会返回一堆"原地不动"的方案。
     * 与其让模型自己发现，不如在这里确定性地拒绝并给出纠正方式。
     *
     * @return 拦截提示；输入正常时返回 null
     */
    public static String directionsGuard(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(toolInput);
            String origin = node.path("origin").asText("").trim();
            String destination = node.path("destination").asText("").trim();
            if (!origin.isEmpty() && origin.equals(destination)) {
                return "起点与终点坐标相同（" + origin + "），无法规划路线。"
                        + "请分别对起点、终点调用 map_geocode，把两个不同的坐标分别填入 origin 和 destination 后重试。";
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 将 map_directions 的原始 JSON 压缩成模型可读的行程文本。
     * <p>
     * 原始响应含每个方案的完整 path 折线（几十 KB），直接喂给模型会挤爆上下文；
     * 这里只保留方案级的距离/耗时/票价和每一段的 instruction，最多 3 个方案。
     * instruction 本身就是百度给的中文行程描述（"XX站乘地铁S1号线经过1站到XX站"），
     * 直接转述比我们二次拼接更准确。
     */
    public static String formatDirectionsOutput(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return rawJson;
        }
        try {
            JsonNode root = MAPPER.readTree(rawJson);
            int status = root.path("status").asInt(1);
            if (status != 0) {
                return "路线规划失败（status=" + status + "）：" + root.path("message").asText("未知错误")
                        + "，原始响应：" + truncate(rawJson, 300);
            }
            JsonNode routes = root.path("result").path("routes");
            if (!routes.isArray() || routes.isEmpty()) {
                return "未查到可用路线。";
            }
            int limit = Math.min(3, routes.size());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < limit; i++) {
                JsonNode route = routes.get(i);
                if (i > 0) {
                    sb.append('\n');
                }
                sb.append("方案").append(i + 1).append("：全程")
                        .append(formatMeters(route.path("distance").asInt(0))).append("，")
                        .append(formatSeconds(route.path("duration").asInt(0)));
                int price = route.path("price").asInt(0);
                if (price > 0) {
                    sb.append("，票价").append(price).append("元");
                }
                sb.append('\n');
                for (JsonNode step : route.path("steps")) {
                    String instruction = step.path("instruction").asText("").trim();
                    if (instruction.isEmpty()) {
                        int distance = step.path("distance").asInt(0);
                        if (distance <= 0) {
                            continue;
                        }
                        instruction = "步行" + formatMeters(distance);
                    }
                    sb.append("- ").append(instruction).append('\n');
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return truncate(rawJson, 1200);
        }
    }

    private static String formatMeters(int meters) {
        return meters < 1000 ? meters + "米" : String.format("%.1f公里", meters / 1000.0);
    }

    private static String formatSeconds(int seconds) {
        int minutes = Math.max(1, (int) Math.round(seconds / 60.0));
        if (minutes < 60) {
            return "约" + minutes + "分钟";
        }
        int rest = minutes % 60;
        return rest == 0 ? "约" + minutes / 60 + "小时" : "约" + minutes / 60 + "小时" + rest + "分钟";
    }

    /**
     * 提取 POI 类别。优先用 classified_poi_tag（如"交通设施;地铁站"），
     * 取最后一段作为具体类别；tag 常常只是"其他"，不能直接用。
     */
    private static String poiType(JsonNode detail) {
        if (detail == null || !detail.isObject()) {
            return "";
        }
        for (String field : new String[]{"classified_poi_tag", "tag"}) {
            String raw = detail.path(field).asText("");
            if (raw.isBlank()) {
                continue;
            }
            String[] segments = raw.split(";");
            for (int i = segments.length - 1; i >= 0; i--) {
                String segment = segments[i].trim();
                if (!segment.isEmpty() && !"其他".equals(segment)) {
                    return segment;
                }
            }
        }
        return "";
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
