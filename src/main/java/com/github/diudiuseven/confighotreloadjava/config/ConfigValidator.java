package com.github.diudiuseven.confighotreloadjava.config;

import java.util.List;
import java.util.Map;

/** 合并后配置的校验器。返回空列表表示通过。 */
@FunctionalInterface
public interface ConfigValidator {

    /**
     * @return 校验错误列表，元素应能定位到具体配置项；空列表表示通过
     */
    List<String> validate(Map<String, String> merged);
}
