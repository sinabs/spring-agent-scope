package com.yoke.a2a;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import io.a2a.client.http.A2AHttpClient;
import io.a2a.client.http.A2AHttpResponse;
import org.jspecify.annotations.NonNull;

/**
 * 给 A2A 客户端用的 HTTP 客户端：<b>不跟随重定向</b>，并给每个请求设上超时。
 *
 * <p>官方的 {@code JdkA2AHttpClient} 用 {@code Redirect.NORMAL}（读 A2A SDK 字节码所得，并实测）：
 * 远端对 {@code POST /a2a} 回一个 307，客户端会把消息体原样重发到 Location 指向的任意主机。
 * 同源校验只管 Agent Card 里的地址，管不到重定向，所以这里直接把重定向关掉：3xx 当作失败。
 * 超时也一并解决了「{@code Mono.timeout} 只取消等待、底层请求还挂着」的问题。
 *
 * <p>只实现同步的 GET 和 POST：Yoke 的客户端是非流式的，SSE 和 DELETE 不会被用到，调用即报错。
 */
public class NoRedirectHttpClient implements A2AHttpClient {

    private final HttpClient client;
    private final Duration requestTimeout;

    public NoRedirectHttpClient(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** 关闭底层连接池与 SelectorManager 线程。 */
    public void close() {
        client.close();
    }

    /** 底层 HttpClient 是否已关闭，供生命周期测试断言。 */
    public boolean isTerminated() {
        return client.isTerminated();
    }

    @Override
    public @NonNull GetBuilder createGet() {
        return new Get();
    }

    @Override
    public @NonNull PostBuilder createPost() {
        return new Post();
    }

    @Override
    public @NonNull DeleteBuilder createDelete() {
        throw new UnsupportedOperationException("Yoke 的 A2A 客户端不使用 DELETE");
    }

    private abstract static class Base<T extends Builder<T>> implements Builder<T> {
        String url;
        final Map<String, String> headers = new HashMap<>();

        abstract T self();

        @Override
        public @NonNull T url(@NonNull String url) {
            this.url = url;
            return self();
        }

        @Override
        public @NonNull T addHeaders(@NonNull Map<String, String> more) {
            headers.putAll(more);
            return self();
        }

        @Override
        public @NonNull T addHeader(@NonNull String name, @NonNull String value) {
            headers.put(name, value);
            return self();
        }
    }

    private HttpRequest.Builder request(Base<?> base) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base.url)).timeout(requestTimeout);
        base.headers.forEach(builder::header);
        return builder;
    }

    private A2AHttpResponse send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = response.statusCode();
        return new A2AHttpResponse() {
            @Override
            public int status() {
                return status;
            }

            @Override
            public boolean success() {
                return status >= 200 && status < 300;
            }

            @Override
            public @NonNull String body() {
                return response.body();
            }
        };
    }

    private final class Get extends Base<GetBuilder> implements GetBuilder {

        @Override
        GetBuilder self() {
            return this;
        }

        @Override
        public @NonNull A2AHttpResponse get() throws IOException, InterruptedException {
            return send(request(this).GET().build());
        }

        @Override
        public @NonNull CompletableFuture<Void> getAsyncSSE(@NonNull Consumer<String> messageConsumer, @NonNull Consumer<Throwable> errorConsumer,
                @NonNull Runnable completeRunnable) {
            throw new UnsupportedOperationException("Yoke 的 A2A 客户端不使用流式");
        }
    }

    private final class Post extends Base<PostBuilder> implements PostBuilder {

        private String body = "";

        @Override
        PostBuilder self() {
            return this;
        }

        @Override
        public @NonNull PostBuilder body(@NonNull String body) {
            this.body = body;
            return this;
        }

        @Override
        public @NonNull A2AHttpResponse post() throws IOException, InterruptedException {
            return send(request(this).POST(HttpRequest.BodyPublishers.ofString(body)).build());
        }

        @Override
        public @NonNull CompletableFuture<Void> postAsyncSSE(@NonNull Consumer<String> messageConsumer, @NonNull Consumer<Throwable> errorConsumer,
                @NonNull Runnable completeRunnable) {
            throw new UnsupportedOperationException("Yoke 的 A2A 客户端不使用流式");
        }
    }
}
