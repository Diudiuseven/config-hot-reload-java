package com.github.diudiuseven.confighotreloadjava.config;

import java.util.List;

/**
 * 一次重载尝试的结果。
 */
public record ReloadResult(Status status,
                           Long version,
                           ConfigDiff diff,
                           List<String> errors) {

    public enum Status {
        /** 新版本已生效 */
        APPLIED,
        /** 内容无变化，未产生新版本 */
        NO_CHANGE,
        /** 校验或读取失败，保持上一版本 */
        REJECTED,
        /** 已回退到上一个生效版本 */
        ROLLED_BACK
    }
}
