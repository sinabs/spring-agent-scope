package com.yoke.controller;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoke.a2a.A2aGateway;
import com.yoke.a2a.runner.YokeAgentRunner;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;

import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 认证先于读体、超限不读体：用一个会数「被读走多少字节」的请求证明。
 * 请求体是读不完的无限数据流，如果实现像旧版那样（{@code @RequestBody String}）把体全读进来，这里会读到内存耗尽或测试超时。
 */
class A2aControllerBodyLimitTests {

    private static final int MAX = 1000;
    private static final String TOKEN = "body-limit-token-0123456789";
    /** 实现读取时的缓冲粒度留一点余量，远小于「读完整个体」。 */
    private static final long SLACK = 16 * 1024;

    /** 会计数的请求：体是读不完的零字节流。 */
    static final class CountingRequest extends MockHttpServletRequest {

        final AtomicLong bytesRead = new AtomicLong();
        private final long declaredLength;
        private final long available;

        /** declaredLength 为 -1 表示没声明长度（chunked）；available 是体实际有多少字节。 */
        CountingRequest(long declaredLength, long available) {
            super("POST", "/a2a");
            this.declaredLength = declaredLength;
            this.available = available;
        }

        @Override
        public long getContentLengthLong() {
            return declaredLength;
        }

        @Override
        public ServletInputStream getInputStream() {
            InputStream source = new InputStream() {
                private long remaining = available;

                @Override
                public int read() {
                    if (remaining <= 0) {
                        return -1;
                    }
                    remaining--;
                    bytesRead.incrementAndGet();
                    return ' ';
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    if (remaining <= 0) {
                        return -1;
                    }
                    int n = (int) Math.min(Math.min(len, 4096), remaining);
                    java.util.Arrays.fill(b, off, off + n, (byte) ' ');
                    remaining -= n;
                    bytesRead.addAndGet(n);
                    return n;
                }
            };
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return false;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                }

                @Override
                public int read() throws IOException {
                    return source.read();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    return source.read(b, off, len);
                }
            };
        }
    }

    private static A2aController controller(String token) {
        AgentCard card = new AgentCard.Builder().name("n").description("d").url("http://h/a2a").version("1")
                .capabilities(new AgentCapabilities(false, false, false, List.of()))
                .defaultInputModes(List.of("text")).defaultOutputModes(List.of("text")).skills(List.of()).build();
        return new A2aController(new A2aGateway(mock(AgentScopeA2aServer.class), mock(YokeAgentRunner.class), card, token, MAX));
    }

    private static CountingRequest authorized(CountingRequest request) {
        request.addHeader("Authorization", "Bearer " + TOKEN);
        return request;
    }

    @Test
    void 未认证时即使声明了超大的Content_Length也不读体() {
        CountingRequest request = new CountingRequest(500_000_000L, Long.MAX_VALUE);

        ResponseEntity<String> response = controller(TOKEN).message(request);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(request.bytesRead.get()).isZero();
    }

    @Test
    void 未认证的chunked请求也不读体() {
        CountingRequest request = new CountingRequest(-1, Long.MAX_VALUE);

        ResponseEntity<String> response = controller(TOKEN).message(request);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(request.bytesRead.get()).isZero();
    }

    @Test
    void token错误同样不读体() {
        CountingRequest request = new CountingRequest(-1, Long.MAX_VALUE);
        request.addHeader("Authorization", "Bearer wrong-token");

        ResponseEntity<String> response = controller(TOKEN).message(request);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(request.bytesRead.get()).isZero();
    }

    @Test
    void 已认证但声明的长度超限时直接拒绝且不读体() throws Exception {
        CountingRequest request = authorized(new CountingRequest(MAX + 1, Long.MAX_VALUE));

        ResponseEntity<String> response = controller(TOKEN).message(request);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(new ObjectMapper().readTree(response.getBody()).path("error").path("code").asInt()).isEqualTo(-32600);
        assertThat(request.bytesRead.get()).isZero();
    }

    @Test
    void chunked超限时限长读取并中止() {
        CountingRequest request = authorized(new CountingRequest(-1, Long.MAX_VALUE));

        ResponseEntity<String> response = controller(TOKEN).message(request);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(request.bytesRead.get()).as("最多读到上限加一个缓冲粒度").isLessThanOrEqualTo(MAX + SLACK);
    }

    @Test
    void 没有配置token时同样限长() {
        CountingRequest request = new CountingRequest(-1, Long.MAX_VALUE);

        ResponseEntity<String> response = controller(null).message(request);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(request.bytesRead.get()).isLessThanOrEqualTo(MAX + SLACK);
    }

    @Test
    void 正常大小的请求读完整个体并继续处理() {
        // 体是 500 个空格，不是有效请求：得到的是 200 加 JSON-RPC 的请求无效错误（不是 401 也不是 413），说明通过了认证和大小检查，体也被读完
        CountingRequest request = authorized(new CountingRequest(500, 500));

        ResponseEntity<String> response = controller(TOKEN).message(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("-32600").contains("\"error\"");
        assertThat(request.bytesRead.get()).isEqualTo(500);
    }
}
