package com.meeting.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "zhipu")
public class ZhiPuProperties {
    private String apiKey = "";
    private String model = "glm-4v";
    private String url = "https://open.bigmodel.cn/api/paas/v4";
}
