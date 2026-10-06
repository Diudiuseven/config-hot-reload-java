package com.github.diudiuseven.confighotreloadjava.config;

import java.util.Map;

/**
 * 一个配置来源。优先级数值越大，覆盖能力越强。
 */
public interface ConfigSource {

    /** 来源名称，用于差异与错误定位。 */
    String name();

    /** 优先级，数值大的来源覆盖数值小的来源。 */
    int priority();

    /**
     * 读取当前来源的全部配置项。
     *
     * @throws ConfigSourceException 来源缺失、损坏或暂时不可读时抛出
     */
    Map<String, String> load() throws ConfigSourceException;
}
