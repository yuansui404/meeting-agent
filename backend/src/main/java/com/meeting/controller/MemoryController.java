package com.meeting.controller;

import com.meeting.common.ApiResponse;
import com.meeting.controller.dto.request.SaveMemoryRequest;
import com.meeting.controller.dto.response.TextContentVO;
import com.meeting.user.service.MemoryStorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryStorageService memoryStorageService;

    @GetMapping("/memory")
    public ApiResponse<TextContentVO> readMemory() {
        String content = memoryStorageService.read();
        return ApiResponse.ok(new TextContentVO(content));
    }

    @PutMapping("/memory")
    public ApiResponse<Void> saveMemory(@Valid @RequestBody SaveMemoryRequest request) {
        memoryStorageService.write(request.content());
        return ApiResponse.ok(null);
    }
}
