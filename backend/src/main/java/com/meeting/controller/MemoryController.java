package com.meeting.controller;

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

    private static final Path MEMORY_FILE = Path.of("../.agentscope/workspace", "MEMORY.md");

    @GetMapping("/memory")
    public ResponseEntity<Map<String, Object>> readMemory() {
        String content = "";
        if (Files.exists(MEMORY_FILE)) {
            try {
                content = Files.readString(MEMORY_FILE, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return ResponseEntity.ok(Map.of("success", false, "error", "读取记忆文件失败"));
            }
        }
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of("content", content)));
    }

    @PutMapping("/memory")
    public ResponseEntity<Map<String, Object>> saveMemory(@RequestBody Map<String, String> body) {
        String content = body.getOrDefault("content", "");
        try {
            Files.createDirectories(MEMORY_FILE.getParent());
            Files.writeString(MEMORY_FILE, content, StandardCharsets.UTF_8);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "保存记忆文件失败"));
        }
    }
}
