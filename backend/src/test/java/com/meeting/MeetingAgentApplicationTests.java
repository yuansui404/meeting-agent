package com.meeting;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class MeetingAgentApplicationTests {

    @Test
    void contextLoads() {
        // 验证 Spring 上下文可以加载
    }
}
