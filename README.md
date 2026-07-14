# Sable Quota

Sable Quota 是面向 Minecraft 1.21.1、NeoForge 和 Sable 的服务器配额模组。它按玩家 UUID 记录物理结构（Sub-Level）归属，限制玩家无限创建结构造成的性能与存储压力。

## 功能

- 普通玩家与 OP 使用不同的默认上限，`-1` 表示无限。
- 支持按玩家 UUID 设置个人上限，离线和重启后仍然保留。
- 覆盖 Simulated 物理装配器、旋转轴承以及 Sable 的安全单结构装配命令。
- 达到上限时取消创建，并发送支持中英文与自定义文本的红色提示。
- 结构真正删除后自动释放额度；普通卸载不会误减计数。
- Sable 自动拆分出的结构继承原结构所有者。
- 玩家可以查询自己结构的 UUID、维度、当前位置或最后已知位置。
- 提供第三方模组单结构创建 API。

## 安装与兼容性

- Minecraft：`1.21.1`
- NeoForge：`21.1.233+`
- Sable：`2.0.3+`（必需）
- Simulated：`1.3.0` 集成目标；未安装时相关可选 Mixin 会跳过。

将构建出的 JAR 放入服务端与客户端的 `mods` 目录。配额判断和数据保存均由服务端完成。

## 配置

首次启动后生成 `config/sable_quota-common.toml`：

```toml
[quota]
# 普通玩家默认上限；-1 为无限，0 为禁止创建。
maxStructuresPerPlayer = 3

# OP 默认上限。
maxStructuresPerOperator = -1

[messages]
# 留空时按照玩家客户端语言使用内置文本。
# 自定义文本支持 {owned} 和 {limit}。
creationBlocked = ""
```

有效额度按以下优先级计算：

```text
个人上限 > OP 默认上限 > 普通玩家默认上限
```

OP 状态不会缓存。升为 OP 或取消 OP 后，下一次查询/创建会立即使用新的身份默认值。降低任何上限都不会删除已有结构；如果已有数量超过新上限，只会禁止继续创建。

## 指令

普通玩家可以查询自己；查询他人、设置、重置和重载需要权限等级 2。

| 指令 | 作用 |
| --- | --- |
| `/sablequota get` | 查看自己的数量、有效上限和额度来源 |
| `/sablequota get <player>` | 查询指定玩家，支持服务器档案中已知的离线玩家 |
| `/sablequota list` | 查看自己的结构位置（第一页） |
| `/sablequota list page <page>` | 查看自己的指定页 |
| `/sablequota list <player> [page]` | 管理员查看指定玩家，每页最多 10 个 |
| `/sablequota set <player> <amount>` | 设置个人上限；`-1` 为无限，不会改动已有结构 |
| `/sablequota reset <player>` | 删除个人上限，恢复跟随普通玩家/OP 默认值 |
| `/sablequota reload` | 校验并重新读取 TOML；失败时旧配置继续生效 |

### 结构坐标

- 已加载结构：读取当前物理姿态坐标。
- 未加载结构：显示持久化的最后已知坐标。
- 查询不会强制加载区块或 Sub-Level。
- 从旧版本升级且尚无坐标记录的结构，会在下次加载、卸载或查询时补齐。

## 数据与稳定性

数据保存在主世界 SavedData：`<world>/data/sable_quota_structures.dat`。

保存内容包括结构 UUID → 玩家 UUID、个人上限覆盖和最后已知坐标。运行时还维护玩家 → 结构集合的反向索引，因此额度计数为 O(1)；坐标查询只处理当前页最多 10 个结构，不进行全服结构扫描，也不会持续轮询移动结构。

安装模组之前已经存在的结构没有可靠创建者信息，无法自动追溯玩家归属。未接入 Sable Quota API 的第三方或系统创建会被静默放行并保持“无归属”：不会抛异常、返回空结构或强制删除，且不计入任何玩家额度。

## 第三方模组集成

Sable 的底层分配接口没有玩家参数。其他模组代表玩家创建一个 Sub-Level 时，应通过公开 API 建立所有权上下文：

```java
Optional<ServerSubLevel> result = SableQuotaApi.createStructure(player, () ->
        (ServerSubLevel) container.allocateNewSubLevel(pose));
```

一次 API 调用最多创建一个 Sub-Level。额度不足时返回 `Optional.empty()` 并通知玩家；回调意外返回 `null` 时也会安全清理临时上下文。

## 构建

```text
gradlew build
```

构建启用了 Java `-Xlint` 静态警告检查。产物位于 `build/libs/`。
