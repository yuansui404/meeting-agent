package com.meeting.controller;

import com.meeting.common.ApiResponse;
import com.meeting.controller.dto.request.SaveProfileRequest;
import com.meeting.controller.dto.request.ToggleProfileRequest;
import com.meeting.user.model.entity.ProfileMetadataEntity;
import com.meeting.user.service.ProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    @GetMapping("/files")
    public ApiResponse<List<Map<String, Object>>> listFiles() {
        List<String> files = profileService.listFiles();
        List<Map<String, Object>> result = files.stream().map(filename -> {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("filename", filename);
            profileService.findMeta(filename).ifPresent(meta -> {
                item.put("description", meta.getDescription());
                item.put("enabled", meta.isEnabled());
                item.put("updatedAt", meta.getUpdatedAt());
            });
            return item;
        }).toList();
        return ApiResponse.ok(result);
    }

    @GetMapping("/{filename}")
    public ApiResponse<Map<String, String>> readFile(@PathVariable String filename) {
        String content = profileService.readFile(filename);
        return ApiResponse.ok(Map.of("content", content));
    }

    @PutMapping("/{filename}")
    public ApiResponse<Void> saveFile(@PathVariable String filename,
                                      @Valid @RequestBody SaveProfileRequest request) {
        profileService.saveFile(filename, request.content());
        if (request.description() != null) {
            profileService.updateMeta(filename, request.description(), null);
        }
        return ApiResponse.ok(null);
    }

    @PostMapping("/{filename}")
    public ApiResponse<Void> createFile(@PathVariable String filename,
                                        @RequestBody(required = false) SaveProfileRequest request) {
        profileService.createFile(filename);
        if (request != null && request.description() != null) {
            profileService.updateMeta(filename, request.description(), null);
        }
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{filename}")
    public ApiResponse<Void> deleteFile(@PathVariable String filename) {
        profileService.deleteFile(filename);
        return ApiResponse.ok(null);
    }

    @PatchMapping("/{filename}/toggle")
    public ApiResponse<Void> toggleFile(@PathVariable String filename,
                                        @Valid @RequestBody ToggleProfileRequest request) {
        profileService.updateMeta(filename, null, request.enabled());
        return ApiResponse.ok(null);
    }
}
