package com.github.diudiuseven.confighotreloadjava.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 热加载相关可调参数（前缀 config.reload）。
 */
@ConfigurationProperties(prefix = "config.reload")
public class ConfigReloadProperties {

    /** 本地配置文件来源，按列表顺序优先级递增（后者覆盖前者）。 */
    private List<Source> sources = new ArrayList<>();

    /** 防抖窗口：短时间内的连续变更合并为一次重载。 */
    private Duration debounce = Duration.ofMillis(200);

    /** 重载失败后的重试次数（不含首次）。 */
    private int maxRetries = 3;

    /** 重试间隔。 */
    private Duration retryInterval = Duration.ofMillis(500);

    /** 一次重载（含重试）的最长等待时间，超时后放弃本轮，保持旧版本。 */
    private Duration maxWait = Duration.ofSeconds(5);

    /** 是否启用文件变更自动监听。 */
    private boolean watchEnabled = true;

    public List<Source> getSources() {
        return sources;
    }

    public void setSources(List<Source> sources) {
        this.sources = sources;
    }

    public Duration getDebounce() {
        return debounce;
    }

    public void setDebounce(Duration debounce) {
        this.debounce = debounce;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Duration getRetryInterval() {
        return retryInterval;
    }

    public void setRetryInterval(Duration retryInterval) {
        this.retryInterval = retryInterval;
    }

    public Duration getMaxWait() {
        return maxWait;
    }

    public void setMaxWait(Duration maxWait) {
        this.maxWait = maxWait;
    }

    public boolean isWatchEnabled() {
        return watchEnabled;
    }

    public void setWatchEnabled(boolean watchEnabled) {
        this.watchEnabled = watchEnabled;
    }

    /** 单个本地文件来源配置。 */
    public static class Source {

        /** 来源名称（缺省用文件名）。 */
        private String name;

        /** 文件路径。 */
        private String path;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }
}
