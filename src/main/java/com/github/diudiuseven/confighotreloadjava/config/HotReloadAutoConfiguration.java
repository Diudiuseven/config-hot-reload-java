package com.github.diudiuseven.confighotreloadjava.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配热加载组件。 */
@Configuration
@EnableConfigurationProperties(ReloadProperties.class)
public class HotReloadAutoConfiguration {

    /**
     * 默认校验器：值为空串视为非法，错误信息定位到具体配置项。
     * 业务方可声明自己的 {@link ConfigValidator} Bean 覆盖。
     */
    @Bean
    public ConfigValidator configValidator() {
        return merged -> {
            List<String> errors = new ArrayList<>();
            for (var e : merged.entrySet()) {
                if (e.getValue() == null || e.getValue().isBlank()) {
                    errors.add("key '" + e.getKey() + "' must not be blank");
                }
            }
            return errors;
        };
    }

    @Bean(destroyMethod = "stop")
    public HotReloadConfigManager hotReloadConfigManager(ReloadProperties properties,
                                                         ConfigValidator validator) {
        List<ConfigSource> sources = new ArrayList<>();
        List<String> files = properties.getFiles();
        for (int i = 0; i < files.size(); i++) {
            // 列表顺序即优先级：越靠后优先级越高
            sources.add(new FileConfigSource("file[" + i + "]:" + files.get(i),
                    Path.of(files.get(i)), i));
        }
        HotReloadConfigManager manager = new HotReloadConfigManager(sources, validator, properties);
        manager.start();
        return manager;
    }
}
