package com.xhs.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ★ P2-3：OpenAPI / Swagger 文档
 *
 * 接口说明由代码自动生成；这里补上文档信息与 JWT 鉴权方案，
 * 使 Swagger UI 上可以直接点 "Authorize" 填入 token 调用受保护接口。
 *
 * 生产环境建议关闭（见 application-prod.yml 的 springdoc.*.enabled=false）。
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI xhsOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("xhs-backend API")
                        .description("仿小红书高并发内容社区系统 —— 接口文档。"
                                + "受保护接口需先调用 /api/users/login 拿到 token，再点右上角 Authorize 填入。")
                        .version("1.0.0"))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
