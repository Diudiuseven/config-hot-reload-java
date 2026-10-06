package com.github.diudiuseven.confighotreloadjava.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 热加载机制自身的可调参数。 */
@ConfigurationProperties(prefix = "config.reload")
public class ReloadProperties {

    /** 监听的本地配置文件，按优先级从低到高排列。 */
    private List<String> files = List.of();

    /** 轮询间隔。 */
    private Duration pollInterval = Duration.ofSeconds(1);

    /** 抖动合并窗口：变更发生后等待静默再重载。 */
    private Duration debounceWindow = Duration.ofMillis(300);

    /** 单次重载失败后的重试间隔。 */
    private Duration retryInterval = Duration.ofMillis(500);

    /** 一次变更从发现到放弃重试的最长等待时间。 */
    private Duration maxWait = Duration.ofSeconds(10);

    public List<String> getFiles() {
        return files;
    }

    public void setFiles(List<String> files) {
        this.files = files;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getDebounceWindow() {
        return debounceWindow;
    }

    public void setDebounceWindow(Duration debounceWindow) {
        this.debounceWindow = debounceWindow;
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
}
