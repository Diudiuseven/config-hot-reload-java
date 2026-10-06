package com.github.diudiuseven.confighotreloadjava.config;

import java.util.List;

/** 一次重载尝试的结果。 */
public record ReloadResult(Status status, long version, ConfigDiff diff,
                           List<String> errors) {

    public enum Status {
        /** 新配置已生效 */
        APPLIED,
        /** 内容无变化，未产生新版本 */
        NO_CHANGE,
        /** 校验或来源失败，保持旧版本 */
        REJECTED
    }
}
