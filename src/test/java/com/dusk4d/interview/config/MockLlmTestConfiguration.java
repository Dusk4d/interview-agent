package com.dusk4d.interview.config;

import com.dusk4d.interview.llm.LlmClient;
import com.dusk4d.interview.llm.MockLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 集成测试专用配置：用确定性 Mock 替换真实模型客户端。
 *
 * <p>这样端到端测试在没有本地模型、没有网络的环境下也能完整跑通，
 * 并且断言可复现；同时不影响生产装配（只在测试类显式 import 时生效）。
 */
@TestConfiguration
public class MockLlmTestConfiguration {

    @Bean
    @Primary
    public LlmClient mockLlmClient(ObjectMapper objectMapper) {
        return new MockLlmClient(objectMapper);
    }
}
