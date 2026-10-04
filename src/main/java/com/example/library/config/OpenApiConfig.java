package com.example.library.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI cityLibraryOpenApi() {
        return new OpenAPI().info(new Info()
                .title("城市图书馆跨馆借还调拨服务 API")
                .version("1.0.0")
                .description("""
                        覆盖：书目检索（本馆/跨馆库存、预约队列、ETA、电子资源）、预约排队与公平性规则、
                        跨馆调拨物流、借阅/续借/逾期罚款、还书核验与赔偿、
                        物流丢包/声称已还/临时闭馆等异常的一致性处理、管理端统计与规则调整审计、
                        热门书需求建议（新增采购/暂停续借）与新购复本按预约时间+分馆等待的均衡分配（馆员可改派留痕）。
                        测试读者账号：R001 张三 / R002 李四 / R003 王五；馆员操作在请求体中传 staff。
                        """)
                .license(new License().name("内部演示").url("https://example.com")));
    }
}
