package com.github.diudiuseven.confighotreloadjava.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配热加载组件：来源、校验器、管理器、文件监听。
 */
@Configuration
@EnableConfigurationProperties(ConfigReloadProperties.class)
public class ConfigHotReloadConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(ConfigHotReloadConfiguration.class);

    @Bean
    public List<ConfigSource> configSources(ConfigReloadProperties properties) {
        List<ConfigSource> sources = new ArrayList<>();
        List<ConfigReloadProperties.Source> defs = properties.getSources();
        for (int i = 0; i < defs.size(); i++) {
            ConfigReloadProperties.Source def = defs.get(i);
            Path path = Path.of(def.getPath());
            String name = def.getName() != null ? def.getName()
                    : path.getFileName().toString();
            // 列表顺序即优先级：越靠后优先级越高
            sources.add(new FileConfigSource(name, i, path));
        }
        return sources;
    }

    @Bean
    public List<ConfigValidator> configValidators() {
        List<ConfigValidator> validators = new ArrayList<>();
        // 内置校验：不允许空值，错误信息定位到配置项与其来源
        validators.add(candidate -> {
            List<String> errors = new ArrayList<>();
            candidate.values().forEach((key, value) -> {
                if (value == null || value.isBlank()) {
                    errors.add("配置项[" + key + "]（来自 "
                            + candidate.provenance().get(key) + "）值为空");
                }
            });
            return errors;
        });
        return validators;
    }

    @Bean
    public HotReloadConfigManager hotReloadConfigManager(
            List<ConfigSource> sources,
            List<ConfigValidator> validators,
            ConfigReloadProperties properties) {
        HotReloadConfigManager manager =
                new HotReloadConfigManager(sources, validators, properties);
        // 启动时做首次加载；失败则以空配置启动，后续变更仍可生效
        ReloadResult initial = manager.reload();
        log.info("initial config load: status={} version={} errors={}",
                initial.status(), initial.version(), initial.errors());
        return manager;
    }

    @Bean(destroyMethod = "close")
    public ConfigFileWatcher configFileWatcher(List<ConfigSource> sources,
                                               HotReloadConfigManager manager,
                                               ConfigReloadProperties properties)
            throws IOException {
        List<FileConfigSource> fileSources = sources.stream()
                .filter(FileConfigSource.class::isInstance)
                .map(FileConfigSource.class::cast)
                .toList();
        ConfigFileWatcher watcher = new ConfigFileWatcher(fileSources, manager);
        if (properties.isWatchEnabled() && !fileSources.isEmpty()) {
            watcher.start();
        }
        return watcher;
    }
}
