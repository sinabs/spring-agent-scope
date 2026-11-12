package com.yoke.model;

/**
 * 知识库里的一个片段，也是检索的返回单位。
 *
 * @param id     片段的稳定标识（文档名加序号，如 api-usage.md#2）：多路检索融合时靠它判断是不是同一个片段，
 * {@code retrieve} 也把它当作引用编号返回给模型，所以它在整个对话里必须固定指向同一个片段
 * @param source 片段所属文档的标题，引用时展示给用户
 * @param text   片段正文
 */
public record Passage(String id, String source, String text) {
}
