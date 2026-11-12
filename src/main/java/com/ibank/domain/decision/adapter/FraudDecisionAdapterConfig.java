package com.ibank.domain.decision.adapter;

import com.ibank.global.config.FdsProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 판정 어댑터 선택. {@code ibank.fds.mode}로 고른다.
 *
 * <ul>
 *   <li>{@code always-approve}(기본): {@link AlwaysApproveFraudDecisionAdapter} — FDS 서비스 없이 ibank만 띄울 때</li>
 *   <li>{@code http}: {@link HttpFraudDecisionAdapter} — 운영 프로파일의 기본값</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(FdsProperties.class)
public class FraudDecisionAdapterConfig {

    @Bean
    @ConditionalOnProperty(name = "ibank.fds.mode", havingValue = "http")
    public HttpFraudDecisionAdapter httpFraudDecisionAdapter(FdsProperties properties) {
        return new HttpFraudDecisionAdapter(properties);
    }
}
