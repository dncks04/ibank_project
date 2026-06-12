package com.ibank.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI(Swagger) 문서 설정.
 *
 * <p>인증이 필요한 엔드포인트는 JWT bearer 스킴으로 표시되어 Swagger UI의
 * Authorize 버튼에 access 토큰을 넣으면 바로 호출해 볼 수 있다.
 * 운영(prod)에서는 {@code springdoc.api-docs.enabled=false}로 문서 자체를 끈다.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ibankOpenApi() {
        String scheme = "bearerAuth";
        return new OpenAPI()
                .info(new Info()
                        .title("ibank API")
                        .version("v1")
                        .description("인터넷 뱅킹 API — 거래(이체/입금/출금)는 멱등성 키가 필수이며, "
                                + "같은 키의 재요청은 저장된 결과를 반환합니다."))
                .components(new Components().addSecuritySchemes(scheme,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(scheme));
    }
}
