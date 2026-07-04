package com.meeting.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "zhipu")
public class ZhiPuProperties {
    @NotBlank(message = "zhipu.api-key 不能为空，请通过环境变量或配置中心注入")
    private String apiKey;
    private String model = "glm-4v";
    private String url = "https://open.bigmodel.cn/api/paas/v4";
}
