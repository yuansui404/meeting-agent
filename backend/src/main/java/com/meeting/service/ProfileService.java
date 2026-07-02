package com.meeting.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;

@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final Path profileDir;

    public ProfileService() {
        this.profileDir = Path.of("../.agentscope/workspace", "profile");
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(profileDir);
            log.info("Profile directory initialized at {}", profileDir);
        } catch (Exception e) {
            log.warn("Failed to initialize profile directory at {}: {}", profileDir, e.getMessage());
        }
    }

    /** List all .md files in the profile directory. */
    public List<String> listFiles() {
        try {
            if (!Files.exists(profileDir)) return List.of();
            try (Stream<Path> files = Files.list(profileDir)) {
                return files
                        .filter(p -> p.toString().endsWith(".md"))
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            log.warn("Failed to list profile files: {}", e.getMessage());
            return List.of();
        }
    }

    /** Read the content of a profile .md file. */
    public String readFile(String filename) {
        Path file = profileDir.resolve(filename);
        if (!Files.exists(file) || !file.startsWith(profileDir)) {
            throw new IllegalArgumentException("Profile file not found: " + filename);
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read profile file: " + filename, e);
        }
    }

    /** Save (overwrite) a profile .md file. */
    public void saveFile(String filename, String content) {
        Path file = profileDir.resolve(filename);
        if (!file.startsWith(profileDir)) {
            throw new IllegalArgumentException("Invalid filename: " + filename);
        }
        try {
            Files.createDirectories(profileDir);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            log.info("Saved profile file: {}", filename);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save profile file: " + filename, e);
        }
    }

    /** Create a new profile .md file. */
    public void createFile(String filename) {
        Path file = profileDir.resolve(filename);
        if (!file.startsWith(profileDir)) {
            throw new IllegalArgumentException("Invalid filename: " + filename);
        }
        if (Files.exists(file)) {
            throw new IllegalArgumentException("Profile file already exists: " + filename);
        }
        saveFile(filename, "# " + filename.replace(".md", "") + "\n\n");
    }

    /** Delete a profile .md file. */
    public void deleteFile(String filename) {
        Path file = profileDir.resolve(filename);
        if (!Files.exists(file) || !file.startsWith(profileDir)) {
            throw new IllegalArgumentException("Profile file not found: " + filename);
        }
        try {
            Files.delete(file);
            log.info("Deleted profile file: {}", filename);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete profile file: " + filename, e);
        }
    }

    /**
     * Build profile context string for system prompt injection.
     * Reads all .md files and concatenates them with headers.
     */
    public String buildProfileContext() {
        List<String> files = listFiles();
        if (files.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n=== 用户画像 ===\n");
        sb.append("以下是关于用户的信息，请参考这些信息来个性化你的回答：\n\n");
        for (String filename : files) {
            try {
                String content = readFile(filename);
                if (content != null && !content.isBlank()) {
                    String label = filename.replace(".md", "");
                    sb.append("【").append(label).append("】\n").append(content).append("\n\n");
                }
            } catch (Exception e) {
                log.warn("Failed to read profile file {}: {}", filename, e.getMessage());
            }
        }
        return sb.toString();
    }

    public Path getProfileDir() {
        return profileDir;
    }
}
