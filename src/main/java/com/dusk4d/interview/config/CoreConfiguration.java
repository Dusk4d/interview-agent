package com.dusk4d.interview.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 基础 Bean：可注入的时间源（测试可替换，保证评估/报告时间可断言）。 */
@Configuration
public class CoreConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
