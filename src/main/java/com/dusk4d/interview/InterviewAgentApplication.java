package com.dusk4d.interview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 简历驱动的 AI 面试陪练系统 —— 应用入口。
 *
 * <p>模块划分（对应方案书第六节）：
 * <ul>
 *   <li>{@code parse}   简历导入、文本清洗、事实结构化</li>
 *   <li>{@code rag}     Embedding、向量检索、知识库与事实检索</li>
 *   <li>{@code llm}     模型适配层（OpenAI 兼容 / 本地模型 / Mock）与结构化输出解析</li>
 *   <li>{@code agent}   问题生成、答案评估、追问、报告与有限状态机</li>
 *   <li>{@code service} 业务编排与会话状态变更（模型不直接改状态）</li>
 *   <li>{@code api}     REST 接口层（参数校验、错误码、幂等）</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class InterviewAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(InterviewAgentApplication.class, args);
    }
}
