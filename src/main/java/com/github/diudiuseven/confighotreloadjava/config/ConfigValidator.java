package com.github.diudiuseven.confighotreloadjava.config;

import java.util.List;

/**
 * 对合并后的完整配置做校验。校验失败时保持上一个可用版本。
 */
public interface ConfigValidator {

    /**
     * @return 校验错误列表，空列表表示通过；错误信息需定位到具体配置项
     */
    List<String> validate(ConfigSnapshot candidate);
}
