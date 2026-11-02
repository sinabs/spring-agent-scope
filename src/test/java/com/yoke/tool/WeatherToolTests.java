package com.yoke.tool;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用本机的假服务代替 Open-Meteo，不访问真实网络。
 */
class WeatherToolTests {

    private static final String GEOCODING_OK = """
            {"results":[{"name":"北京","latitude":39.9075,"longitude":116.39723,"country":"中国","admin1":"北京市"}]}""";

    private static final String FORECAST_OK = """
            {"current":{"time":"2026-11-02T19:15","temperature_2m":12.0,"apparent_temperature":9.6,
            "relative_humidity_2m":61,"weather_code":0,"wind_speed_10m":7.8}}""";

    private HttpServer server;

    private final List<String> requests = new CopyOnWriteArrayList<>();

    private volatile int geocodingStatus = 200;

    private volatile String geocodingBody = GEOCODING_OK;

    private volatile int forecastStatus = 200;

    private volatile String forecastBody = FORECAST_OK;

    private WeatherTool tool;

    @BeforeEach
    void 启动假服务() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/geo", exchange -> {
            requests.add(exchange.getRequestURI().getRawQuery());
            respond(exchange, geocodingStatus, geocodingBody);
        });
        server.createContext("/forecast", exchange -> {
            requests.add(exchange.getRequestURI().getRawQuery());
            respond(exchange, forecastStatus, forecastBody);
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        tool = new WeatherTool(base + "/geo", base + "/forecast");
    }

    @AfterEach
    void 关闭假服务() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void 正常查询返回中文天气摘要() {
        String result = tool.getWeather("北京");

        assertThat(result).isEqualTo("北京（中国 北京市）当前：晴，气温 12.0°C，体感 9.6°C，相对湿度 61%，"
                + "风速 7.8 km/h，观测时间 2026-11-02T19:15（当地时间）。数据来自 Open-Meteo。");
    }

    @Test
    void 先查地名再按经纬度查天气() {
        tool.getWeather("北京");

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0)).startsWith("name=%E5%8C%97%E4%BA%AC&count=1");
        assertThat(requests.get(1)).startsWith("latitude=39.9075&longitude=116.39723&current=");
    }

    @Test
    void 城市名里的特殊字符被转义不会注入额外参数() {
        tool.getWeather("a&b=c");

        assertThat(requests.get(0)).startsWith("name=a%26b%3Dc&count=1");
    }

    @Test
    void 城市不存在时返回提示而不是报错() {
        geocodingBody = "{\"generationtime_ms\":0.1}";

        assertThat(tool.getWeather("不存在的城市")).contains("没有找到名为「不存在的城市」的城市");
        assertThat(requests).hasSize(1);
    }

    @Test
    void 空白城市名不发请求() {
        assertThat(tool.getWeather("  ")).isEqualTo("请提供城市名。");
        assertThat(tool.getWeather(null)).isEqualTo("请提供城市名。");
        assertThat(requests).isEmpty();
    }

    @Test
    void 过长的城市名不发请求() {
        assertThat(tool.getWeather("长".repeat(65))).contains("城市名过长");
        assertThat(requests).isEmpty();
    }

    @Test
    void 地名服务返回500时提示服务不可用() {
        geocodingStatus = 500;

        assertThat(tool.getWeather("北京")).isEqualTo("天气服务暂时不可用，请稍后再试。");
    }

    @Test
    void 天气服务返回500时提示服务不可用() {
        forecastStatus = 500;

        assertThat(tool.getWeather("北京")).isEqualTo("天气服务暂时不可用，请稍后再试。");
    }

    @Test
    void 上游返回的不是JSON时提示服务不可用() {
        forecastBody = "<html>bad gateway</html>";

        assertThat(tool.getWeather("北京")).isEqualTo("天气服务暂时不可用，请稍后再试。");
    }

    @Test
    void 上游缺少current字段时提示服务不可用() {
        forecastBody = "{\"latitude\":1}";

        assertThat(tool.getWeather("北京")).isEqualTo("天气服务暂时不可用，请稍后再试。");
    }

    @Test
    void 连不上上游时提示服务不可用() {
        server.stop(0);

        assertThat(tool.getWeather("北京")).isEqualTo("天气服务暂时不可用，请稍后再试。");
    }

    @Test
    void 天气代码转中文() {
        assertThat(WeatherTool.weatherOf(0)).isEqualTo("晴");
        assertThat(WeatherTool.weatherOf(63)).isEqualTo("中雨");
        assertThat(WeatherTool.weatherOf(95)).isEqualTo("雷暴");
        assertThat(WeatherTool.weatherOf(123)).isEqualTo("天气代码 123");
    }
}
