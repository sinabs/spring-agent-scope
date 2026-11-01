package com.yoke.nacos;

import com.yoke.config.YokeProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 探活失败路径：不需要 Docker，用一个不存在的端口。 */
class NacosSupportTests {

    private static final String TEST_WORKSPACE = System.getProperty("java.io.tmpdir") + "/yoke-test-workspace";

    private static YokeProperties.Nacos nacos(String addr) {
        return new YokeProperties.Nacos(addr, null, "nacos-user-xyz", "S3cret-Pass-xyz",
                new YokeProperties.Nacos.A2aSwitch(true, false), new YokeProperties.Nacos.PromptSwitch(false, "k", java.time.Duration.ofSeconds(2)));
    }

    @Test
    void 连不上Nacos时启动失败且报错信息是中文并不含凭证() {
        assertThatThrownBy(() -> NacosSupport.connect(nacos("127.0.0.1:1"), TEST_WORKSPACE))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).contains("无法使用 Nacos 的 AI 通道").contains("127.0.0.1:1")
                            .contains("3.x").contains("9848");
                    assertThat(e.getMessage()).doesNotContain("nacos-user-xyz").doesNotContain("S3cret-Pass-xyz");
                    assertThat(e.getCause()).as("底层异常可能带着凭证，不作为 cause").isNull();
                });
    }

    @Test
    void 配置对象的toString不含密码() {
        String text = nacos("127.0.0.1:8848").toString();

        assertThat(text).contains("password=***").doesNotContain("S3cret-Pass-xyz");
    }

    @Test
    void 创建客户端之前就把目录指到工作区() {
        System.clearProperty("JM.LOG.PATH");
        System.clearProperty("JM.SNAPSHOT.PATH");

        assertThatThrownBy(() -> NacosSupport.connect(nacos("127.0.0.1:1"), TEST_WORKSPACE)).isInstanceOf(IllegalStateException.class);

        assertThat(System.getProperty("JM.LOG.PATH")).isNotNull().contains("yoke-test-workspace");
        assertThat(System.getProperty("JM.SNAPSHOT.PATH")).isNotNull().contains("yoke-test-workspace");
    }

    @Test
    void 客户端的日志和快照目录被指到工作区而不是用户主目录() {
        NacosSupport.redirectClientFiles(TEST_WORKSPACE);

        assertThat(System.getProperty("JM.LOG.PATH")).endsWith("nacos/logs").doesNotStartWith(System.getProperty("user.home") + "/logs");
        assertThat(System.getProperty("JM.SNAPSHOT.PATH")).endsWith("nacos/snapshot");
    }
}
