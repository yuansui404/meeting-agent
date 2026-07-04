package com.meeting.service;

import com.meeting.common.BusinessException;
import com.meeting.config.FileProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final FileProperties fileProps;
    private Path profileDir;

    @PostConstruct
    public void init() {
        this.profileDir = Path.of(fileProps.uploadDir(), "profile");
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
        if (!file.startsWith(profileDir)) {
            throw new BusinessException("Invalid filename: " + filename);
        }
        if (!Files.exists(file)) {
            throw BusinessException.notFound("Profile file not found: " + filename);
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw BusinessException.processingFailed("Failed to read profile file: " + filename, e);
        }
    }

    /** Save (overwrite) a profile .md file. */
    public void saveFile(String filename, String content) {
        Path file = profileDir.resolve(filename);
        if (!file.startsWith(profileDir)) {
            throw new BusinessException("Invalid filename: " + filename);
        }
        try {
            Files.createDirectories(profileDir);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            log.info("Saved profile file: {}", filename);
        } catch (IOException e) {
            throw BusinessException.processingFailed("Failed to save profile file: " + filename, e);
        }
    }

    /** Append content to an existing file, or create it if absent. File-lock protected. */
    public void appendFile(String filename, String content) {
        Path file = profileDir.resolve(filename);
        if (!file.startsWith(profileDir)) {
            throw new BusinessException("Invalid filename: " + filename);
        }
        try {
            Files.createDirectories(profileDir);
            try (FileChannel ch = FileChannel.open(file,
                    StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
                 FileLock lock = ch.lock()) {
                String existing = "";
                long size = ch.size();
                if (size > 0) {
                    ByteBuffer buf = ByteBuffer.allocate((int) size);
                    ch.read(buf);
                    buf.flip();
                    existing = StandardCharsets.UTF_8.decode(buf).toString();
                }
                String updated = existing.isBlank()
                        ? content.trim()
                        : existing.trim() + "\n\n" + content.trim();
                ch.truncate(0);
                ch.position(0);
                ch.write(StandardCharsets.UTF_8.encode(updated));
            }
            log.info("Appended to profile file: {}", filename);
        } catch (IOException e) {
            throw BusinessException.processingFailed("Failed to append to profile file: " + filename, e);
        }
    }

    /** Create a new profile .md file. */
    public void createFile(String filename) {
        Path file = profileDir.resolve(filename);
        if (!file.startsWith(profileDir)) {
            throw new BusinessException("Invalid filename: " + filename);
        }
        if (Files.exists(file)) {
            throw new BusinessException("Profile file already exists: " + filename);
        }
        saveFile(filename, "# " + filename.replace(".md", "") + "\n\n");
    }

    /** Delete a profile .md file. */
    public void deleteFile(String filename) {
        Path file = profileDir.resolve(filename);
        if (!Files.exists(file) || !file.startsWith(profileDir)) {
            throw BusinessException.notFound("Profile file not found: " + filename);
        }
        try {
            Files.delete(file);
            log.info("Deleted profile file: {}", filename);
        } catch (IOException e) {
            throw BusinessException.processingFailed("Failed to delete profile file: " + filename, e);
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
