# EndRefresh 实现原理

本文档说明本项目中“定时清理末地远端区块”功能的实现方式、关键类、配置项以及安全策略。

## 功能目标

模组会在服务端运行期间比对系统时间，按配置的真实时间间隔自动扫描末地存档。对于末地区块坐标 `x`、`z` 不在中心范围 `[-30, 30]` 内的区块，模组会尝试删除对应区块数据。

为了避免误删玩家正在活动的区域，清理时会跳过玩家附近一定范围内的区块，也会跳过当前仍被服务器加载的区块。清理完成后，模组会向所有在线玩家广播清理结果。

## 入口与事件注册

模组入口类是：

`src/main/java/com/helloluckyhuang/endrefresh/EndRefresh.java`

构造函数中完成两件事：

1. 注册通用配置：

   ```java
   modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
   ```

2. 将清理器注册到 NeoForge 事件总线：

   ```java
   NeoForge.EVENT_BUS.register(EndChunkCleaner.class);
   ```

真正的清理逻辑位于：

`src/main/java/com/helloluckyhuang/endrefresh/EndChunkCleaner.java`

它监听 `ServerTickEvent.Post`，即每个服务端 tick 结束时检查一次是否需要启动或推进清理任务。

同时它也监听 `RegisterCommandsEvent`，注册手动清理命令：

```text
/endrefresh cleanup
```

该命令需要 2 级权限，也就是普通服务端管理员权限。执行后会立即启动一次末地清理，不需要等待下一次定时触发。

如果当前已经有清理任务正在运行，命令不会并发启动第二个任务，而是向执行者提示清理已在运行中。

## 时间调度机制

清理不是根据游戏时间，而是根据系统时间：

```java
long now = System.currentTimeMillis();
```

第一次 tick 时，模组不会立即清理，而是设置下一次执行时间：

```java
nextRunAtMillis = now + intervalMillis();
```

之后每个服务端 tick 都会比较当前系统时间和 `nextRunAtMillis`：

```java
if (now >= nextRunAtMillis) {
    activeRun = CleanupRun.start(event.getServer());
}
```

一次清理完成后，会重新计算下一次清理时间：

```java
nextRunAtMillis = System.currentTimeMillis() + intervalMillis();
```

这样可以保证清理间隔以真实时间为准，不受服务器 TPS、暂停或游戏时间规则影响。

手动命令 `/endrefresh cleanup` 使用同一套 `CleanupRun` 流程。它只绕过“等待下一次定时触发”这个步骤，不绕过中心范围保护、玩家附近保护、已加载区块跳过和分批删除逻辑。

即使 `enabled = false` 禁用了自动定时清理，手动命令启动的清理仍会继续在后续 tick 中推进，直到本次清理完成。`enabled` 控制的是自动定时触发，不是管理员手动触发。

## 配置项

配置类是：

`src/main/java/com/helloluckyhuang/endrefresh/Config.java`

当前提供以下配置：

| 配置项 | 默认值 | 作用 |
| --- | ---: | --- |
| `enabled` | `true` | 是否启用自动清理 |
| `cleanupIntervalDays` | `1` | 每隔多少真实天执行一次清理 |
| `keptCenterRadiusChunks` | `30` | 永久保留中心区块范围，默认保留 `[-30, 30]` |
| `playerProtectionRadiusChunks` | `8` | 保护玩家附近区块的半径 |
| `chunksPerTick` | `128` | 每 tick 最多调度多少个区块删除操作 |
| `timeBasedGeneratedLootSeeds` | `true` | 结构生成奖励箱时是否把系统时间混入奖励随机种子 |

配置文件会在运行后生成到：

`run/config/endrefresh-common.toml`

## 手动清理指令

管理员可以直接执行：

```text
/endrefresh cleanup
```

命令效果：

1. 立即创建一次末地清理任务。
2. 后台扫描末地 region 文件。
3. 在后续服务端 tick 中分批删除候选区块。
4. 完成后广播清理结果。

命令不会阻塞到清理完成。执行命令后会先提示：

```text
[EndRefresh] End cleanup started.
```

真正的删除结果仍以最终广播消息为准。

如果清理已经在运行，再次执行命令会提示：

```text
[EndRefresh] End cleanup is already running.
```

## 奖励箱随机种子

Minecraft 的结构奖励箱通常不会在结构生成时立刻填充物品，而是在箱子方块实体上保存两个字段：

```text
LootTable
LootTableSeed
```

玩家第一次打开箱子时，游戏会根据 `LootTable` 和 `LootTableSeed` 生成实际物品。原版在结构模板放置时，会在 `StructureTemplate.placeInWorld` 中为 `RandomizableContainer` 写入奖励箱种子：

```java
structuretemplate$structureblockinfo.nbt.putLong("LootTableSeed", random.nextLong());
```

这个 `random` 来自世界生成随机源。对于同一个世界种子、同一个结构位置和同一次生成流程，删除区块后再次生成时往往会得到相同的 `LootTableSeed`，从而导致奖励箱内容重复。

为了解决这个问题，模组添加了统一的种子混合工具：

`src/main/java/com/helloluckyhuang/endrefresh/LootSeedRandomizer.java`

配置开启时，模组会先取得原版种子，再混入当前系统时间：

```java
long seed = random.nextLong();
long time = System.currentTimeMillis() ^ System.nanoTime();
```

随后经过几轮位旋转、异或和乘法扰动，生成新的 `LootTableSeed`。这样同一个被删除的末地区块再次生成时，即使结构位置和世界种子相同，只要生成发生在不同时间，奖励箱种子也会变化。

目前覆盖三条原版奖励容器路径：

| Mixin | 原版目标 | 覆盖场景 |
| --- | --- | --- |
| `StructureTemplateMixin` | `StructureTemplate.placeInWorld` | 模板里直接带 `LootTable` NBT 的容器 |
| `RandomizableContainerMixin` | `RandomizableContainer.setBlockEntityLootTable` | 末地城这类通过结构数据标记后置设置奖励表的容器 |
| `StructurePieceMixin` | `StructurePiece.createChest/createDispenser` | 部分旧式结构 piece 直接创建箱子或发射器奖励容器 |

特别说明：末地城奖励箱走的是 `EndCityPieces.handleDataMarker("Chest")`，它会调用：

```java
RandomizableContainer.setBlockEntityLootTable(level, random, blockpos, BuiltInLootTables.END_CITY_TREASURE);
```

因此只修改 `StructureTemplate.placeInWorld` 并不能影响末地城奖励箱。当前实现已经额外覆盖 `RandomizableContainer.setBlockEntityLootTable`，这才是末地城宝箱随机种子变化的关键路径。

该功能由配置项控制：

```toml
timeBasedGeneratedLootSeeds = true
```

关闭时，Mixin 仍然存在，但会直接返回原版 `random.nextLong()` 的结果，不改变原版奖励箱种子行为。

注意：这些注入点位于通用结构生成逻辑，因此它会影响所有通过这些原版路径设置 `LootTableSeed` 的结构奖励容器，不只限于末地。实际与本模组清理功能配合时，主要效果体现在末地区块删除后重新生成的末地城奖励箱。

## 清理目标维度

清理只处理末地：

```java
ServerLevel endLevel = server.getLevel(Level.END);
```

如果末地维度未加载，清理不会执行，并在日志中输出警告。

末地存档目录通过原版接口计算：

```java
Path worldPath = server.getWorldPath(LevelResource.ROOT);
Path endDimensionPath = DimensionType.getStorageFolder(Level.END, worldPath);
Path regionPath = endDimensionPath.resolve("region");
```

对于原版末地，实际目录通常是：

`<世界目录>/DIM1/region`

## 区块扫描方式

清理开始时会在后台 I/O 线程扫描末地 `region` 目录：

```java
CompletableFuture.supplyAsync(
    () -> scanRegionFiles(regionPath, keptCenterRadius, playerProtectionRadius, protectedPlayers),
    Util.ioPool()
);
```

扫描目标是形如下面格式的 region 文件：

```text
r.<regionX>.<regionZ>.mca
```

每个 `.mca` region 文件包含 `32 * 32` 个区块槽位。模组只读取 region 文件开头的 4096 字节 offset 表：

```java
ByteBuffer header = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);
```

offset 表中每 4 字节对应一个区块。如果某个槽位的 offset 为 `0`，说明该区块不存在；如果不为 `0`，说明这个区块在 region 文件中有数据。

槽位索引转换为区块坐标：

```java
int localX = index % 32;
int localZ = index / 32;
ChunkPos pos = new ChunkPos(regionX * 32 + localX, regionZ * 32 + localZ);
```

扫描阶段会先排除两类区块：

1. 中心保留范围内的区块。
2. 清理开始瞬间玩家附近保护范围内的区块。

其余区块会进入候选队列，等待主线程分批删除。

## 中心范围判断

中心保留范围使用区块坐标判断：

```java
pos.x >= -radius && pos.x <= radius && pos.z >= -radius && pos.z <= radius
```

默认 `radius = 30`，因此 `x` 和 `z` 都在 `[-30, 30]` 内的区块永远不会被清理。

注意这里判断的是“两个坐标都在范围内”。只要 `x` 或 `z` 有一个超出范围，该区块就属于可清理区域。

## 玩家保护范围

玩家保护基于玩家当前所在区块：

```java
ChunkPos playerChunk = player.chunkPosition();
```

判断方式是方形范围：

```java
Math.abs(pos.x - playerChunk.x) <= radius
    && Math.abs(pos.z - playerChunk.z) <= radius
```

默认保护半径为 `8`，也就是玩家所在区块周围 `17 * 17` 个区块不会被删除。

清理实现里有两次保护判断：

1. 扫描阶段使用清理开始时的玩家位置做快照过滤。
2. 真正删除前再次读取实时玩家位置过滤。

第二次检查可以防止玩家在扫描完成后移动到即将删除的区域。

## 跳过已加载区块

真正删除前还会检查区块是否仍在服务器内存中：

```java
level.getChunkSource().chunkMap.getVisibleChunkIfPresent(packedPos) != null
    || level.areEntitiesLoaded(packedPos)
```

只要区块数据或实体数据仍处于加载状态，本轮清理就会跳过该区块。

这样做的原因是：如果直接删除已加载区块的磁盘数据，内存中的区块之后仍可能被服务器重新保存，从而把刚删除的数据写回磁盘，甚至造成状态不一致。

因此本功能更偏向“清理已经卸载的远端末地区块”。如果某个远端区块本轮因为加载而跳过，它会在之后卸载并满足条件时被下一轮清理。

## 实际删除内容

Minecraft 1.21.1 中，区块相关数据分布在多个存储中：

| 数据类型 | 目录 | 说明 |
| --- | --- | --- |
| 地形、方块、方块实体、结构等 | `region` | 主区块数据 |
| 实体 | `entities` | 独立实体区块数据 |
| POI | `poi` | 村民兴趣点等点位数据 |

因此删除时不会只删除 `region`，还会同时清理 `entities` 和 `poi`：

```java
CompletableFuture<Void> chunkDelete = chunkMap.write(pos, null);
CompletableFuture<Void> entityDelete = entityStorage.write(pos, null);
CompletableFuture<Void> poiDelete = poiStorage.write(pos, null);
```

其中 `write(pos, null)` 是原版存储层已有语义：向指定区块写入 `null`，底层会调用 `RegionFile.clear(chunkPos)`，清空 `.mca` 中对应区块槽位，并删除可能存在的外置 `.mcc` 大区块文件。

这比手动解析和修改 `.mca` 文件更稳，因为它复用了原版 `IOWorker` 和 `RegionFileStorage` 的线程与缓存机制。

## 为什么使用 Mixin

原版有些必要的存储对象是私有字段，普通模组代码无法直接访问。这里使用 Mixin accessor 暴露这些字段：

| Accessor | 目标类 | 目的 |
| --- | --- | --- |
| `ServerLevelAccessor` | `ServerLevel` | 取得 `entityManager` |
| `PersistentEntitySectionManagerAccessor` | `PersistentEntitySectionManager` | 取得实体持久化存储 |
| `EntityStorageAccessor` | `EntityStorage` | 取得实体 `SimpleRegionStorage` |
| `SectionStorageAccessor` | `SectionStorage` | 取得 POI `SimpleRegionStorage` |

Mixin 配置文件：

`src/main/resources/endrefresh.mixins.json`

`META-INF/neoforge.mods.toml` 中已经声明：

```toml
[[mixins]]
config = "endrefresh.mixins.json"
```

因此 NeoForge 加载模组时会自动加载这些 accessor。

## 分批删除与性能控制

清理扫描在后台线程完成，避免在主线程长时间遍历文件。

实际删除调度在服务端 tick 中分批推进：

```java
int limit = Math.max(1, Config.chunksPerTick);
```

默认每 tick 最多调度 `128` 个区块删除操作，避免一次性向 I/O worker 塞入过多任务。

同时循环条件会参考：

```java
event.hasTime()
```

如果当前 tick 没有额外时间，清理会暂停到后续 tick 继续推进。

## 完成后的刷新与广播

所有候选区块处理完成，并且所有删除任务的 `CompletableFuture` 都完成后，会执行刷新：

```java
chunkMap.flushWorker();
entityStorage.synchronize(true).join();
poiStorage.synchronize(true).join();
```

刷新完成后会向所有玩家广播结果：

```java
server.getPlayerList().broadcastSystemMessage(message, false);
```

消息内容包含：

- 删除的区块数量。
- 中心区域保留的区块数量。
- 玩家附近保护的区块数量。
- 因已加载而跳过的区块数量。
- 删除失败数量。
- 扫描失败的 region 文件数量。

源码中中文文本使用 Unicode 转义保存，是为了避免部分 Windows/Gradle 环境用 GBK 编译 Java 源码时出现编码错误；游戏内实际显示仍是中文。

## 安全边界

当前实现刻意遵守以下边界：

1. 不删除中心 `[-30, 30]` 范围内的末地区块。
2. 不删除玩家附近保护范围内的区块。
3. 不删除当前仍被服务器加载的区块。
4. 不直接手写 `.mca` 文件，而是复用原版存储 worker。
5. 清理只针对末地，不影响主世界和下界。
6. 扫描失败或删除失败不会崩服，只会记录日志并在最终消息中统计失败。

## 已知限制

1. 玩家保护范围目前是方形范围，不是圆形距离。
2. 已加载远端区块会被跳过，需要等它们卸载后在下一轮清理。
3. 清理只根据 region offset 表判断区块是否存在，不读取完整 NBT，因此扫描速度快，但不会按生物群系、结构或区块内容做更细粒度过滤。
4. 如果其他模组在同一时间强制加载或频繁保存远端末地区块，这些区块可能在本轮被跳过或之后被重新生成。

## 构建验证

当前实现已经通过 Gradle 构建：

```text
gradle build
```

构建产物：

`build/libs/endrefresh-0.0.1.jar`

注意：项目中没有 `gradlew.bat`，而本机 PATH 中的 `gradle` 是 8.0，不满足 NeoForge ModDev 插件要求。验证时使用的是本机 Gradle wrapper 缓存中的 Gradle 8.8。
