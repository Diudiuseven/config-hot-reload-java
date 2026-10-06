package com.github.diudiuseven.confighotreloadjava.config;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 本地 properties 文件来源。
 *
 * <p>读取时一次性读入全部字节再解析：若文件被整体覆盖或读到写了一半
 * 的内容，解析失败会抛出 {@link ConfigSourceException}，由上层决定
 * 重试或保持旧版本，绝不让半个文件的内容生效。</p>
 */
public class FileConfigSource implements ConfigSource {

    private final String name;
    private final Path path;
    private final int priority;

    public FileConfigSource(String name, Path path, int priority) {
        this.name = name;
        this.path = path;
        this.priority = priority;
    }

    public Path path() {
        return path;
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
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new ConfigSourceException(name,
                    "source '" + name + "' (" + path + ") is not readable: " + e.getMessage(), e);
        }
        Properties props = new Properties();
        try {
            props.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
        } catch (IOException | IllegalArgumentException e) {
            // IllegalArgumentException: 形如 unicode 转义被截断等半写入内容
            throw new ConfigSourceException(name,
                    "source '" + name + "' (" + path + ") is malformed: " + e.getMessage(), e);
        }
        Map<String, String> entries = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            entries.put(key, props.getProperty(key));
        }
        return entries;
    }
}
