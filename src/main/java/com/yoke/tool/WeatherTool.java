package com.yoke.tool;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

import cn.hutool.core.util.StrUtil;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 天气查询工具，数据来自开源的 Open-Meteo（无需密钥）：先用地名换经纬度，再查当前天气。
 *
 * <p>Open-Meteo 的免费接口仅限非商业使用，商用要换成它的付费版或别的数据源。
 *
 * <p>被所有请求共享，所以没有请求态字段，{@link HttpClient} 与 {@link JsonMapper} 都是线程安全的。
 * 工具方法不抛异常：失败时返回一句人话，让模型据此回答用户，而不是让整次对话报错。
 */
@Slf4j
public class WeatherTool {

    static final String GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search";

    static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";

    private static final int MAX_CITY_LENGTH = 64;

    /** 上游响应体的大小上限，正常响应只有几百字节，超过说明不对劲。 */
    private static final int MAX_BODY_LENGTH = 64 * 1024;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final String geocodingUrl;

    private final String forecastUrl;

    private final HttpClient httpClient;

    private final JsonMapper mapper = JsonMapper.builder().build();

    public WeatherTool() {
        this(GEOCODING_URL, FORECAST_URL);
    }

    /** 上游地址可注入，测试里指向本机的假服务，不访问真实网络。 */
    public WeatherTool(String geocodingUrl, String forecastUrl) {
        this.geocodingUrl = geocodingUrl;
        this.forecastUrl = forecastUrl;
        // 地址是写死的，不需要跟随重定向，避免被引到别处
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Tool(name = "get_weather", readOnly = true, concurrencySafe = true,
            description = "当用户问某个城市现在的天气、气温、冷不冷、要不要带伞时调用。"
                    + "模型自己不知道实时天气，不要凭印象回答。只返回当前实况，不含未来预报。"
                    + "找不到城市或服务不可用时会返回原因，请如实告诉用户。")
    public String getWeather(
            @ToolParam(name = "city", description = "城市名，中文或英文均可，例如 北京、Shanghai") String city) {
        if (StrUtil.isBlank(city)) {
            return "请提供城市名。";
        }
        String name = city.trim();
        if (name.length() > MAX_CITY_LENGTH) {
            return "城市名过长，请提供准确的城市名。";
        }
        try {
            JsonNode place = findPlace(name);
            if (Objects.isNull(place)) {
                return "没有找到名为「" + name + "」的城市，请确认城市名，或换成更常见的写法。";
            }
            return describe(place, fetchCurrent(place));
        } catch (IOException | JacksonException e) {
            log.warn("天气查询失败：{}", e.toString());
            return "天气服务暂时不可用，请稍后再试。";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "天气查询被中断，请重试。";
        }
    }

    /** 地名转经纬度，取第一个匹配项；没有匹配返回 null。 */
    private JsonNode findPlace(String name) throws IOException, InterruptedException {
        JsonNode body = get(geocodingUrl + "?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8)
                + "&count=1&language=zh&format=json");
        JsonNode first = body.path("results").path(0);
        return first.isMissingNode() ? null : first;
    }

    private JsonNode fetchCurrent(JsonNode place) throws IOException, InterruptedException {
        JsonNode body = get(forecastUrl + "?latitude=" + place.path("latitude").asDouble()
                + "&longitude=" + place.path("longitude").asDouble()
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m"
                + "&timezone=auto");
        JsonNode current = body.path("current");
        if (current.isMissingNode()) {
            throw new IOException("上游响应缺少 current 字段");
        }
        return current;
    }

    private JsonNode get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("上游返回状态码 " + response.statusCode());
        }
        if (response.body().length() > MAX_BODY_LENGTH) {
            throw new IOException("上游响应过大");
        }
        return mapper.readTree(response.body());
    }

    private static String describe(JsonNode place, JsonNode current) {
        StringJoiner label = new StringJoiner(" ");
        for (String field : new String[] {"country", "admin1"}) {
            Optional.of(place.path(field).asString("")).filter(StrUtil::isNotBlank).ifPresent(label::add);
        }
        String where = place.path("name").asString("") + (label.length() > 0 ? "（" + label + "）" : "");
        return where + "当前：" + weatherOf(current.path("weather_code").asInt(-1))
                + "，气温 " + current.path("temperature_2m").asDouble() + "°C"
                + "，体感 " + current.path("apparent_temperature").asDouble() + "°C"
                + "，相对湿度 " + current.path("relative_humidity_2m").asInt() + "%"
                + "，风速 " + current.path("wind_speed_10m").asDouble() + " km/h"
                + "，观测时间 " + current.path("time").asString("") + "（当地时间）。数据来自 Open-Meteo。";
    }

    /** WMO 天气代码转中文，取值见 Open-Meteo 文档。 */
    static String weatherOf(int code) {
        return switch (code) {
            case 0 -> "晴";
            case 1 -> "大部晴朗";
            case 2 -> "多云";
            case 3 -> "阴";
            case 45, 48 -> "雾";
            case 51, 53, 55 -> "毛毛雨";
            case 56, 57, 66, 67 -> "冻雨";
            case 61 -> "小雨";
            case 63 -> "中雨";
            case 65 -> "大雨";
            case 71 -> "小雪";
            case 73 -> "中雪";
            case 75 -> "大雪";
            case 77 -> "雪粒";
            case 80, 81, 82 -> "阵雨";
            case 85, 86 -> "阵雪";
            case 95 -> "雷暴";
            case 96, 99 -> "雷暴伴冰雹";
            default -> "天气代码 " + code;
        };
    }
}
