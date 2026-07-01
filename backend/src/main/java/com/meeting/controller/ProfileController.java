package com.meeting.controller;

import com.meeting.service.ProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/files")
    public ResponseEntity<Map<String, Object>> listFiles() {
        List<String> files = profileService.listFiles();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", files
        ));
    }

    @GetMapping("/{filename:.+}")
    public ResponseEntity<Map<String, Object>> readFile(@PathVariable String filename) {
        try {
            String content = profileService.readFile(filename);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", Map.of("filename", filename, "content", content)
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{filename:.+}")
    public ResponseEntity<Map<String, Object>> saveFile(@PathVariable String filename,
                                                         @RequestBody Map<String, String> body) {
        try {
            String content = body.getOrDefault("content", "");
            profileService.saveFile(filename, content);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @PostMapping("/{filename:.+}")
    public ResponseEntity<Map<String, Object>> createFile(@PathVariable String filename) {
        try {
            profileService.createFile(filename);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @DeleteMapping("/{filename:.+}")
    public ResponseEntity<Map<String, Object>> deleteFile(@PathVariable String filename) {
        try {
            profileService.deleteFile(filename);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }
}
