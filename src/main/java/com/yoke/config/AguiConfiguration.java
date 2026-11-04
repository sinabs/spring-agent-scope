package com.yoke.config;

import com.yoke.codec.AguiStreamCodec;
import com.yoke.codec.HintEventConverter;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.tool.ToolMergeMode;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 流式接口的 AG-UI 协议配置。只用官方的协议库，不引官方的 agui starter。
 */
@Configuration(proxyBeanMethods = false)
public class AguiConfiguration {

    /**
     * 适配器配置。Yoke 不做状态同步，也不输出思考过程；token 用量以 {@code CUSTOM token_usage} 事件输出；
     * 出错时只发 {@code RUN_ERROR}，不再补 {@code RUN_FINISHED}。
     *
     * <p>{@code runTimeout} 保持默认：2.0.4 里这个配置只有 getter，适配器并没有读它，所以不要指望它能限制运行时长。
     *
     * <p>工具只用服务端注册的（{@code AGENT_ONLY}）。codec 已经丢弃了客户端的 {@code tools}，这里再兜一层：
     * 默认的 {@code MERGE_EXTERNAL_PRIORITY} 下，一旦客户端工具被透传进来，就会盖掉同名的服务端工具。
     */
    @Bean
    public AguiAdapterConfig aguiAdapterConfig() {
        return AguiAdapterConfig.builder()
                .emitStateEvents(false)
                .emitTokenUsage(true)
                .enableReasoning(false)
                .toolMergeMode(ToolMergeMode.AGENT_ONLY)
                .addEventConverter(new HintEventConverter())
                .build();
    }

    @Bean
    public AguiStreamCodec aguiStreamCodec(AguiAdapterConfig config) {
        return new AguiStreamCodec(config);
    }
}
