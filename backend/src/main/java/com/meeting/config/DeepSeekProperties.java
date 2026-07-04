package com.meeting.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "deepseek")
public class DeepSeekProperties {
    @NotBlank(message = "deepseek.api-key 不能为空，请通过环境变量或配置中心注入")
    private String apiKey;
    private String model = "deepseek-chat";
    private String url = "https://api.deepseek.com";
}
