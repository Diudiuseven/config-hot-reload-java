package com.github.diudiuseven.confighotreloadjava.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 本地 properties 文件来源。
 *
 * <p>容忍整体覆盖写入与快速连续写入：读到不完整内容（解析失败、键无值、
 * 内容在读取期间仍在变化）时按策略重试，超过重试上限后抛出
 * {@link ConfigSourceException}，绝不把写了一半的内容当作有效配置。
 */
public class FileConfigSource implements ConfigSource {

    /** 判定“读到一半”的启发规则：行不含 '=' 且不是注释/空行。 */
    private static final int MAX_READ_ATTEMPTS = 5;
    private static final long RETRY_DELAY_MS = 20;

    private final String name;
    private final int priority;
    private final Path file;

    public FileConfigSource(String name, int priority, Path file) {
        this.name = name;
        this.priority = priority;
        this.file = file;
    }

    public Path file() {
        return file;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public Map<String, String> load() throws ConfigSourceException {
        if (!Files.isRegularFile(file)) {
            throw new ConfigSourceException(name, "来源文件不存在: " + file, null);
        }
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_READ_ATTEMPTS; attempt++) {
            try {
                byte[] content = Files.readAllBytes(file);
                Map<String, String> parsed = parse(content);
                // 读取期间文件又被改动（快速连续写入），视为不稳定，重读
                if (isStable(content)) {
                    return parsed;
                }
            } catch (IOException | IllegalArgumentException e) {
                lastError = e;
            }
            sleepQuietly();
        }
        throw new ConfigSourceException(name,
                "来源 " + name + " (" + file + ") 读取不稳定或格式损坏，已重试 "
                        + MAX_READ_ATTEMPTS + " 次", lastError);
    }

    private Map<String, String> parse(byte[] content) throws IOException {
        Properties props = new Properties();
        try (InputStream in = new java.io.ByteArrayInputStream(content)) {
            props.load(in);
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            result.put(key, props.getProperty(key));
        }
        // 启发式校验：存在非注释非空、且不含 '=' 的行，说明可能读到写了一半的内容
        String text = new String(content, java.nio.charset.StandardCharsets.ISO_8859_1);
        for (String line : text.split("\r?\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("!")
                    && !trimmed.contains("=") && !trimmed.contains(":")
                    && !trimmed.endsWith("\\")) {
                throw new IllegalArgumentException(
                        "来源 " + name + " 存在不完整行（可能写入未完成）: " + trimmed);
            }
        }
        return result;
    }

    /** 读取前后各取一次文件状态，内容或元数据变化则认为写入仍在进行。 */
    private boolean isStable(byte[] content) throws IOException {
        byte[] again = Files.readAllBytes(file);
        return java.util.Arrays.equals(content, again);
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
