package com.yoke.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import lombok.extern.slf4j.Slf4j;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Yoke 的子 Agent 声明，两种来源并存：
 *
 * <ul>
 *   <li><b>编程式</b>：{@link #programmatic()}，在代码里用 {@link SubagentDeclaration} 声明，例如 {@code summarizer}；</li>
 *   <li><b>工作区文件</b>：{@code src/main/resources/workspace/subagents/*.md}，文件名即 agent id，
 *       启动时拷贝到 {@code yoke.workspace/subagents/}，由 HarnessAgent 按 2.0.4 的约定从工作区加载。</li>
 * </ul>
 *
 * <p>同一个 id 同时出现在两处时，2.0.4 不会报错：默认的动态加载（DynamicSubagentsMiddleware）里
 * 「动态声明胜出」，工作区文件静默覆盖代码里的声明；框架内置的 {@code general-purpose} 同理，
 * 一个叫 {@code general-purpose.md} 的文件就能把它换掉。配置错位却毫无提示很危险，
 * 所以 Yoke 在启动时自己检查，冲突直接启动失败，见 {@link #requireNoConflict}。
 *
 * <p><b>权限</b>：子 Agent 不继承父 Agent 的 {@code disableWebTools}，只继承文件和 shell 的关闭。
 * 因此子 Agent 的联网禁用靠传给 Builder 的 {@code ToolsConfig.deny}，
 * 它会同时传给内置的 general-purpose 子 Agent 和所有声明的子 Agent，见 {@link #toolsConfig()}。
 */
@Slf4j
public final class YokeSubagents {

    public static final String SUMMARIZER = "summarizer";

    /**
     * 「一个工具都不要」的占位名。2.0.4 里声明的 {@code tools} 为空表示继承父 Agent 的全部工具，
     * 没有办法直接表达「零个」；放一个不存在的名字，白名单非空，其余工具就都被过滤掉了。
     */
    static final String NO_TOOLS = "__no_tools__";

    /**
     * 要对所有 Agent（含全部子 Agent）禁掉的工具：联网工具，以及读类的记忆和会话工具。
     * {@code memory_get} 只校验路径不越出工作区根目录、不校验是否在当前用户目录下，{@code session_history}
     * 把模型给的 agentId/sessionId 直接拼进路径，模型能借它们读到别的用户的记忆和会话；
     * {@code @SafeId} 只管 HTTP 入参，管不到模型传给工具的参数。{@code memory_search} 是否跨用户没有验证，一并拒绝。
     * 保留 {@code memory_save}，它只往当前用户固定的记忆文件里追加，路径不由模型控制。
     */
    static final List<String> DENIED_TOOLS = List.of("web_fetch", "web_search",
            "memory_get", "memory_search", "session_history", "session_list", "session_search");

    /** 工作区里放子 Agent 声明文件的目录名，2.0.4 约定为 {@code <workspace>/subagents/}。 */
    static final String WORKSPACE_DIR = "subagents";

    private YokeSubagents() {
    }

    /** 编程式声明的子 Agent。 */
    public static List<SubagentDeclaration> programmatic() {
        return List.of(SubagentDeclaration.builder()
                .name(SUMMARIZER)
                .description("把一大段文字压缩成要点摘要。需要概括长文本、长工具结果或历史讨论时委派给它；它不会调用任何工具。")
                .inlineAgentsBody("你是摘要助手。只做一件事：把收到的内容概括成不超过 5 条的要点，保持原意，不添加原文没有的信息。")
                .steps(2)
                .tools(List.of(NO_TOOLS))
                .build());
    }

    /**
     * 传给 {@code HarnessAgent.Builder.toolsConfig} 的工具配置：只含一份 deny 名单。
     *
     * <p>传入显式配置还有一个作用：2.0.4 在没有显式配置时会去读工作区里的 {@code tools.json}，
     * 其中可以声明要启动的 stdio MCP 服务。显式传入后不再读取那个文件，堵住这条绕过 MCP 传输限制的路。
     */
    public static io.agentscope.harness.agent.tools.ToolsConfig toolsConfig() {
        io.agentscope.harness.agent.tools.ToolsConfig config = new io.agentscope.harness.agent.tools.ToolsConfig();
        config.setDeny(DENIED_TOOLS);
        return config;
    }

    /** 把 classpath 里的声明文件拷到工作区；每次启动覆盖，保证文件内容与发布的版本一致。 */
    public static Set<String> installWorkspaceFiles(Path workspace) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:workspace/" + WORKSPACE_DIR + "/*.md");
            Path target = workspace.resolve(WORKSPACE_DIR);
            Files.createDirectories(target);
            for (Resource resource : resources) {
                Files.write(target.resolve(resource.getFilename()), resource.getInputStream().readAllBytes());
            }
            return Arrays.stream(resources).map(YokeSubagents::agentId).collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException("安装子 Agent 声明文件失败：" + workspace, e);
        }
    }

    /**
     * 启动时的完整准备：安装随应用发布的声明文件，再扫描工作区里<b>实际存在</b>的全部声明文件做冲突检查。
     *
     * <p>只检查 classpath 里的文件不够：{@code yoke.workspace} 是持久目录，里面手放的、或旧版本遗留的
     * {@code summarizer.md}、{@code general-purpose.md} 在 2.0.4 里「文件赢」，会静默替换代码声明和内置子 Agent。
     * 所以以目录里实际的文件名为准；冲突直接启动失败。其余多出来的文件只记警告（它们会作为子 Agent 被加载）。
     */
    public static void prepareWorkspace(Path workspace, List<SubagentDeclaration> declarations) {
        Set<String> shipped = installWorkspaceFiles(workspace);
        Set<String> actual = existingFileIds(workspace);
        requireNoConflict(declarations, actual);
        actual.stream().filter(id -> !shipped.contains(id))
                .forEach(id -> log.warn("工作区 {}/ 里有非随应用发布的子 Agent 声明文件：{}.md，它会被加载为子 Agent", WORKSPACE_DIR, id));
    }

    /** 工作区 {@code subagents/} 目录下全部 {@code .md} 文件对应的 id。 */
    static Set<String> existingFileIds(Path workspace) {
        Path dir = workspace.resolve(WORKSPACE_DIR);
        if (!Files.isDirectory(dir)) {
            return Set.of();
        }
        try (var files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".md"))
                    .map(name -> name.substring(0, name.length() - ".md".length()))
                    .collect(Collectors.toCollection(java.util.TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException("扫描子 Agent 声明目录失败：" + dir, e);
        }
    }

    /** 框架内置的子 Agent id，声明文件不许占用。 */
    static final String BUILT_IN = "general-purpose";

    /** 同一个 id 不允许同时由代码和文件声明，文件也不许占用框架内置的 id。 */
    public static void requireNoConflict(List<SubagentDeclaration> declarations, Set<String> fileIds) {
        Set<String> taken = declarations.stream().map(SubagentDeclaration::getName)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        taken.add(BUILT_IN);
        Set<String> clashes = fileIds.stream().filter(taken::contains)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        if (!clashes.isEmpty()) {
            throw new IllegalStateException("子 Agent id 重复：" + clashes + " 同时被 workspace/subagents/*.md 和代码或框架内置声明占用。"
                    + "2.0.4 对此不报错，工作区文件会静默覆盖另一处，请改名或删掉其中一处");
        }
    }

    private static String agentId(Resource resource) {
        String name = resource.getFilename();
        return name.substring(0, name.length() - ".md".length());
    }
}
