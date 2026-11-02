# LetsDoCropAddons — 沉浸系列：作物附属

让 NSUK（New Sim U Kraft）的**雇佣农民**通过**农田盒**种植沉浸系列（Let's Do）的作物。
本工程由 `KaleidoscopeCropAddons` 改造而来，框架与 NSUK 农田盒的接线方式完全一致。

- **modid**：`letsdocropaddons`
- **包名**：`com.xiaolianganmuchen.letsdocropaddons`
- **版本**：1.1.0
- **目标**：MC 1.21.1 / NeoForge 21.1.248+

---

## 收录的作物

### 沉浸农艺 Farm & Charm（`farm_and_charm.json`）

| 作物 | 种子 | 作物方块 | 说明 |
|---|---|---|---|
| 番茄 | `farm_and_charm:tomato_seeds` | `farm_and_charm:tomato_crop` | **按耕地走**；`harvest_height=2` + `harvest_blocks=[tomato_crop_body]`，上下两格同时成熟才收割 |
| 燕麦 | `oat_seeds` | `oat_crop` | 标准耕地 |
| 大麦 | `barley_seeds` | `barley_crop` | 标准耕地 |
| 玉米 | `corn` | `corn_crop` | 标准耕地 |
| 生菜 | `lettuce_seeds` | `lettuce_crop` | 标准耕地 |
| 洋葱 | `onion` | `onion_crop` | 标准耕地（洋葱本体即种子） |
| 草莓 | `strawberry_seeds` | `strawberry_crop` | 标准耕地 |

### 盛节精酿 Brewery（`brewery.json`）

| 作物 | 种子 | 作物方块 | 说明 |
|---|---|---|---|
| 啤酒花 | `brewery:hops_crop` | `brewery:hops_crop` | **按耕地走，不搭绳索**；两格结构，`harvest_height=2` |

### 葡园酒香 Vinery（`vinery.json`）

8 种葡萄：红、白、针叶林红/白、热带草原红/白、丛林红/白。

种植方式（与 Kaleidoscope 的藤架不同，原因见下）：

- 作物方块统一用 `vinery:grapevine_stem`（葡萄藤杆，`PaleStemBlock`）。
- 靠新增的 `plant_properties` 在种下时写入葡萄种类：`{"grape": "red" | "white" | "taiga_red" | ...}`。
- 配 `plant_mature: true` 种下即满熟（它的自然生长与播种都要求正下方也是藤杆，农民是一格一株的布局，堆不出柱子）。
- 土壤用泥土类：`dirt / grass_block / coarse_dirt / podzol / rooted_dirt`
  （`grapevine_stem` 的 `canSurvive` 要求下方是红石导体，耕地不满足）。

---

## 未收录

- 树木、蘑菇（属自然类模组，非耕地作物）
- 水稻（需要水田，属尚未被百科收录的 Wok & Bowl）
- 青青草甸 Meadow 的燕麦与香草（可选，尚未加入）

---

## 编译

```bash
gradlew build
```

产物：`build/libs/letsdocropaddons-1.1.0.jar`

### 已知阻塞：`maven.neoforged.net` 不可达

本机当前 **DNS 被污染**（`maven.neoforged.net` 解析到 Facebook 的 IP 段），真实 IP `89.187.187.14`（CDN77）同样连不上，
导致 ModDevGradle 的工具链依赖 `net.neoforged:neoform-runtime:1.0.24` 无法下载（该 artifact 只存在于官方仓库），
且本地 Gradle 缓存中的相关文件已被清理 → **首次构建无法完成**。

**解除方式（任选其一）**：

1. 开启可用的代理 / VPN 后执行 `gradlew build`；
2. 或在可用网络下先把依赖缓存好，之后可 `gradlew build --offline`。

恢复网络后首次构建约需 4 分钟（下载客户端资源 786 MiB + 反编译 MC 约 90 s）。

---

## 如何再加一个沉浸系列模组

在 `src/main/resources/data/letsdocropaddons/letsdo_crops/` 放一份 JSON，并在
`CropJsonReloadListener.BUILTIN_FILES` 里登记文件名：

```json
{
  "mod": "目标模组的 modid",
  "prefix": "abc",
  "label": "source.letsdocropaddons.目标模组的 modid",
  "discover": false,
  "crops": [
    { "id": "xxx", "seed": "ns:seed_item", "plant": "ns:crop_block" }
  ]
}
```

可用字段：`seed / plant / layout(full|stem|trellis) / fruit_block / fruit_item / support_block /
soil_tag / soil_blocks / soil_item / soil_exclude / harvest_blocks / harvest_height /
harvest_max_age / plant_mature / plant_properties / waterlog / allow_non_crop_block`。

> 注意：`mod` 字段对应的模组**未加载时整份 JSON 会被跳过**（有意设计）。

---

## 依赖

| 依赖 | 说明 |
|---|---|
| `simukraft`（New Sim U Kraft 2.2.0） | 必需，提供农田盒与农民系统；`libs/` 内已附带 |
| Architectury API 13.0.11 | Farm & Charm / Brewery / Vinery 的前置 |
| `foundation`（Let's Do 内部库） | **已由各模组 jar-in-jar 内嵌，无需单独安装** |
