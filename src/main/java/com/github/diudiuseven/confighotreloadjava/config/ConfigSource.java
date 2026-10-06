package com.github.diudiuseven.confighotreloadjava.config;

import java.util.Map;

/**
 * 一个配置来源。不同来源有确定的优先级，高优先级覆盖低优先级。
 */
public interface ConfigSource {

    /** 来源名称，用于定位问题与追踪生效项出处。 */
    String name();

    /** 优先级，数值越大优先级越高。 */
    int priority();

    /**
     * 读取该来源当前的完整配置。
     *
     * @throws ConfigSourceException 来源缺失、损坏或暂时不可读时抛出，不影响其他来源
     */
    Map<String, String> load() throws ConfigSourceException;
}
