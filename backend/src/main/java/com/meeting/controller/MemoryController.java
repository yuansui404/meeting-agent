package com.meeting.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MemoryController {

    private final Path memoryFile;

    public MemoryController(@Value("${file.upload-dir:/app/data/uploads}") String uploadDir) {
        this.memoryFile = Path.of(uploadDir, "memory", "MEMORY.md");
    }

    @GetMapping("/memory")
    public ResponseEntity<Map<String, Object>> readMemory() {
        String content = "";
        if (Files.exists(memoryFile)) {
            try {
                content = Files.readString(memoryFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return ResponseEntity.ok(Map.of("success", false, "error", "读取记忆文件失败"));
            }
        }
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of("content", content)));
    }

    @PutMapping("/memory")
    public ResponseEntity<Map<String, Object>> saveMemory(@RequestBody Map<String, String> body) {
        String content = body.getOrDefault("content", "");
        if (content.length() > 100_000) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "内容过长，最大100KB"));
        }
        try {
            Files.createDirectories(memoryFile.getParent());
            Files.writeString(memoryFile, content, StandardCharsets.UTF_8);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "保存记忆文件失败"));
        }
    }
}
