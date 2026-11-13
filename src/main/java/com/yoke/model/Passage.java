package com.yoke.model;

/**
 * 知识库里的一个片段，也是检索的返回单位。
 *
 * @param id     片段的稳定标识（文档名加正文摘要，形如「文档名.md#8 位十六进制」）：多路检索融合时靠它判断是不是同一个片段，
 * {@code retrieve} 也把它当作引用编号返回给模型，引用会留在对话历史里跨部署存在，所以同一个 id 只能对应同一段正文
 * @param source 片段所属文档的标题，引用时展示给用户
 * @param text   片段正文
 */
public record Passage(String id, String source, String text) {
}
