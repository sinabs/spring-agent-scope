package com.yoke.nacos;

import java.util.Properties;
import java.util.UUID;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import com.yoke.YokeApplication;
import com.yoke.config.YokeProperties;
import com.yoke.support.A2aRaw;
import com.yoke.support.NacosCompose;
import com.yoke.support.NacosIntegrationTest;
import com.yoke.support.RecordingModel;
import com.yoke.support.StubModelConfiguration;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 Nacos（docker-compose.yml 里固定版本的 3.x 容器）+ AgentScope 2.0.4 自带的客户端。
 * 每个测试自己启动并关闭一个完整的应用上下文，这样能观察「就绪后注册、关闭时注销」。模型是假的。
 */
class NacosIntegrationTests extends NacosIntegrationTest {

    private static final String TEST_WORKSPACE = System.getProperty("java.io.tmpdir") + "/yoke-test-workspace";

    private static String uniqueId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static YokeProperties.Nacos nacosProps(boolean a2a, boolean prompt) {
        return new YokeProperties.Nacos(NacosCompose.serverAddr(), null, null, null,
                new YokeProperties.Nacos.A2aSwitch(a2a, false), new YokeProperties.Nacos.PromptSwitch(prompt, "k", java.time.Duration.ofSeconds(2)));
    }

    /** 用命令行参数的形式传属性：优先级最高，不会被 application.yml 里的空默认值盖掉（builder.properties 是最低优先级）。 */
    private static ConfigurableApplicationContext start(String... properties) {
        // 同名选项出现两次会被拼成逗号分隔的值，所以后面的覆盖前面的
        java.util.Map<String, String> args = new java.util.LinkedHashMap<>();
        args.put("yoke.redis.uri", com.yoke.support.RedisCompose.uri());
        args.put("yoke.nacos.server-addr", NacosCompose.serverAddr());
        args.put("spring.main.banner-mode", "off");
        for (String property : properties) {
            int eq = property.indexOf('=');
            args.put(property.substring(0, eq), property.substring(eq + 1));
        }
        String[] cli = args.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        return new SpringApplicationBuilder(YokeApplication.class, StubModelConfiguration.class)
                .profiles("test").run(cli);
    }

    private static AiService independentClient() throws NacosException {
        NacosSupport.redirectClientFiles(TEST_WORKSPACE);
        Properties props = new Properties();
        props.setProperty(PropertyKeyConst.SERVER_ADDR, NacosCompose.serverAddr());
        return AiFactory.createAiService(props);
    }

    @Test
    void 探活成功时返回可用的客户端() throws Exception {
        AiService service = NacosSupport.connect(nacosProps(true, false), TEST_WORKSPACE);

        assertThat(service).isNotNull();
        service.shutdown();
    }

    @Test
    void 端口不对时探活失败而不是静默() {
        // 主端口对、gRPC 端口（主端口 + 1000）连不上：这是最常见的接线错误
        String[] hostPort = NacosCompose.serverAddr().split(":");
        int wrongMain = Integer.parseInt(hostPort[1]) + 1;
        YokeProperties.Nacos wrong = new YokeProperties.Nacos("127.0.0.1:" + wrongMain, null, null, null,
                new YokeProperties.Nacos.A2aSwitch(true, false), new YokeProperties.Nacos.PromptSwitch(false, "k", java.time.Duration.ofSeconds(2)));

        assertThatThrownBy(() -> NacosSupport.connect(wrong, TEST_WORKSPACE)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法使用 Nacos 的 AI 通道");
    }

    @Test
    void 开关打开且Nacos不可达时整个应用启动失败() {
        assertThatThrownBy(() -> start("yoke.nacos.prompt.enabled=true", "yoke.nacos.server-addr=127.0.0.1:1",
                "yoke.nacos.password=S3cret-Pass-xyz", "server.port=0"))
                .satisfies(e -> {
                    StringBuilder all = new StringBuilder();
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        all.append(t.getMessage()).append('\n');
                    }
                    assertThat(all.toString()).contains("无法使用 Nacos 的 AI 通道").doesNotContain("S3cret-Pass-xyz");
                });
    }

    @Test
    void Prompt热更新_改完下一轮对话就用新版本且不重复拼接() {
        String key = uniqueId("hot-prompt");
        NacosCompose.publishPrompt(key, "1.0.0", "【热更新版本一】请在回答末尾加上喵。");
        try (ConfigurableApplicationContext context = start("yoke.nacos.prompt.enabled=true",
                "yoke.nacos.prompt.key=" + key, "server.port=0")) {
            RecordingModel model = context.getBean(RecordingModel.class);
            var controller = context.getBean(com.yoke.controller.ChatController.class);
            String session = uniqueId("hot");

            controller.chat(new com.yoke.model.ChatRequest(session, "第一轮")).block();
            String first = systemText(model, "第一轮");
            assertThat(first).contains("【热更新版本一】").contains("你是一个乐于助人的 AI 助手");
            assertThat(first.split("【热更新版本一】", -1)).as("只出现一次").hasSize(2);

            NacosCompose.publishPrompt(key, "1.0.1", "【热更新版本二】请在回答末尾加上汪。");
            // 发布到生效之间有一小段服务端传播延迟（实测几十到几百毫秒），所以每隔一会儿再问一轮，最多等 10 秒
            long start = System.nanoTime();
            String second = "";
            for (int turn = 0; turn < 50 && !second.contains("【热更新版本二】"); turn++) {
                String user = "第二轮-" + turn;
                controller.chat(new com.yoke.model.ChatRequest(session, user)).block();
                second = systemText(model, user);
                if (!second.contains("【热更新版本二】")) {
                    sleepQuietly(200);
                }
            }
            System.out.println("PROMPT-HOT-UPDATE 发布后 " + (System.nanoTime() - start) / 1_000_000 + " ms 内生效");

            assertThat(second).contains("【热更新版本二】").doesNotContain("【热更新版本一】");
            assertThat(second.split("【热更新版本二】", -1)).hasSize(2);
        }
    }

    @Test
    void Prompt在Nacos里不存在时对话照常进行() {
        try (ConfigurableApplicationContext context = start("yoke.nacos.prompt.enabled=true",
                "yoke.nacos.prompt.key=" + uniqueId("missing"), "server.port=0")) {
            RecordingModel model = context.getBean(RecordingModel.class);
            var reply = context.getBean(com.yoke.controller.ChatController.class)
                    .chat(new com.yoke.model.ChatRequest(uniqueId("s"), "你好")).block();

            assertThat(reply.reply()).isEqualTo("ok");
            assertThat(systemText(model, "你好")).contains("你是一个乐于助人的 AI 助手");
        }
    }

    /** 该 Agent 的端点（服务实例）数量：端点注册在命名服务里，Card 本身是配置数据，不能用 Card 判断在线与否。 */
    private static int endpointCount(String agentName) {
        String body = NacosCompose.httpGet("/nacos/v3/admin/ns/service/list?pageNo=1&pageSize=200&groupNameParam=agent-endpoints");
        try {
            for (var item : new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("data").path("pageItems")) {
                // 服务名里的非字母数字被转义成 _ASCII 码，所以按转义后的名字比较
                if (item.path("name").asText().contains(escape(agentName))) {
                    return item.path("ipCount").asInt();
                }
            }
            return 0;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(body, e);
        }
    }

    private static String escape(String name) {
        StringBuilder out = new StringBuilder();
        for (char c : name.toCharArray()) {
            out.append(Character.isLetter(c) || c == '-' ? String.valueOf(c) : "_" + String.format("%03d", (int) c));
        }
        return out.toString();
    }

    @Test
    void A2A服务就绪后注册到Nacos关闭后注销() throws Exception {
        int port = A2aRaw.freePort();
        String name = uniqueId("yoke-reg");
        AiService observer = independentClient();
        try {
            try (ConfigurableApplicationContext context = start("yoke.a2a.server.enabled=true",
                    "yoke.a2a.server.card.name=" + name, "yoke.nacos.a2a.enabled=true", "server.port=" + port,
                    "yoke.a2a.server.public-url=http://127.0.0.1:" + port)) {
                var card = observer.getAgentCard(name);

                assertThat(card.getName()).isEqualTo(name);
                assertThat(card.getUrl()).isEqualTo("http://127.0.0.1:" + port + "/a2a");
                assertThat(card.getCapabilities().getStreaming()).as("注册的 Card 也要说真话").isFalse();
                assertThat(endpointCount(name)).as("就绪后端点已注册").isEqualTo(1);
            }
            long start = System.nanoTime();
            int remaining = endpointCount(name);
            while (remaining > 0 && System.nanoTime() - start < 15_000_000_000L) {
                sleepQuietly(300);
                remaining = endpointCount(name);
            }
            System.out.println("DEREGISTER 关闭后等待 " + (System.nanoTime() - start) / 1_000_000 + " ms，剩余端点 " + remaining);
            assertThat(remaining).as("关闭后端点已注销").isZero();
        } finally {
            observer.shutdown();
        }
    }

    @Test
    void 主动注销会在客户端仍连着时就删除端点() throws Exception {
        // 区别于「关闭应用」：这里客户端连接一直在，端点只可能是被 deregister 删掉的，而不是连接断开后被服务端摘除
        String name = uniqueId("yoke-dereg");
        AiService client = NacosSupport.connect(nacosProps(true, false), TEST_WORKSPACE);
        try {
            io.a2a.spec.AgentCard card = new io.a2a.spec.AgentCard.Builder().name(name).description("d")
                    .url("http://127.0.0.1:45678/a2a").version("1.0.0").protocolVersion("0.3.0").preferredTransport("JSONRPC")
                    .capabilities(new io.a2a.spec.AgentCapabilities(false, false, false, java.util.List.of()))
                    .defaultInputModes(java.util.List.of("text")).defaultOutputModes(java.util.List.of("text"))
                    .skills(java.util.List.of()).build();
            NacosA2aRegistrar registrar = new NacosA2aRegistrar(client, false);
            registrar.register(card);
            assertThat(endpointCount(name)).isEqualTo(1);

            registrar.deregister();

            int remaining = endpointCount(name);
            for (int i = 0; i < 40 && remaining > 0; i++) {
                sleepQuietly(250);
                remaining = endpointCount(name);
            }
            assertThat(remaining).isZero();
        } finally {
            client.shutdown();
        }
    }

    @Test
    void 经Nacos发现远端并调用() {
        int port = A2aRaw.freePort();
        String name = uniqueId("yoke-disc");
        try (ConfigurableApplicationContext context = start("yoke.a2a.server.enabled=true",
                "yoke.a2a.server.card.name=" + name, "yoke.nacos.a2a.enabled=true", "server.port=" + port,
                "yoke.a2a.server.public-url=http://127.0.0.1:" + port, "yoke.a2a.remote.agent-name=" + name)) {
            A2aRaw.Response response = new A2aRaw(port, null).postJson("/api/chat/remote",
                    "{\"sessionId\":\"" + uniqueId("d") + "\",\"message\":\"经 Nacos 发现\"}");

            assertThat(response.status()).as(response.body()).isEqualTo(200);
            assertThat(response.json().path("reply").asText()).isEqualTo("ok");
            assertThat(context.getBean(RecordingModel.class).received.stream().flatMap(java.util.List::stream)
                    .anyMatch(m -> m.getTextContent() != null && m.getTextContent().contains("经 Nacos 发现"))).isTrue();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 主 Agent 处理某句话时模型收到的系统提示词（框架后台还会用同一个模型做记忆整理等调用，所以按用户的话找）。 */
    private static String systemText(RecordingModel model, String userText) {
        var call = model.received.stream()
                .filter(messages -> messages.stream().anyMatch(m -> m.getRole() == MsgRole.USER
                        && m.getTextContent() != null && m.getTextContent().contains(userText)))
                .filter(messages -> messages.stream().anyMatch(m -> m.getRole() == MsgRole.SYSTEM
                        && m.getTextContent() != null && m.getTextContent().contains("你是一个乐于助人的 AI 助手")))
                .reduce((first, second) -> second).orElseThrow();
        return call.stream().filter(m -> m.getRole() == MsgRole.SYSTEM)
                .map(m -> m.getTextContent()).reduce("", (a, b) -> a + "\n" + b);
    }
}
