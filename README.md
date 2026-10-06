# config-hot-reload-java

运行期配置热加载：配置放在本地文件，进程运行中改动自动发现并生效，
任何时刻读到的配置都是一个自洽的完整版本。

## 快速开始

```properties
# application.properties
config.reload.files[0]=/etc/myapp/base.properties
config.reload.files[1]=/etc/myapp/override.properties
```

业务代码通过 `ConfigService` 读取：

```java
Optional<String> v = configService.get("app.threads");
Map<String, String> all = configService.all();
long version = configService.version();
```

REST 入口（演示/运维用）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/config` | 当前版本号、全部生效值、每项来源 |
| GET | `/config/{key}` | 单个配置项 |
| POST | `/config/reload` | 手动触发一次重载 |
| POST | `/config/rollback` | 回退到最近一次已生效版本 |
| GET | `/config/diff` | 最近一次生效变更的差异 |

## 重载语义

- **原子生效**：每次重载先完整加载、合并、校验，全部通过后一次性切换
  `AtomicReference` 中的不可变快照。读取无锁、不阻塞，任何时刻只能读到
  某个完整版本，不会看到新旧字段混合，也不会看到重载中间状态。
- **半写入容错**：文件被整体覆盖或读到写了一半的内容时，解析失败不会让
  错误内容生效——该来源回退到最近一次成功加载的值；若该来源从未成功过，
  本次重载整体拒绝（`REJECTED`），旧版本继续服务。
- **校验失败**：合并结果未通过 `ConfigValidator` 时保持旧版本，不留半生效
  状态；错误信息定位到具体配置项，来源级错误可通过
  `HotReloadConfigManager.sourceErrors()` 定位到具体来源。
- **无变化不产生虚假变更**：合并结果与当前版本完全一致时返回 `NO_CHANGE`，
  不递增版本号、不产生差异记录。
- **差异输出**：每次生效的变更（含回退）都记录 `ConfigDiff`
  （added / removed / changed，changed 含新旧值），经 `/config/diff` 或
  `lastDiff()` 获取。
- **回退**：`rollback()` 恢复到最近一次已生效版本。回退与重载在同一把锁上
  串行，并发发生时结果确定，中间状态对读取方不可见。

## 优先级与覆盖规则

- `config.reload.files` 列表顺序即优先级：**越靠后优先级越高**，高优先级
  来源的同名键覆盖低优先级来源。
- 单个来源缺失、损坏或暂时不可读**不影响其他来源**：该来源用最近一次
  成功内容兜底，其余来源的变更照常生效。
- 每个生效键的来源记录在快照的 `provenance` 中，经 `/config` 接口可查。

## 抖动合并与重试

后台线程按 `poll-interval` 轮询文件指纹（修改时间+大小）：

1. 发现变化后先等待 `debounce-window` 静默期，期间持续变化则继续等待，
   短时间内的连续写入被合并为一次重载；
2. 重载被拒绝（来源损坏/校验失败）时按 `retry-interval` 重试，
   累计超过 `max-wait` 放弃本轮，保持旧版本，不会忙循环；
3. 来源恢复后下一轮轮询自动生效。

## 可用配置项（`config.reload.*`）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `files` | `[]` | 监听的本地 properties 文件，按优先级从低到高排列 |
| `poll-interval` | `1s` | 变更轮询间隔 |
| `debounce-window` | `300ms` | 抖动合并窗口：变更后等待静默再重载 |
| `retry-interval` | `500ms` | 重载被拒绝后的重试间隔 |
| `max-wait` | `10s` | 一次变更从发现到放弃重试的最长等待时间 |

## 自定义校验

声明自己的 `ConfigValidator` Bean 即可覆盖默认校验（默认仅拒绝空值）：

```java
@Bean
ConfigValidator configValidator() {
    return merged -> merged.containsKey("app.threads")
            ? List.of()
            : List.of("key 'app.threads' is required");
}
```

## 测试

```bash
mvn test
```

覆盖场景：整体覆盖写入、写了一半的截断内容、连续抖动合并、来源损坏兜底、
校验失败保持旧版本、并发读取不混版本、并发重载与回退的确定性、
来源长时间抖动后的自动恢复、优先级覆盖与来源追踪、回退语义。
