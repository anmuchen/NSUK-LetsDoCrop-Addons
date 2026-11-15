package com.xiaolianganmuchen.letsdocropaddons.crop;

import com.nsukcropapi.api.BoxView;
import com.nsukcropapi.api.Boxes;
import com.nsukcropapi.api.PlotView;
import com.nsukcropapi.nsuk.WorkContainers;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

public final class TrellisCropWork {
   public static final int TRELLIS_HEIGHT = 3;
   /** 藤蔓从格子正上方第 1 层一直长到最顶层。果实在每一层都可能结，产量与高度数成正比。 */
   public static final int VINE_LAYERS = 3;
   /**
    * 木格架（丛林葡萄）的空手收割门槛：{@code age > 2} 时原版 {@code useItemOn} 会掉葡萄、
    * 把 AGE 复位到 1 并同步方块实体（Vinery {@code LatticeBlock.useItemOn}）。
    */
   private static final int LATTICE_HARVEST_AGE = 3;
   /**
    * 藤茎堆（普通葡萄）的空手收割门槛：{@code age > 3} 时原版 {@code useWithoutItem} 会掉葡萄
    * 并把 AGE 复位到 2（Vinery {@code StemBlock.useWithoutItem}）。
    */
   private static final int VINE_STACK_HARVEST_AGE = 4;
   private static final String TYPE = "type";
   private static final String SINGLE = "single";
   private static final String WAXED = "waxed";
   private static final ResourceLocation GREEN_GRAPE = ResourceLocation.parse("kaleidoscope_tavern:green_grape");
   private static final ResourceLocation LEGACY_TRELLIS_ID = ResourceLocation.parse("kaleidoscope_tavern:trellis");
   private static Block legacyTrellis;

   private TrellisCropWork() {
   }

   // =====================================================================
   // 公开入口：按布局分发（藤架 / 木格架 / 藤茎堆 / 自定义土壤作物）
   // =====================================================================

   public static Item statusItem(ServerLevel level, ResolvedCrop crop, BoxView data) {
      if (crop == null) {
         return Items.AIR;
      } else if (level != null && data != null && data.plot() != null && data.boxPos() != null) {
         PlotView plot = data.plot();
         List<BlockPos> chests = data.chests(level);
         BlockPos boxPos = data.boxPos();
         if (crop.isLattice()) {
            return latticeStatusItem(level, crop, plot, boxPos, chests);
         } else if (crop.isVineStack()) {
            return vineStackStatusItem(level, crop, plot, boxPos, chests);
         } else if (!crop.isTrellis()) {
            return soilCropStatusItem(level, crop, plot, boxPos, chests);
         }

         // 葡萄藤架分四阶段显示：
         //   1) plot 任意 cell 还缺柱子 / 横梁   → 「先等待葡萄藤杆/格架」
         //   2) 框架齐了，但有 hang 位空着         → 「等待对应种子」
         //   3) 框架齐了，所有 hang 都在长且未熟   → 「等待成熟 xxxx」
         //   4) 框架齐了，至少一串熟了             → 返回 AIR，由 HARVEST 阶段接管
         // 这里强制按这个优先级扫描：哪怕某一格只有 1 个空 hang 位、其它格全在长未熟，
         // 只要整片田还有任何一根柱子 / 横梁没铺好，就优先报「等杆子」，避免状态文字乱跳。
         boolean anyFrameWorkToDo = hasFrameWork(level, crop, plot, boxPos);
         boolean anyEmptyHang = false;
         boolean allGrowing = true;

         for (int index = 0; index < Math.max(1, plot.cellCount()); index++) {
            BlockPos fruitPos = plot.cellAt(index);
            if (fruitPos.getX() == boxPos.getX() && fruitPos.getZ() == boxPos.getZ()) {
               continue;
            }
            if (!level.isLoaded(fruitPos)) {
               continue;
            }
            if (anyFrameWorkToDo) {
               // 阶段 1：报「等杆子」。角格要 post、非角格要 beam；缺哪根报哪根。
               if (isCorner(plot, fruitPos)) {
                  if (missingPost(level, crop, fruitPos) != null) {
                     return frameWorkShortage(fruitPos, chests, level, crop);
                  }
               } else if (missingBeam(level, crop, fruitPos) != null) {
                  return frameWorkShortage(fruitPos, chests, level, crop);
               }
               continue;
            }
            if (isCorner(plot, fruitPos)) {
               continue;
            }
            if (firstEmptyVine(level, crop, fruitPos) != null) {
               anyEmptyHang = true;
               allGrowing = false;
            } else if (!allHangMatureOrAbsent(level, crop, fruitPos)) {
               allGrowing = false;
            }
         }

         // 阶段 1 没有未铺的框架时，再走 2 / 3 / 4
         Item next = Items.AIR;
         for (int index = 0; index < Math.max(1, plot.cellCount()); index++) {
            BlockPos fruitPos = plot.cellAt(index);
            if (fruitPos.getX() == boxPos.getX() && fruitPos.getZ() == boxPos.getZ()) {
               continue;
            }
            if (!level.isLoaded(fruitPos)) {
               continue;
            }
            if (isCorner(plot, fruitPos)) {
               continue;
            }
            if (anyEmptyHang) {
               // 阶段 2：报「等种子」。等「整片田的葡萄种子都到位」再统一种。
               if (firstEmptyVine(level, crop, fruitPos) != null) {
                  Item seed = crop.seed();
                  if (seed != Items.AIR && !WorkContainers.has(level, chests, seed)) {
                     return seed;
                  }
                  if (next == Items.AIR) {
                     next = seed;
                  }
               }
            } else if (allGrowing) {
               // 阶段 3：报「等成熟」。所有 hang 位都在长（age < max），展示一颗同模组的成熟葡萄，
               // 让玩家从 NPC 头顶的「葡萄」读出"在等成熟"。
               Item fruit = crop.fruitItem();
               if (fruit != Items.AIR && !WorkContainers.has(level, chests, fruit)) {
                  return fruit;
               }
               if (next == Items.AIR) {
                  next = fruit;
               }
            }
         }

         return next != Items.AIR ? next : crop.fruitItem();
      } else {
         return crop.seed();
      }
   }

   /**
    * 按「当前正在执行的阶段」报出对应的材料，让农民头顶的文字和它手上的活一致。
    *
    * <p>宿主把阶段以状态键传进来：{@code .till} 只会要土壤；{@code .plant} 要么在立柱子 / 铺横梁，
    * 要么在插葡萄藤 —— 两者需要的东西不同；只有没有目标（{@code missing_seed} 等）时，
    * 才退回全局优先级扫描去猜「下一件缺的东西」。</p>
    */
   public static Item statusItemForPhase(ServerLevel level, ResolvedCrop crop, BoxView data, String phaseKey) {
      if (crop == null) {
         return Items.AIR;
      } else if (level == null || data == null || data.plot() == null || data.boxPos() == null) {
         return crop.seed();
      } else {
         PlotView plot = data.plot();
         List<BlockPos> chests = data.chests(level);
         boolean tillPhase = phaseKey != null && phaseKey.endsWith(".till");
         boolean plantPhase = phaseKey != null && phaseKey.endsWith(".plant");
         if (tillPhase) {
            Item soil = availableSoilItem(level, chests, crop);
            return soil != Items.AIR ? soil : crop.soilItem();
         } else if (plantPhase) {
            if (crop.isLattice()) {
               // 丛林葡萄 PLANT 阶段：缺格架（或有杂物要清）→ 格架材料；格架齐了但有未播种的 → 种子；
               // 全部已播种 → 葡萄成品（等成熟 / 等收）。
               if (anyLatticeToDo(level, crop, plot, data.boxPos())) {
                  return availableLatticeItem(level, chests, crop);
               }
               if (anyLatticeUnseeded(level, crop, plot, data.boxPos())) {
                  return crop.seed() != Items.AIR ? crop.seed() : crop.soilItem();
               }
               Item fruit = crop.fruitItem();
               return fruit != Items.AIR ? fruit : crop.seed();
            }
            if (crop.isVineStack()) {
               // 普通葡萄 PLANT 阶段：缺茎 / 有杂物 → 藤茎材料；茎堆齐了但有未播种的顶茎 → 种子；
               // 全部已播种 → 葡萄成品。
               if (anyStemStackToDo(level, crop, plot, data.boxPos())) {
                  return supportItem(crop);
               }
               if (anyTopStemUnseeded(level, crop, plot, data.boxPos())) {
                  return crop.seed() != Items.AIR ? crop.seed() : crop.soilItem();
               }
               Item fruit = crop.fruitItem();
               return fruit != Items.AIR ? fruit : crop.seed();
            }
            if (!crop.isTrellis()) {
               return crop.seed() != Items.AIR ? crop.seed() : crop.soilItem();
            }
            // PLANT 阶段按 plot 整体阶段显示：
            //   - 框架未齐 → 等葡萄藤杆/格架（supportItem）
            //   - 框架齐了，但 plot 内还有 hang 位空 → 等对应种子
            //   - 全部 hang 都在长（age<max），还没熟 → 等成熟（用葡萄成品做提示物品）
            // 同一格（boxPos）永远不参与「缺材料」判断，由 NSUK 自己决定如何播报。
            if (hasFrameWork(level, crop, plot, data.boxPos())) {
               return supportItem(crop);
            }
            if (anyHangEmpty(level, crop, plot, data.boxPos())) {
               return crop.seed() != Items.AIR ? crop.seed() : crop.soilItem();
            }
            if (allHangGrowing(level, crop, plot, data.boxPos())) {
               Item fruit = crop.fruitItem();
               return fruit != Items.AIR ? fruit : crop.seed();
            }
            return crop.seed() != Items.AIR ? crop.seed() : crop.soilItem();
         } else {
            if (crop.isLattice()) {
               return latticeStatusItem(level, crop, plot, data.boxPos(), chests);
            }
            if (crop.isVineStack()) {
               return vineStackStatusItem(level, crop, plot, data.boxPos(), chests);
            }
            return crop.isTrellis()
               ? statusItem(level, crop, data)
               : soilCropStatusItem(level, crop, plot, data.boxPos(), chests);
         }
      }
   }

   /**
    * 一个 cell 上所有 hang 位都「要么空（=firstEmptyVine != null）、要么已熟」。
    * 空 hang 算「等种子」分支；其余 hang 至少有一串是「在长（age<max）」就归为「等成熟」。
    * 实际逻辑：第一层是空 → false（=还在等种子）；否则全部 max age → true；中间混合 → false。
    */
   private static boolean allHangMatureOrAbsent(ServerLevel level, ResolvedCrop crop, BlockPos fruitPos) {
      boolean hasGrowing = false;
      for (BlockPos hang : hangPositions(fruitPos)) {
         if (!level.isLoaded(hang)) {
            return false;
         }
         BlockState state = level.getBlockState(hang);
         if (crop.isFruit(state)) {
            if (CropAge.isMaxAge(state)) {
               // 熟了，至少这串算「就绪」
               continue;
            }
            hasGrowing = true;
         }
      }
      return hasGrowing;
   }

   /** 在指定 cell 报「等框架（柱或梁）」要用的物品：角格报柱子、非角格报横梁，两种都是 supportItem。 */
   private static Item frameWorkShortage(BlockPos fruitPos, List<BlockPos> chests, ServerLevel level, ResolvedCrop crop) {
      return supportItem(crop);
   }

   /**
    * plot 内（除 boxPos 之外）任何非角格 cell 是否有空 hang 位。
    * 配合 {@link #statusItemForPhase} 的「等种子」判定：哪怕只有 1 串还没挂出来都算。
    */
   private static boolean anyHangEmpty(ServerLevel level, ResolvedCrop crop, PlotView plot, BlockPos boxPos) {
      int cells = Math.max(1, plot.cellCount());
      for (int index = 0; index < cells; index++) {
         BlockPos fruitPos = plot.cellAt(index);
         if (fruitPos.getX() == boxPos.getX() && fruitPos.getZ() == boxPos.getZ()) {
            continue;
         }
         if (!level.isLoaded(fruitPos) || isCorner(plot, fruitPos)) {
            continue;
         }
         if (firstEmptyVine(level, crop, fruitPos) != null) {
            return true;
         }
      }
      return false;
   }

   /**
    * plot 内（除 boxPos 之外）所有非角格 cell 的 hang 位都「在长、没空、也没熟」。
    * 用于「等成熟」阶段判定：玩家看到 NPC 头顶的「葡萄」即代表这一阶段。
    */
   private static boolean allHangGrowing(ServerLevel level, ResolvedCrop crop, PlotView plot, BlockPos boxPos) {
      int cells = Math.max(1, plot.cellCount());
      boolean hasAnyHang = false;
      for (int index = 0; index < cells; index++) {
         BlockPos fruitPos = plot.cellAt(index);
         if (fruitPos.getX() == boxPos.getX() && fruitPos.getZ() == boxPos.getZ()) {
            continue;
         }
         if (!level.isLoaded(fruitPos) || isCorner(plot, fruitPos)) {
            continue;
         }
         // 这一格没空 hang 才考虑「在长」
         if (firstEmptyVine(level, crop, fruitPos) != null) {
            return false;
         }
         for (BlockPos hang : hangPositions(fruitPos)) {
            if (!level.isLoaded(hang)) {
               continue;
            }
            BlockState state = level.getBlockState(hang);
            if (crop.isFruit(state)) {
               hasAnyHang = true;
               if (CropAge.isMaxAge(state)) {
                  // 至少一串已熟 → 切到 harvest 阶段
                  return false;
               }
            }
         }
      }
      return hasAnyHang;
   }

   /** 这片田里还有没有任何一根立柱 / 一条横梁没铺好。 */
   private static boolean hasFrameWork(ServerLevel level, ResolvedCrop crop, PlotView plot, BlockPos boxPos) {
      int cells = Math.max(1, plot.cellCount());

      for (int index = 0; index < cells; index++) {
         BlockPos cell = plot.cellAt(index);
         if ((cell.getX() != boxPos.getX() || cell.getZ() != boxPos.getZ()) && level.isLoaded(cell)) {
            if (isCorner(cell)) {
               if (missingPost(level, crop, cell) != null) {
                  return true;
               }
            } else if (missingBeam(level, crop, cell) != null) {
               return true;
            }
         }
      }

      return false;
   }

   /**
    * 自定义土壤作物（末影薄荷 / 灵魂辣椒 / 迷毒果 / 回响作物）该报什么。
    *
    * <p>优先级固定：<b>先土壤、后种子</b>。箱子里一种可用的土壤都没有时先报土壤方块
    * （显示成「等待中：灵魂沙」），土壤齐了才轮到种子（「等待种子：红辣椒」）。
    * 这样农民头上的提示不会一会儿缺土、一会儿缺种子地来回跳。</p>
    */
   private static Item soilCropStatusItem(ServerLevel level, ResolvedCrop crop, PlotView plot, BlockPos boxPos, List<BlockPos> chests) {
      Item seed = crop.seed();
      boolean needsSoil = false;
      int cells = Math.max(1, plot.cellCount());

      for (int index = 0; index < cells; index++) {
         BlockPos cell = plot.cellAt(index);
         if ((cell.getX() != boxPos.getX() || cell.getZ() != boxPos.getZ()) && level.isLoaded(cell) && SoilCropWork.needsTill(level, crop, cell)) {
            needsSoil = true;
            break;
         }
      }

      if (needsSoil && !hasAnySoilItem(level, chests, crop)) {
         return crop.soilItem();
      } else if (seed != Items.AIR && !WorkContainers.has(level, chests, seed)) {
         return seed;
      } else {
         return seed != Items.AIR ? seed : crop.soilItem();
      }
   }

   /** 箱子里有没有任意一种本作物可用的土壤材料。 */
   private static boolean hasAnySoilItem(ServerLevel level, List<BlockPos> chests, ResolvedCrop crop) {
      for (Item item : crop.soilItems()) {
         if (item != null && item != Items.AIR && WorkContainers.has(level, chests, item)) {
            return true;
         }
      }

      return false;
   }

   /**
    * 箱子里现成可用的土壤材料：按 {@link ResolvedCrop#soilItems()} 的顺序取第一个箱子里有的。
    * 一个都没有时退回作物定义里的首选材料（让状态栏能显示“缺什么”），而不是直接判为没活干。
    */
   private static Item availableSoilItem(ServerLevel level, List<BlockPos> chests, ResolvedCrop crop) {
      if (level != null && chests != null && crop != null) {
         for (Item item : crop.soilItems()) {
            if (item != null && item != Items.AIR && WorkContainers.has(level, chests, item)) {
               return item;
            }
         }
      }

      return crop != null ? crop.soilItem() : Items.AIR;
   }

   /**
    * 需不需要铺土壤（把作物格下方换成本作物的土壤方块）。
    *
    * <p>四角例外只属于藤架布局：角落那一列只立蜡封立柱、不结葡萄藤，土壤铺了也永远没有东西长在上面。
    * 木格架 / 藤茎堆布局里每个格子（含角落）都是完整的一株，土壤必须铺。</p>
    *
    * <p>水槽行必须先排除（与宿主 {@code needsTillWork} 第一步同口径）：水槽里是水，既不是本作物的
    * 土壤、又 {@code canBeReplaced()}，不排除就会被永久判成「该铺土」；而水槽行本来就不种东西、
    * 箱子里也不会备泥土，于是 {@link #applyTill} 每次都拿不到材料而失败。更要命的是 TILL 阶段的
    * 扫描窗口正好覆盖整片田，只要还有一格被判成要铺土，TILL 就永远返回一个目标，
    * 后面的 PLANT 阶段一次都轮不到——农夫站在水槽边一动不动，一根藤杆都放不下去。</p>
    */
   public static boolean needsTill(ServerLevel level, ResolvedCrop crop, BlockPos fruitPos) {
      if (!ready(level, crop, fruitPos) || crop.isTrellis() && isCorner(fruitPos)) {
         return false;
      } else if (isWaterTroughCell(fruitPos)) {
         return false;
      } else {
         BlockState soil = level.getBlockState(fruitPos.below());
         // 被排除的方块（例如岩浆块）视同需要换掉：农民会把它们替换成本作物真正可用的土壤，
         // 免得站在上面被烫死。
         return matchesSoil(crop, soil)
            ? false
            : soil.isAir() || soil.canBeReplaced() || isLooseSoil(soil) || crop.isExcludedSoil(soil);
      }
   }

   public static boolean applyTill(ServerLevel level, ResolvedCrop crop, List<BlockPos> chests, BlockPos fruitPos) {
      if (!needsTill(level, crop, fruitPos)) {
         return false;
      } else {
         Item soilItem = availableSoilItem(level, chests, crop);
         if (soilItem instanceof BlockItem blockItem && WorkContainers.consume(level, chests, soilItem)) {
            BlockPos soilPos = fruitPos.below();
            BlockState previous = level.getBlockState(soilPos);
            if (!previous.isAir()) {
               List<ItemStack> drops = Block.getDrops(previous, level, soilPos, level.getBlockEntity(soilPos));
               level.setBlock(soilPos, Blocks.AIR.defaultBlockState(), 3);
               WorkContainers.deposit(level, chests, drops, soilPos);
            }

            level.setBlock(soilPos, blockItem.getBlock().defaultBlockState(), 3);
            return true;
         }

         return false;
      }
   }

   public static boolean needsPlant(ServerLevel level, ResolvedCrop crop, BlockPos fruitPos) {
      if (!ready(level, crop, fruitPos)) {
         return false;
      } else if (isWaterTroughCell(fruitPos)) {
         // 水槽行不种东西（藤茎堆 / 木格架靠土壤不匹配自然跳过，老藤架的立柱分支要显式排除）。
         return false;
      } else if (crop.isLattice()) {
         if (isLatticeAisle(fruitPos)) {
            // 过道行只留给农夫走动：不铺新格架，已有的也不动（拆玩家的东西太粗暴）
            return false;
         }
         return needsLatticePlant(level, crop, fruitPos);
      } else if (crop.isVineStack()) {
         return needsVineStackPlant(level, crop, fruitPos);
      } else if (isCorner(fruitPos)) {
         return missingPost(level, crop, fruitPos) != null;
      } else if (lowestClutter(level, crop, fruitPos) != null) {
         return true;
      } else if (!matchesSoil(crop, level.getBlockState(fruitPos.below()))) {
         return false;
      } else {
         BlockState fruit = level.getBlockState(fruitPos);
         return !fruit.isAir() && !fruit.canBeReplaced() ? false : beamMissing(level, crop, fruitPos) || firstEmptyVine(level, crop, fruitPos) != null;
      }
   }

   /**
    * 执行一次「建造」动作。按布局分发：
    * <ul>
    *   <li>藤架（老布局）：角落立蜡封立柱、清残留、搭横梁、插葡萄藤，整片田框架没铺齐时优先补框架；</li>
    *   <li>木格架（丛林葡萄）：清杂物 → 放木格架 → 原生 {@code useItemOn} 播种；</li>
    *   <li>藤茎堆（普通葡萄）：清杂物 → 先底茎后顶茎 → 原生 {@code useItemOn} 播种。</li>
    * </ul>
    *
    * @return 是否真的做了事（放置了方块、清掉杂物、播种成功）。
    *         返回 false 只代表这一次没能推进——通常是箱子里的材料不够，
    *         调用方据此报「等材料」，不要再用「还缺不缺下一步」来判断，
    *         否则放下横梁后（下一步才插藤）会被误报成"缺葡萄藤"。
    */
   public static boolean applyPlant(ServerLevel level, ResolvedCrop crop, List<BlockPos> chests, BlockPos fruitPos) {
      if (!needsPlant(level, crop, fruitPos)) {
         return false;
      } else if (crop.isLattice()) {
         if (isLatticeAisle(fruitPos)) {
            return false;
         }
         return applyLatticePlant(level, crop, chests, fruitPos);
      } else if (crop.isVineStack()) {
         return applyVineStackPlant(level, crop, chests, fruitPos);
      } else if (isCorner(fruitPos)) {
         return placeSupport(level, crop, chests, fruitPos, missingPost(level, crop, fruitPos), true);
      } else {
         // 整片田还缺任何一根立柱 / 一条横梁 → 这一轮就去补框架，放弃种藤。
         // 避免了「这格框架齐了先种了藤，下一轮才回去补其它 cell 的柱子」这种抢跑。
         BoxView boxData = Boxes.current().orElse(null);
         if (boxData != null && boxData.plot() != null && hasFrameWork(level, crop, boxData.plot(), boxData.boxPos())) {
            return false;
         }
         BlockPos clutter = lowestClutter(level, crop, fruitPos);
         if (clutter != null) {
            breakStructure(level, chests, clutter);
            return true;
         } else {
            BlockPos missingBeam = missingBeam(level, crop, fruitPos);
            if (missingBeam != null) {
               return placeSupport(level, crop, chests, fruitPos, missingBeam, false);
            } else {
               BlockPos bare = firstEmptyVine(level, crop, fruitPos);
               if (bare == null) {
                  return false;
               } else if (!WorkContainers.consume(level, chests, crop.seed())) {
                  return false;
               } else {
                  BlockState above = level.getBlockState(bare);
                  BlockState vine = crop.plantBlock().defaultBlockState();
                  if (above.hasProperty(BlockStateProperties.WATERLOGGED) && vine.hasProperty(BlockStateProperties.WATERLOGGED)) {
                     vine = (BlockState)vine.setValue(BlockStateProperties.WATERLOGGED, (Boolean)above.getValue(BlockStateProperties.WATERLOGGED));
                  }

                  if (crop.plantMature()) {
                     // 个别模组的葡萄藤必须直接种成成熟状态（次元酒窖的绯红/诡异葡萄：
                     // 它们的随机生长要求正下方是菌岩，而这里正下方要留给果实）。
                     vine = CropAge.resetToMaxAge(vine);
                  }

                  level.setBlock(bare, vine, 3);
                  refreshShape(level, bare);
                  return true;
               }
            }
         }
      }
   }

   public static boolean needsBonemeal(ServerLevel level, ResolvedCrop crop, BlockPos fruitPos) {
      if (!ready(level, crop, fruitPos)) {
         return false;
      } else if (crop.isLattice()) {
         // 木格架：age>0 且未熟即可催熟（原版 isValidBonemealTarget）。
         BlockState state = level.getBlockState(fruitPos);
         return crop.matchesLattice(state) && canBonemeal(level, fruitPos, state);
      } else if (crop.isVineStack()) {
         // 藤茎堆：只有顶茎能催熟（原版要求下方是同类茎）。
         BlockPos top = fruitPos.above(1);
         if (!level.isLoaded(top)) {
            return false;
         }
         BlockState state = level.getBlockState(top);
         return crop.isVine(state) && canBonemeal(level, top, state);
      } else if (isCorner(fruitPos)) {
         return false;
      } else {
         BlockPos hang = firstHangPos(level, crop, fruitPos);
         if (hang != null) {
            BlockState fruit = level.getBlockState(hang);
            if (crop.isFruit(fruit)) {
               return !CropAge.isMaxAge(fruit) && canBonemeal(level, hang, fruit);
            } else if (fruit.isAir() || fruit.canBeReplaced()) {
               if (!beamMissing(level, crop, fruitPos)) {
                  BlockPos supportPos = beamPos(fruitPos);
                  BlockState above = level.getBlockState(supportPos);
                  return crop.isVine(above) && canBonemeal(level, supportPos, above);
               }
            }
         }
         return false;
      }
   }

   public static boolean applyBonemeal(ServerLevel level, ResolvedCrop crop, BlockPos fruitPos) {
      if (!needsBonemeal(level, crop, fruitPos)) {
         return false;
      } else if (crop.isLattice()) {
         return bonemeal(level, fruitPos, level.getBlockState(fruitPos));
      } else if (crop.isVineStack()) {
         BlockPos top = stemColumnTop(level, crop, fruitPos);
         return top != null && bonemeal(level, top, level.getBlockState(top));
      } else {
         BlockPos hang = firstHangPos(level, crop, fruitPos);
         if (hang != null) {
            BlockState fruit = level.getBlockState(hang);
            if (crop.isFruit(fruit)) {
               return bonemeal(level, hang, fruit);
            } else {
               BlockPos supportPos = beamPos(fruitPos);
               return bonemeal(level, supportPos, level.getBlockState(supportPos));
            }
         }
         return false;
      }
   }

   public static boolean isReadyToHarvest(ServerLevel level, ResolvedCrop crop, BlockPos fruitPos) {
      if (!ready(level, crop, fruitPos)) {
         return false;
      } else if (crop.isLattice()) {
         // 木格架 age>2 即可空手收割（原版 LatticeBlock.useItemOn 分支）。
         BlockState state = level.getBlockState(fruitPos);
         return crop.matchesLattice(state) && CropAge.ageOf(state) >= LATTICE_HARVEST_AGE;
      } else if (crop.isVineStack()) {
         // 藤茎堆 age>3 才能空手收割（原版 StemBlock.useWithoutItem 分支）。
         BlockPos top = stemColumnTop(level, crop, fruitPos);
         if (top == null) {
            return false;
         }
         BlockState state = level.getBlockState(top);
         // grape=none 的藤茎（老存档用普通耕地方案种下的）掉了也是空手，交给 PLANT 阶段补品种
         return crop.isVine(state)
                 && CropAge.ageOf(state) >= VINE_STACK_HARVEST_AGE
                 && grapeTypeMatches(crop, state);
      } else if (isCorner(fruitPos)) {
         return false;
      } else {
         // 任意一层 hangPos 出现 mature fruit 就算可收，让农夫从最底层往上逐串剪。
         for (BlockPos hang : hangPositions(fruitPos)) {
            if (level.isLoaded(hang) && crop.isMatureFull(level.getBlockState(hang))) {
               return true;
            }
         }
         return false;
      }
   }

   /**
    * 成熟的收割。
    *
    * <p>木格架 / 藤茎堆走原版「空手右键」：掉落物、AGE 复位、方块实体同步全部由原版逻辑完成，
    * 不消耗种子也不破坏结构，收割后下一轮继续生长（可持续收割）。</p>
    */
   public static void harvest(ServerLevel level, ResolvedCrop crop, List<BlockPos> chests, BlockPos fruitPos) {
      if (!isReadyToHarvest(level, crop, fruitPos)) {
         return;
      }

      if (crop.isLattice()) {
         CropHarvest.rightClickHarvestEmptyHand(level, chests, fruitPos);
         return;
      }

      if (crop.isVineStack()) {
         BlockPos top = stemColumnTop(level, crop, fruitPos);
         if (top != null) {
            CropHarvest.rightClickHarvestEmptyHand(level, chests, top);
         }
         return;
      }

      // 从最底层往上逐串收获：第一串复位后催熟 → 第二串可见 / 可收 → 继续。
      for (BlockPos hang : hangPositions(fruitPos)) {
         if (!level.isLoaded(hang)) {
            continue;
         }
         BlockState mature = level.getBlockState(hang);
         if (!crop.isFruit(mature) || !CropAge.isMaxAge(mature)) {
            continue;
         }

         List<ItemStack> drops = new ArrayList<>();
         if (!CropHarvest.rightClickHarvest(level, chests, hang, new ItemStack(Items.SHEARS))) {
            drops.addAll(handPickedDrops(level, crop));
         }

         BlockState reset = resetFruit(crop, mature);
         BlockState current = level.getBlockState(hang);
         if (reset != null && (current.isAir() || current.canBeReplaced() || crop.isFruit(current))) {
            level.setBlock(hang, reset, 3);
         }

         if (!drops.isEmpty()) {
            WorkContainers.deposit(level, chests, drops, hang);
         }
      }
   }

   /**
    * 本体掉落表：3 颗葡萄 + 30% 概率 1~2 颗绿葡萄，与酒馆「剪刀右键」的结果保持一致。
    * 只有在右键收割没能产出任何掉落物时才会用到。
    */
   private static List<ItemStack> handPickedDrops(ServerLevel level, ResolvedCrop crop) {
      Item fruit = crop.fruitItem();
      if (fruit == Items.AIR) {
         return List.of();
      } else {
         List<ItemStack> drops = new ArrayList<>();
         drops.add(new ItemStack(fruit, 3));
         Item green = (Item)BuiltInRegistries.ITEM.get(GREEN_GRAPE);
         // 30% 绿葡萄是酒馆绿葡萄自己的规则，别套到别的模组的葡萄上
         if (green != null && green != Items.AIR && fruit == green && level.getRandom().nextFloat() < 0.3F) {
            drops.add(new ItemStack(green, 1 + level.getRandom().nextInt(2)));
         }

         return drops;
      }
   }

   /**
    * 把成熟果实换算成 0% 生长状态的状态：优先在现有状态上把 age 归零，
    * 保不住（例如右键已经把方块拿走了）时退回果实方块的默认状态。
    */
   private static BlockState resetFruit(ResolvedCrop crop, BlockState mature) {
      BlockState reset = crop.isFruit(mature) ? CropAge.resetToMinAge(mature) : null;
      if (reset == null || !crop.isFruit(reset)) {
         Block fruitBlock = crop.fruitBlock();
         reset = fruitBlock == null || fruitBlock == Blocks.AIR ? null : fruitBlock.defaultBlockState();
      }

      return reset;
   }

   private static boolean ready(ServerLevel level, ResolvedCrop crop, BlockPos fruitPos) {
      if (level == null || crop == null || fruitPos == null || !level.isLoaded(fruitPos) || crop.isMissing()) {
         return false;
      }
      return crop.isTrellisWork();
   }

   private static boolean matchesSoil(ResolvedCrop crop, BlockState soil) {
      return crop.matchesSoil(soil);
   }

   /** 水槽行判定统一走 {@link SoilCropWork#isWaterTroughCell}（与宿主同口径）。 */
   private static boolean isWaterTroughCell(BlockPos cropPos) {
      return SoilCropWork.isWaterTroughCell(cropPos);
   }

   /** 水槽行判定（按地块坐标，不依赖线程上下文）。 */
   public static boolean isWaterTroughCell(PlotView plot, BlockPos cell) {
      return plot != null && cell != null && Math.floorMod(cell.getZ() - plot.min().getZ(), 4) == 3;
   }

   /** 木格架的过道行（按地块坐标，不依赖线程上下文）。 */
   public static boolean isLatticeAisle(PlotView plot, BlockPos cell) {
      return plot != null && cell != null && Math.floorMod(cell.getZ() - plot.min().getZ(), 2) == 1;
   }

   /**
    * 木格架布局的「过道行」：每隔一行留空，供农夫走动。
    *
    * <p>木格架是带薄碰撞的方块，整片格子都放满以后农夫根本进不去田里，
    * 只能贴外围绕圈，内部格子永远够不着——表现为头顶一直「等待格架」却怎么都铺不完。
    * 隔行留过道后，每一排格架都紧挨一条可走的空行，农夫从过道就能照顾到两侧。</p>
    */
   /**
    * 木格架朝向：板面沿田块长边排列（长边沿 X 就朝南北，沿 Z 就朝东西），
    * 这样一整排格架连成面，配合隔行过道，农夫可以在空行里走动、够到两侧格架。
    */
   private static BlockState orientLattice(BlockState state) {
      if (state == null || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
         return state;
      }
      PlotView plot = Boxes.current().map(BoxView::plot).orElse(null);
      boolean alongX = plot == null || plot.width() >= plot.depth();
      return state.setValue(BlockStateProperties.HORIZONTAL_FACING, alongX ? Direction.NORTH : Direction.EAST);
   }

   private static boolean isLatticeAisle(BlockPos cell) {
      if (cell == null) {
         return false;
      }
      PlotView plot = Boxes.current().map(BoxView::plot).orElse(null);
      return plot != null && Math.floorMod(cell.getZ() - plot.min().getZ(), 2) == 1;
   }

   private static boolean isLooseSoil(BlockState soil) {
      return soil.is(Blocks.GRASS_BLOCK)
         || soil.is(Blocks.DIRT)
         || soil.is(Blocks.COARSE_DIRT)
         || soil.is(Blocks.PODZOL)
         || soil.is(Blocks.ROOTED_DIRT)
         || soil.is(Blocks.FARMLAND)
         || soil.is(Blocks.DIRT_PATH);
   }

   // =====================================================================
   // 丛林葡萄（vinery jungle_grape_seeds_*）——「一个种子对应一个木格架」
   //
   // 布局（CropLayout.LATTICE）：
   //   cell-1 土壤 · cell 任意木质木格架（vinery:lattice 标签内 10 种变体）。
   // 种子经原版 useItemOn 直接种在格架上（age==0 → withAge(1, type) + 方块实体同步）；
   // 生长走原版 randomTick（98% 概率、无光照要求）；age>2 空手右键收割（掉葡萄 + AGE 复位 1）；
   // 原版没有机械蔓延，视觉上的「蔓延」就是格架间 getConnection 连线 + 方块实体藤叶渲染。
   // =====================================================================

   /**
    * 诊断：把木格架田每格的判定结果压成一行文字，供 {@code /letsdocropaddons diag} 输出。
    *
    * <p>用来区分“真的缺格架”和“格子被过道/水槽规则跳过了”，不必再靠猜。</p>
    */
   public static String diagnoseLattice(ServerLevel level, ResolvedCrop crop, PlotView plot, BlockPos boxPos, List<BlockPos> chests) {
      int total = 0;
      int trough = 0;
      int aisle = 0;
      int placed = 0;
      int missing = 0;
      int clutter = 0;
      int unseeded = 0;
      int growing = 0;
      int ready = 0;
      int soilMismatch = 0;
      String soilSample = "";
      StringBuilder missingAt = new StringBuilder();
      int cells = Math.max(1, plot.cellCount());
      for (int index = 0; index < cells; index++) {
         BlockPos cell = plot.cellAt(index);
         if (cell.getX() == boxPos.getX() && cell.getZ() == boxPos.getZ()) {
            continue;
         }
         total++;
         if (isWaterTroughCell(plot, cell)) {
            trough++;
            continue;
         }
         if (isLatticeAisle(plot, cell)) {
            aisle++;
            continue;
         }
         BlockState state = level.getBlockState(cell);
         if (crop.matchesLattice(state)) {
            placed++;
            int age = CropAge.ageOf(state);
            if (age == 0) {
               unseeded++;
            } else if (age >= LATTICE_HARVEST_AGE) {
               ready++;
            } else {
               growing++;
            }
            continue;
         }
         if (!state.isAir() && !state.canBeReplaced()) {
            clutter++;
            continue;
         }
         if (!matchesSoil(crop, level.getBlockState(cell.below()))) {
            soilMismatch++;
            if (soilSample.isEmpty()) {
               BlockPos soilPos = cell.below();
               soilSample = soilPos.toShortString() + "="
                       + BuiltInRegistries.BLOCK.getKey(level.getBlockState(soilPos).getBlock());
            }
            continue;
         }
         missing++;
         if (missingAt.length() < 150) {
            missingAt.append('(').append(cell.getX()).append(',').append(cell.getZ()).append(')');
         }
      }
      boolean hasLatticeItem = false;
      for (Item item : crop.plantTagItems()) {
         if (item != Items.AIR && WorkContainers.has(level, chests, item)) {
            hasLatticeItem = true;
            break;
         }
      }
      boolean hasSeed = WorkContainers.has(level, chests, crop.seed());
      return "作物=" + crop.id() + " 布局=" + (crop.isLattice() ? "lattice" : crop.isVineStack() ? "vine_stack" : "trellis")
              + " | 田格=" + total + " 水槽=" + trough + " 过道=" + aisle
              + " | 已放架=" + placed + "(未播" + unseeded + "/生长" + growing + "/可收" + ready + ")"
              + " 缺架=" + missing + missingAt + " 杂物=" + clutter
              + " 土跳过=" + soilMismatch + (soilSample.isEmpty() ? "" : "@" + soilSample)
              + " | 箱内格架=" + hasLatticeItem + " 箱内种子=" + hasSeed;
   }

   /**
    * 丛林葡萄的状态文字，4 阶段（按 plot 整体判断，避免文字乱跳）：
    * <ol>
    *   <li>任意 cell 还缺格架 / 有杂物 → 「先等待格架」→ 箱子里有的任意木质格架物品</li>
    *   <li>格架齐了，但仍有 age==0 的未播种格架 → 「等待对应种子」</li>
    *   <li>全部已播种、都在长（age&lt;3） → 「等待成熟 xxxx」→ 葡萄成品</li>
    *   <li>至少一格 age&gt;=3 → 葡萄成品（HARVEST 阶段接管）</li>
    * </ol>
    */
   private static Item latticeStatusItem(ServerLevel level, ResolvedCrop crop, PlotView plot, BlockPos boxPos, List<BlockPos> chests) {
      boolean anyFrameToDo = anyLatticeToDo(level, crop, plot, boxPos);
      boolean anyUnseeded = false;
      boolean anyGrowing = false;
      boolean anyHarvestReady = false;
      if (!anyFrameToDo) {
         int cells = Math.max(1, plot.cellCount());

         for (int index = 0; index < cells; index++) {
            BlockPos cell = plot.cellAt(index);
            if (cell.getX() == boxPos.getX() && cell.getZ() == boxPos.getZ() || !level.isLoaded(cell) || isWaterTroughCell(cell)) {
               continue;
            }
            if (isLatticeAisle(cell)) {
               continue;
            }
            BlockState state = level.getBlockState(cell);
            if (!crop.matchesLattice(state)) {
               continue;
            }
            int age = CropAge.ageOf(state);
            if (age == 0) {
               anyUnseeded = true;
            } else if (age >= LATTICE_HARVEST_AGE) {
               anyHarvestReady = true;
            } else {
               anyGrowing = true;
            }
         }
      }

      if (anyFrameToDo) {
         return availableLatticeItem(level, chests, crop);
      } else if (anyUnseeded) {
         Item seed = crop.seed();
         return seed != Items.AIR ? seed : availableLatticeItem(level, chests, crop);
      } else if (anyHarvestReady) {
         return crop.fruitItem();
      } else if (anyGrowing) {
         Item fruit = crop.fruitItem();
         return fruit != Items.AIR ? fruit : crop.seed();
      } else {
         return crop.fruitItem();
      }
   }

   /**
    * 该 cell 的「格架位」是否还没放木格架；还没放就返回那个位置。
    *
    * <p>木格架即本体：{@code vinery:lattice} 标签覆盖 10 种木质变体（橡木 / 丛林木 / 白桦木……），
    * 任意一种都算齐了。被异物占住的格子不算「缺」（由 {@link #latticeClutter} 清完再放）。</p>
    */
   private static BlockPos missingLattice(ServerLevel level, ResolvedCrop crop, BlockPos cell) {
      if (!level.isLoaded(cell)) {
         return null;
      }
      // 脚下不是本作物的土壤（玩家在这儿铺了石板路 / 台阶）：这一格不属于田。
      // 既不该去铺格架，也不能算"缺格架"——否则它会一直卡着"整片田没铺齐"，
      // 把已经放好的格架全部堵在播种门外（表现为永远"等待格架"、一格都不播种）。
      if (!matchesSoil(crop, level.getBlockState(cell.below()))) {
         return null;
      }
      BlockState state = level.getBlockState(cell);
      if (crop.matchesLattice(state)) {
         return null;
      }
      if (!state.isAir() && !state.canBeReplaced()) {
         return null;
      }
      return cell;
   }

   /**
    * 木格架列内需要清理的「错位方块」：
    * <ul>
    *   <li>cell：不是木格架的实体方块（旧版藤架支撑、挡路的石头）——必须清掉才能放格架；</li>
    *   <li>above(1)：旧版布局的灌木本体（{@code plantBlock}）或错位堆上来的格架。</li>
    * </ul>
    * above(1) 上与作物无关的方块（玩家自己放的东西）不动——它并不影响「种子种在格架上」。
    */
   private static BlockPos latticeClutter(ServerLevel level, ResolvedCrop crop, BlockPos cell) {
      for (int dy = 0; dy <= 1; dy++) {
         BlockPos pos = cell.above(dy);
         if (!level.isLoaded(pos)) {
            return null;
         }
         BlockState state = level.getBlockState(pos);
         if (state.isAir() || state.canBeReplaced()) {
            continue;
         }
         if (dy == 0) {
            // cell 只接受木格架本体
            if (crop.matchesLattice(state)) {
               continue;
            }
            return pos;
         }
         // above(1)：旧灌木 / 错位格架清掉；无关方块留着
         if (crop.isVine(state) || crop.isOwnPlant(state)) {
            return pos;
         }
      }
      return null;
   }

   /** cell 上的格架是否「尚未播种」（age==0）——原版 useItemOn 只接受 age==0 的格架。 */
   private static boolean latticeUnseeded(ServerLevel level, ResolvedCrop crop, BlockPos cell) {
      BlockState state = level.getBlockState(cell);
      return crop.matchesLattice(state) && CropAge.ageOf(state) == 0;
   }

   /** plot 内（除 boxPos 所在列）是否还有 cell 缺格架或有杂物。 */
   private static boolean anyLatticeToDo(ServerLevel level, ResolvedCrop crop, PlotView plot, BlockPos boxPos) {
      int cells = Math.max(1, plot.cellCount());
      for (int index = 0; index < cells; index++) {
         BlockPos cell = plot.cellAt(index);
         if (cell.getX() == boxPos.getX() && cell.getZ() == boxPos.getZ() || !level.isLoaded(cell) || isWaterTroughCell(cell)) {
            continue;
         }
         if (isLatticeAisle(cell)) {
            continue;
         }
         if (missingLattice(level, crop, cell) != null || latticeClutter(level, crop, cell) != null) {
            return true;
         }
      }
      return false;
   }

   /** plot 内（除 boxPos 所在列）是否还有 age==0 的未播种格架。 */
   private static boolean anyLatticeUnseeded(ServerLevel level, ResolvedCrop crop, PlotView plot, BlockPos boxPos) {
      int cells = Math.max(1, plot.cellCount());
      for (int index = 0; index < cells; index++) {
         BlockPos cell = plot.cellAt(index);
         if (cell.getX() == boxPos.getX() && cell.getZ() == boxPos.getZ() || !level.isLoaded(cell) || isWaterTroughCell(cell)) {
            continue;
         }
         if (isLatticeAisle(cell)) {
            continue;
         }
         if (latticeUnseeded(level, crop, cell)) {
            return true;
         }
      }
      return false;
   }

   /** 整片田（除 boxPos 所在列）所有可耕 cell 都铺上木格架了？未加载的格子不阻塞。 */
   private static boolean allLatticesPlaced(ServerLevel level, ResolvedCrop crop, BlockPos boxPos) {
      PlotView plot = currentPlot();
      if (plot == null) {
         return true;
      }
      int cells = Math.max(1, plot.cellCount());
      for (int index = 0; index < cells; index++) {
         BlockPos cell = plot.cellAt(index);
         if (cell.getX() == boxPos.getX() && cell.getZ() == boxPos.getZ() || !level.isLoaded(cell) || isWaterTroughCell(cell)) {
            continue;
         }
         if (isLatticeAisle(cell)) {
            continue;
         }
         if (missingLattice(level, crop, cell) != null) {
            return false;
         }
      }
      return true;
   }

   /** 丛林葡萄 PLANT 阶段：清杂物 → 等土壤 → 放格架 → 放满后统一原生播种。 */
   private static boolean needsLatticePlant(ServerLevel level, ResolvedCrop crop, BlockPos cell) {
      if (latticeClutter(level, crop, cell) != null) {
         return true;
      }
      if (!matchesSoil(crop, level.getBlockState(cell.below()))) {
         return false;
      }
      if (missingLattice(level, crop, cell) != null) {
         return true;
      }
      return latticeUnseeded(level, crop, cell);
   }

   private static boolean applyLatticePlant(ServerLevel level, ResolvedCrop crop, List<BlockPos> chests, BlockPos cell) {
      // 1) 清掉列内错位方块（旧版灌木 / 旧格架 / 挡在格架位的异物），掉落进箱。
      BlockPos clutter = latticeClutter(level, crop, cell);
      if (clutter != null) {
         breakStructure(level, chests, clutter);
         return true;
      }

      // 2) 土壤还没换好 → 留给 applyTill 阶段（这里不主动铺，避免覆盖）。
      if (!matchesSoil(crop, level.getBlockState(cell.below()))) {
         return false;
      }

      // 3) 缺格架 → 消耗箱子里任意一种木质格架物品，放在 cell 上。
      BlockPos need = missingLattice(level, crop, cell);
      if (need != null) {
         Item latticeItem = firstAvailableLatticeItem(level, chests, crop);
         if (latticeItem == Items.AIR || !(latticeItem instanceof BlockItem blockItem)) {
            return false;
         }
         if (!WorkContainers.consume(level, chests, latticeItem)) {
            return false;
         }
         BlockState placed = orientLattice(blockItem.getBlock().defaultBlockState());
         level.setBlock(need, withWaterlog(placed, level.getBlockState(need)), 3);
         refreshShape(level, need);
         return true;
      }

      // 4) 整片田的格架还没放满 → 先放满再播种（一个种子对应一个木格架，逐格种，
      //    但「铺格架」是统一动作，避免农夫在还空着的田里跳着种）。
      if (!allLatticesPlaced(level, crop, cell)) {
         return false;
      }

      // 5) 未播种的格架 → 走原版 useItemOn 播种：age / grape 属性 / 方块实体（藤叶渲染）
      //    全部由原版写入，直接 setBlock 会漏掉方块实体同步。
      if (latticeUnseeded(level, crop, cell)) {
         Item seed = crop.seed();
         if (seed == Items.AIR) {
            return false;
         }
         // 先模拟播种，成功了再从箱子里扣种子（原版 shrink 只作用在这个临时栈上）。
         if (CropHarvest.useItemOnAsPlayer(level, cell, new ItemStack(seed))) {
            WorkContainers.consume(level, chests, seed);
            return true;
         }
      }
      return false;
   }

   /** 箱子里现成可用的格架材料：按 {@link ResolvedCrop#plantTagItems()} 顺序取第一个箱子里有的。 */
   private static Item firstAvailableLatticeItem(ServerLevel level, List<BlockPos> chests, ResolvedCrop crop) {
      if (level != null && chests != null && crop != null) {
         for (Item item : crop.plantTagItems()) {
            if (item != null && item != Items.AIR && WorkContainers.has(level, chests, item)) {
               return item;
            }
         }
      }

      return Items.AIR;
   }

   /** 状态栏显示用的格架材料：箱子里有的优先，没有就报作物定义里的首选（supportBlock）。 */
   private static Item availableLatticeItem(ServerLevel level, List<BlockPos> chests, ResolvedCrop crop) {
      Item available = firstAvailableLatticeItem(level, chests, crop);
      return available != Items.AIR ? available : supportItem(crop);
   }

   // =====================================================================
   // 普通葡萄（vinery *_grape_seeds）——「两格藤茎堆」葡萄园
   //
   // 布局（CropLayout.VINE_STACK）：
   //   cell-1 土壤 · cell 底茎（grapevine_stem）· above(1) 顶茎。
   // 种子经原版 useItemOn 点在顶茎上（hasTrunk 由底茎提供，且不触发 LEAVES_PENDING 定时自毁）；
   // 生长走原版 randomTick（98% + 光照>=9 + 下方是茎）；age>3 空手右键收割（掉葡萄 + AGE 复位 2）。
   // =====================================================================

   /**
    * 普通葡萄的状态文字，4 阶段（按 plot 整体判断，避免文字乱跳）：
    * <ol>
    *   <li>任意 cell 还缺茎 / 有杂物 → 「先等待藤茎」→ {@link ResolvedCrop#supportBlock()} 物品</li>
    *   <li>茎堆齐了，但仍有 age==0 的未播种顶茎 → 「等待对应种子」</li>
    *   <li>全部已播种、都在长（age&lt;4） → 「等待成熟 xxxx」→ 葡萄成品</li>
    *   <li>至少一株 age&gt;=4 → 葡萄成品（HARVEST 阶段接管）</li>
    * </ol>
    */
   private static Item vineStackStatusItem(ServerLevel level, ResolvedCrop crop, PlotView plot, BlockPos boxPos, List<BlockPos> chests) {
      boolean anyFrameToDo = anyStemStackToDo(level, crop, plot, boxPos);
      boolean anyUnseeded = false;
      boolean anyGrowing = false;
      boolean anyHarvestReady = false;
      if (!anyFrameToDo) {
         int cells = Math.max(1, plot.cellCount());

         for (int index = 0; index < cells; index++) {
            BlockPos cell = plot.cellAt(index);
            if (cell.getX() == boxPos.getX() && cell.getZ() == boxPos.getZ() || !level.isLoaded(cell) || isWaterTroughCell(cell)) {
               continue;
            }
            BlockPos topPos = stemColumnTop(level, crop, cell);
            if (topPos == null) {
               continue;
            }
            BlockState top = level.getBlockState(topPos);
            if (!crop.isVine(top)) {
               continue;
            }
            int age = CropAge.ageOf(top);
            if (age == 0) {
               anyUnseeded = true;
            } else if (age >= VINE_STACK_HARVEST_AGE) {
               anyHarvestReady = true;
            } else {
               anyGrowing = true;
            }
         }
      }

      if (anyFrameToDo) {
         return supportItem(crop);
      } else if (anyUnseeded) {
         Item seed = crop.seed();
         return seed != Items.AIR ? seed : supportItem(crop);
      } else if (anyHarvestReady) {
         return crop.fruitItem();
      } else if (anyGrowing) {
         Item fruit = crop.fruitItem();
         return fruit != Items.AIR ? fruit : crop.seed();
      } else {
         return crop.fruitItem();
      }
   }

   /**
    * 藤茎堆里第一个还没放茎的位置：底茎（cell）优先，顶茎（above(1)）次之。
    *
    * <p>顺序很重要：顶茎需要下方有茎才能 {@code canSurvive}（原版 tick 会自毁），
    * 也需要下方是茎才能被种子点中（{@code hasTrunk}）。</p>
    */
   private static BlockPos missingStem(ServerLevel level, ResolvedCrop crop, BlockPos cell) {
      for (int dy = 0; dy <= 1; dy++) {
         BlockPos pos = cell.above(dy);
         if (!level.isLoaded(pos)) {
            return null;
         }
         BlockState state = level.getBlockState(pos);
         if (crop.isVine(state)) {
            continue;
         }
         if (!state.isAir() && !state.canBeReplaced()) {
            // 被异物占住 → 交给 vineStackClutter 清完再放
            return null;
         }
         return pos;
      }
      return null;
   }

   /**
    * 藤茎堆列内需要清理的「错位方块」：
    * <ul>
    *   <li>cell / above(1)：挡住茎堆的异物（旧藤架布局的柱、旧灌木、石头）；</li>
    *   <li>above(2) / above(3)：旧藤架布局的残留——本作物的藤 / 果实 / 叶子，以及旧版
    *       {@code kaleidoscope_tavern:trellis} 支撑方块。玩家的其它方块不动。</li>
    * </ul>
    */
   private static BlockPos vineStackClutter(ServerLevel level, ResolvedCrop crop, BlockPos cell) {
      for (int dy = 0; dy <= TRELLIS_HEIGHT; dy++) {
         BlockPos pos = cell.above(dy);
         if (!level.isLoaded(pos)) {
            return null;
         }
         BlockState state = level.getBlockState(pos);
         if (state.isAir() || state.canBeReplaced()) {
            continue;
         }
         if (dy <= 1) {
            // 底 / 顶茎位只接受藤茎本体
            if (crop.isVine(state)) {
               continue;
            }
            return pos;
         }
         // above(2) 及以上：藤茎列本身可以长高，茎本体不算杂物；只清旧布局残留
         if (crop.isVine(state)) {
            continue;
         }
         if (crop.isFruit(state) || isLegacyTrellis(state)) {
            return pos;
         }
      }
      return null;
   }

   /** plot 内（除 boxPos 所在列）是否还有 cell 缺茎或有杂物。 */
   private static boolean anyStemStackToDo(ServerLevel level, ResolvedCrop crop, PlotView plot, BlockPos boxPos) {
      int cells = Math.max(1, plot.cellCount());
      for (int index = 0; index < cells; index++) {
         BlockPos cell = plot.cellAt(index);
         if (cell.getX() == boxPos.getX() && cell.getZ() == boxPos.getZ() || !level.isLoaded(cell) || isWaterTroughCell(cell)) {
            continue;
         }
         if (missingStem(level, crop, cell) != null || vineStackClutter(level, crop, cell) != null) {
            return true;
         }
      }
      return false;
   }

   /** plot 内（除 boxPos 所在列）是否还有 age==0 的未播种顶茎。 */
   private static boolean anyTopStemUnseeded(ServerLevel level, ResolvedCrop crop, PlotView plot, BlockPos boxPos) {
      int cells = Math.max(1, plot.cellCount());
      for (int index = 0; index < cells; index++) {
         BlockPos cell = plot.cellAt(index);
         if (cell.getX() == boxPos.getX() && cell.getZ() == boxPos.getZ() || !level.isLoaded(cell) || isWaterTroughCell(cell)) {
            continue;
         }
         if (missingStem(level, crop, cell) != null) {
            continue;
         }
         BlockState top = level.getBlockState(cell.above(1));
         if (crop.isVine(top) && CropAge.ageOf(top) == 0) {
            return true;
         }
      }
      return false;
   }

   /** 整片田（除 boxPos 所在列）所有可耕 cell 都铺齐两格茎堆了？未加载的格子不阻塞。 */
   private static boolean allStemStacksComplete(ServerLevel level, ResolvedCrop crop, BlockPos boxPos) {
      PlotView plot = currentPlot();
      if (plot == null) {
         return true;
      }
      int cells = Math.max(1, plot.cellCount());
      for (int index = 0; index < cells; index++) {
         BlockPos cell = plot.cellAt(index);
         if (cell.getX() == boxPos.getX() && cell.getZ() == boxPos.getZ() || !level.isLoaded(cell) || isWaterTroughCell(cell)) {
            continue;
         }
         if (missingStem(level, crop, cell) != null) {
            return false;
         }
      }
      return true;
   }

   /**
    * PLANT 阶段是否必须“无视箱里有没有种子”照常扫描。
    *
    * <p>需要无视的只有两类工作：<b>搭结构</b>（藤架立柱/横梁、藤茎堆、木格架 —— 用掉的是支撑材料）
    * 和<b>补品种</b>（老存档里 {@code grape=none} 的藤茎，改属性不需要种子）。
    * 结构齐了又只是“等种子”时返回 {@code false}：宿主会按箱里有没有种子决定跳不跳 PLANT，
    * 否则缺种子的空格会一直占着 PLANT 目标，永远轮不到 HARVEST —— 表现就是“只播种、不收割”。</p>
    */
   public static boolean needsSeedlessPlantWork(ServerLevel level, ResolvedCrop crop) {
      BoxView box = Boxes.current().orElse(null);
      if (level == null || crop == null || box == null || box.plot() == null) {
         return false;
      }
      PlotView plot = box.plot();
      BlockPos boxPos = box.boxPos();
      if (crop.isVineStack()) {
         if (!allStemStacksComplete(level, crop, boxPos)) {
            return true;
         }
         int cells = Math.max(1, plot.cellCount());
         for (int index = 0; index < cells; index++) {
            BlockPos cell = plot.cellAt(index);
            if (cell.getX() == boxPos.getX() && cell.getZ() == boxPos.getZ()
                    || !level.isLoaded(cell) || isWaterTroughCell(cell)) {
               continue;
            }
            BlockPos top = stemColumnTop(level, crop, cell);
            if (top != null && !grapeTypeMatches(crop, level.getBlockState(top))) {
               return true;
            }
         }
         return false;
      }
      if (crop.isLattice()) {
         return !allLatticesPlaced(level, crop, boxPos);
      }
      return hasFrameWork(level, crop, plot, boxPos);
   }

   /** 藤茎列最高的那一根（玩家手放时原版一次会放两根，列高可能超过两格）。 */
   private static BlockPos stemColumnTop(ServerLevel level, ResolvedCrop crop, BlockPos cell) {
      BlockPos top = null;
      for (int dy = 0; dy <= TRELLIS_HEIGHT + 2; dy++) {
         BlockPos pos = cell.above(dy);
         if (!level.isLoaded(pos) || !crop.isVine(level.getBlockState(pos))) {
            break;
         }
         top = pos.immutable();
      }
      return top;
   }

   /**
    * 这根藤茎的 {@code grape} 属性是不是本作物的品种。
    *
    * <p>只有带品种的藤茎才会掉葡萄；老存档里用「普通耕地」方案种下的藤茎是 {@code grape=none}，
    * 手动右键和雇农收割都收不到东西，必须先按 {@code plant_properties} 把品种补上。</p>
    */
   private static boolean grapeTypeMatches(ResolvedCrop crop, BlockState state) {
      if (crop == null || state == null || crop.plantProperties().isEmpty()) {
         return true;
      }
      return crop.applyPlantPropertiesTo(state) == state;
   }

   /** 普通葡萄 PLANT 阶段：清杂物 → 等土壤 → 先底茎后顶茎 → 铺齐后统一原生播种。 */
   private static boolean needsVineStackPlant(ServerLevel level, ResolvedCrop crop, BlockPos cell) {
      if (vineStackClutter(level, crop, cell) != null) {
         return true;
      }
      if (!matchesSoil(crop, level.getBlockState(cell.below()))) {
         return false;
      }
      if (missingStem(level, crop, cell) != null) {
         return true;
      }
      BlockPos topPos = stemColumnTop(level, crop, cell);
      if (topPos == null) {
         return false;
      }
      BlockState top = level.getBlockState(topPos);
      // 没播种（age==0）或品种不对（老存档 grape=none）都需要处理
      boolean needsSeed = CropAge.ageOf(top) == 0 || !grapeTypeMatches(crop, top);
      // 全田茎堆没铺齐时不报「该播种」：宿主一旦选中这种格子当目标，applyPlant 会被
      // allStemStacksComplete 拦下返回 false，目标却一直存在 —— 农夫够不着就永远卡在这一格，
      // 其它缺顶茎的格子再也轮不到。铺齐后播种目标自然出现。
      return needsSeed && allStemStacksComplete(level, crop, cell);
   }

   private static boolean applyVineStackPlant(ServerLevel level, ResolvedCrop crop, List<BlockPos> chests, BlockPos cell) {
      // 1) 清掉列内杂物（旧藤架残留的柱 / 梁 / 藤 / 叶、挡路的异物），掉落进箱。
      BlockPos clutter = vineStackClutter(level, crop, cell);
      if (clutter != null) {
         breakStructure(level, chests, clutter);
         return true;
      }

      // 2) 土壤还没换好 → 留给 applyTill 阶段。
      if (!matchesSoil(crop, level.getBlockState(cell.below()))) {
         return false;
      }

      // 3) 缺茎 → 消耗藤茎物品（supportBlock 复用为 grapevine_stem），先底后顶。
      BlockPos need = missingStem(level, crop, cell);
      if (need != null) {
         Item stemItem = supportItem(crop);
         if (stemItem == Items.AIR || !(stemItem instanceof BlockItem blockItem)) {
            return false;
         }
         if (!WorkContainers.consume(level, chests, stemItem)) {
            return false;
         }
         level.setBlock(need, withWaterlog(blockItem.getBlock().defaultBlockState(), level.getBlockState(need)), 3);
         refreshShape(level, need);
         return true;
      }

      // 4) 整片田的茎堆还没铺齐 → 先铺齐再播种。
      if (!allStemStacksComplete(level, crop, cell)) {
         return false;
      }

      // 5) 顶茎未播种 → 走原版 useItemOn：age / grape 属性由原版写入，
      //    且顶茎下方是茎 → hasTrunk 成立、不会挂 LEAVES_PENDING 定时自毁。
      BlockPos top = stemColumnTop(level, crop, cell);
      if (top == null) {
         return false;
      }
      BlockState topState = level.getBlockState(top);
      // 老存档的 grape=none 藤茎：先补品种，补完下一轮再播种 / 收割
      if (crop.isVine(topState) && !grapeTypeMatches(crop, topState)) {
         BlockState fixed = crop.applyPlantPropertiesTo(topState);
         if (fixed != topState) {
            level.setBlock(top, fixed, 3);
            return true;
         }
      }
      if (crop.isVine(topState) && CropAge.ageOf(topState) == 0) {
         Item seed = crop.seed();
         if (seed == Items.AIR) {
            return false;
         }
         if (CropHarvest.useItemOnAsPlayer(level, top, new ItemStack(seed))) {
            WorkContainers.consume(level, chests, seed);
            return true;
         }
      }
      return false;
   }

   // =====================================================================
   // 藤架（kaleidoscope_tavern 老布局）专用助手
   // =====================================================================

   /**
    * 一次性补正整座藤架的方块形状，用于修老存档。
    *
    * <p>{@link #refreshShape} 只能保证“之后放下”的方块是对的；存档里已经卡在初始 type 的
    * 角落立柱不会自己恢复（它被 {@link #missingPost} 判定为已建好，农民永远不会再碰它）。
    * 这个方法在箱子被认领/刷新时把它们按当前邻居统一重算一遍，玩家不必拆掉重搭。</p>
    *
    * @return 被补正的方块数量，仅用于日志
    */
   public static int settleFrame(ServerLevel level, ResolvedCrop crop, PlotView plot) {
      if (level == null || crop == null || plot == null || !crop.isTrellis() || crop.isMissing()) {
         return 0;
      } else {
         int settled = 0;
         int cells = Math.max(1, plot.cellCount());

         for (int index = 0; index < cells; index++) {
            BlockPos cell = plot.cellAt(index);
            if (!level.isLoaded(cell)) {
               continue;
            }

            if (isCorner(plot, cell)) {
               for (int dy = 0; dy < TRELLIS_HEIGHT; dy++) {
                  BlockPos pos = cell.above(dy);
                  if (level.isLoaded(pos) && isWaxedPost(crop, level.getBlockState(pos)) && refreshShape(level, pos)) {
                     settled++;
                  }
               }
            } else {
               BlockPos beam = beamPos(cell);
               if (level.isLoaded(beam) && crop.isSupport(level.getBlockState(beam)) && refreshShape(level, beam)) {
                  settled++;
               }
            }
         }

         return settled;
      }
   }

   /**
    * 找出挡在藤架建造路径上、需要先清理掉的“杂物”（残留的藤蔓 / 果实）。
    *
    * <p>三格藤架布局：cell-1 土壤 · cell+0 作物格（应为空）· cell+1 挂果位 · cell+2 横梁（藤蔓长在其上，
    * 果实挂在藤蔓正下方一格）。其中 cell+1 正是本作物果实正常的生长位置，若把它当成杂物，
    * {@link #applyPlant} 会在果实刚结出（age=0）时就用 {@code breakStructure} 把它拆掉；
    * 又因为 NSUK 的工作阶段顺序是 TILL → PLANT → BONEMEAL → HARVEST，PLANT 早于 HARVEST，
    * 果实每次结出都会被立刻清掉，玩家看到的现象就是“葡萄刚长出来 0% 就被农民收掉了”。
    *
    * <p>因此：cell+0 上的藤蔓/果实一律视为杂物（旧版两格布局的残留）；
    * cell+1 上只清理错位的藤蔓，本作物的果实一律放过，等它长到满熟再交给 HARVEST 阶段收割。</p>
    */
   private static BlockPos lowestClutter(ServerLevel level, ResolvedCrop crop, BlockPos fruitPos) {
      for (int dy = 0; dy <= TRELLIS_HEIGHT; dy++) {
         BlockPos pos = fruitPos.above(dy);
         if (!level.isLoaded(pos)) {
            return null;
         }

         BlockState state = level.getBlockState(pos);
         if (dy > 0 && crop.isFruit(state)) {
            continue;
         }

         if (crop.isVine(state) || crop.isFruit(state)) {
            return pos;
         }
      }

      return null;
   }

   private static void breakStructure(ServerLevel level, List<BlockPos> chests, BlockPos pos) {
      BlockState state = level.getBlockState(pos);
      List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos));
      level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
      WorkContainers.deposit(level, chests, drops, pos);
   }

   private static BlockPos missingPost(ServerLevel level, ResolvedCrop crop, BlockPos corner) {
      for (int dy = 0; dy < 3; dy++) {
         BlockPos pos = corner.above(dy);
         if (!level.isLoaded(pos)) {
            return null;
         }

         BlockState state = level.getBlockState(pos);
         if (!isWaxedPost(crop, state)) {
            if (!state.isAir() && !state.canBeReplaced() && !crop.isSupport(state) && !crop.isVine(state) && !crop.isFruit(state)) {
               return null;
            }

            return pos;
         }
      }

      return null;
   }

   private static boolean isWaxedPost(ResolvedCrop crop, BlockState state) {
      return crop.isSupport(state) && booleanValue(state, "waxed");
   }

   private static boolean isCorner(BlockPos pos) {
      BoxView data = Boxes.current().orElse(null);
      return isCorner(data == null ? null : data.plot(), pos);
   }

   private static boolean isCorner(PlotView plot, BlockPos pos) {
      if (plot != null && pos != null) {
         boolean edgeX = pos.getX() == plot.min().getX() || pos.getX() == plot.max().getX();
         boolean edgeZ = pos.getZ() == plot.min().getZ() || pos.getZ() == plot.max().getZ();
         return edgeX && edgeZ;
      } else {
         return false;
      }
   }

   /** 当前正在处理的农田盒（拿不到 plot 时返回 null，调用方据此跳过 plot 级门禁）。 */
   private static PlotView currentPlot() {
      BoxView data = Boxes.current().orElse(null);
      return data == null ? null : data.plot();
   }

   /** 站位搜索顺序：先本格，再四正，再四斜。2 格以外的位置基本超出 2.4 格行动距离，不取。 */
   private static final int[][] STAND_OFFSETS = new int[][]{
      {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
   };
   private static final double ACTION_REACH_SQR = 5.76;

   /**
    * 给农夫找一个「能站住、又在行动距离内够得着这一格」的位置，供宿主的站位计算使用。
    *
    * <p>宿主原本只在作物所在的<b>那一层</b>找脚下有实心方块的位置。藤架是抬高的：
    * 作物格 cell 上方才是果实与藤蔓，cell-1 才是待铺的土壤。土壤还没铺时那一层全是空气，
    * 宿主一格都找不到，于是退回到农田盒周围停车点 —— 农夫就在盒子与田块之间来回跑，
    * 每趟只铺得下一根，头顶状态文字也跟着跳。</p>
    *
    * <p>层序：先作物同层（地面邻位，农夫自己能走过去），再往上（藤架 / 茎堆顶，
    * 密集种植时同层邻位全被邻列藤杆占满、下层又是实心地面，只能站到结构顶上，
    * 由 {@code FarmlandReachMixin} 传送兜底），最后往下（土壤坑 / 地面）。
    * 每一层都校验离作物格不超过 2.4 格，避免「走到了却够不着」导致原地打转。</p>
    *
    * <p>作物格本格（{@code dy == 0} 且偏移 {@code (0,0)}）不站：往脚下放方块会把农夫埋进去。</p>
    *
    * @return 站位坐标；找不到可站之处时返回 null，由调用方退回宿主原逻辑
    */
   public static Vec3 standAnchor(ServerLevel level, BlockPos cropPos) {
      if (level == null || cropPos == null) {
         return null;
      } else {
         int[] dys = new int[]{0, 1, 2, 3, -1, -2, -3};
         for (int dy : dys) {
            for (int[] offset : STAND_OFFSETS) {
               if (dy == 0 && offset[0] == 0 && offset[1] == 0) {
                  continue;
               }
               BlockPos feet = cropPos.offset(offset[0], dy, offset[1]);
               Vec3 anchor = Vec3.atBottomCenterOf(feet);
               if (isStandable(level, feet) && anchor.distanceToSqr(Vec3.atCenterOf(cropPos)) <= ACTION_REACH_SQR) {
                  return anchor;
               }
            }
         }

         return null;
      }
   }

   private static boolean isStandable(ServerLevel level, BlockPos feet) {
      if (!level.isLoaded(feet)) {
         return false;
      } else {
         BlockPos belowPos = feet.below();
         BlockState below = level.getBlockState(belowPos);
         if (below.is(Blocks.FARMLAND) || below.getCollisionShape(level, belowPos).isEmpty()) {
            return false;
         } else {
            return isBodyPassable(level, feet, level.getBlockState(feet))
               && isBodyPassable(level, feet.above(), level.getBlockState(feet.above()));
         }
      }
   }

   private static boolean isBodyPassable(ServerLevel level, BlockPos pos, BlockState state) {
      return state.isAir() || state.canBeReplaced() || state.getCollisionShape(level, pos).isEmpty();
   }

   /**
    * 只找「作物结构顶」的站位（藤架 / 茎堆顶），供 {@code FarmlandReachMixin} 的传送兜底使用。
    *
    * <p>与 {@link #standAnchor} 的区别：不搜作物同层与下层——同层有位说明农夫自己走得过去
    * （交给寻路系统），下层一般是实心地面；密集种植时唯一可站之处就是结构顶，而结构顶
    * 比地面高，原版寻路爬不上去，只能传送。校验同样是「可站 + 距作物格不超过 2.4 格」。</p>
    *
    * @return 结构顶站位；找不到返回 null
    */
   public static Vec3 teleportAnchor(ServerLevel level, BlockPos cropPos) {
      if (level == null || cropPos == null) {
         return null;
      }
      // 先找结构顶（藤架 / 藤茎堆顶）
      for (int dy = 1; dy <= 3; ++dy) {
         for (int[] offset : STAND_OFFSETS) {
            BlockPos feet = cropPos.offset(offset[0], dy, offset[1]);
            Vec3 anchor = Vec3.atBottomCenterOf(feet);
            if (isStandable(level, feet) && anchor.distanceToSqr(Vec3.atCenterOf(cropPos)) <= ACTION_REACH_SQR) {
               return anchor;
            }
         }
      }
      // 木格架这类「没有高度」的布局：同层旁边有空位就站旁边
      for (int[] offset : STAND_OFFSETS) {
         BlockPos feet = cropPos.offset(offset[0], 0, offset[1]);
         Vec3 anchor = Vec3.atBottomCenterOf(feet);
         if (isStandable(level, feet) && anchor.distanceToSqr(Vec3.atCenterOf(cropPos)) <= ACTION_REACH_SQR) {
            return anchor;
         }
      }
      // 最后兜底：直接送到作物格中心。格架 / 藤茎都是薄碰撞，站进去不会被夹死，
      // 而宿主只校验「离目标 ≤2.4 格」，这一格里干活是成立的。
      return Vec3.atBottomCenterOf(cropPos);
   }

   private static BlockPos hangPos(BlockPos fruitPos) {
      return fruitPos.above(1);
   }

   /**
    * 葡萄藤蔓的「果串」挂点：从格子正上方 1 层到 VINE_LAYERS 层。
    * 每层都能挂 1 串葡萄——3 层 cell 顶多同时挂 3 串，1 串熟了收 1 串，复位后催熟下一串，循环。
    * 比「只挂 1 层」产量 ×VINE_LAYERS。
    *
    * <p>按用户「第二层放种子」约定，遍历顺序优先 above(2) → above(1) → above(3)，
    * 让农夫从第二层开始催熟、收获；顶层 above(3) 只在下方两层都被占满时才挂出。</p>
    */
   private static List<BlockPos> hangPositions(BlockPos fruitPos) {
      List<BlockPos> result = new ArrayList<>(VINE_LAYERS);
      int[] order = new int[]{2, 1, 3};
      for (int dy : order) {
         if (dy > VINE_LAYERS) {
            continue;
         }
         result.add(fruitPos.above(dy));
      }
      return result;
   }

   /** 在 hangPositions 里找第一个满足「可挂藤 / 可结果」的格（用于 bonemeal / harvest 定位）。 */
   private static BlockPos firstHangPos(ServerLevel level, ResolvedCrop crop, BlockPos fruitPos) {
      for (BlockPos pos : hangPositions(fruitPos)) {
         if (!level.isLoaded(pos)) {
            return null;
         }
         BlockState state = level.getBlockState(pos);
         if (crop.isFruit(state) || state.isAir() || state.canBeReplaced()) {
            return pos;
         }
      }
      return null;
   }

   private static BlockPos firstEmptyVine(ServerLevel level, ResolvedCrop crop, BlockPos fruitPos) {
      // 顶端 beamPos 永远是最末一层的藤锚点；下面的 hangPos 是果实位。逐层找
      // 「可挂藤、横梁就绪、且没占位」的位置——3 层 cell 共能挂 3 串。
      //
      // 「第二层放种子」约定：按用户需求，果实（葡萄串）优先挂 above(2)。
      // 农夫先按 above(2) → above(1) → above(3) 的顺序占位；顶层 above(3) 必须先有横梁作为藤锚点。
      int top = TRELLIS_HEIGHT; // = 3
      int[] order = new int[]{2, 1, 3};
      for (int dy : order) {
         if (dy > top) {
            continue;
         }
         BlockPos pos = fruitPos.above(dy);
         if (!level.isLoaded(pos)) {
            return null;
         }
         BlockState state = level.getBlockState(pos);
         // 任何本作物的方块（藤/果实/叶子）都已经算"被占了"，跳过这层继续找下一层。
         if (crop.isVine(state) || crop.isFruit(state) || crop.isOwnPlant(state)) {
            continue;
         }
         // 顶层需要横梁（不可上蜡）作为藤锚点；下面 1、2 层只需空中即可。
         if (dy == top) {
            if (crop.isSupport(state) && plantableSupport(state)) {
               return pos;
            }
            return null;
         }
         if (state.isAir() || state.canBeReplaced()) {
            return pos;
         }
         return null;
      }
      return null;
   }

   private static boolean beamMissing(ServerLevel level, ResolvedCrop crop, BlockPos aislePos) {
      return missingBeam(level, crop, aislePos) != null;
   }

   private static BlockPos missingBeam(ServerLevel level, ResolvedCrop crop, BlockPos aislePos) {
      BlockPos pos = beamPos(aislePos);
      if (!level.isLoaded(pos)) {
         return null;
      } else {
         BlockState state = level.getBlockState(pos);
         if (!crop.isSupport(state) && !crop.isVine(state)) {
            return !state.isAir() && !state.canBeReplaced() ? null : pos;
         } else {
            return null;
         }
      }
   }

   private static BlockPos beamPos(BlockPos fruitPos) {
      return fruitPos.above(TRELLIS_HEIGHT);
   }

   /**
    * 放一根立柱或横梁。
    *
    * @return 是否真的放成了（材料够、方块已落地）。材料不够返回 false，调用方据此等待补货。
    */
   private static boolean placeSupport(ServerLevel level, ResolvedCrop crop, List<BlockPos> chests, BlockPos fruitPos, BlockPos pos, boolean post) {
      if (pos == null) {
         return false;
      } else {
         Item supportItem = supportItem(crop);
         if (supportItem != Items.AIR && WorkContainers.consume(level, chests, supportItem)) {
            BlockState previous = level.getBlockState(pos);
            if (!previous.isAir() && previous.canBeReplaced()) {
               level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }

            BlockState placed = placedSupport(level, crop, fruitPos, pos);
            if (post) {
               placed = withFlag(placed, "waxed", true);
            }

            level.setBlock(pos, placed, 3);
            refreshShape(level, pos);
            return true;
         } else {
            return false;
         }
      }
   }

   /**
    * 放下方块之后立刻按当前邻居重算一次它自己的形状。
    *
    * <p>藤架的 {@code type}（立柱 / 东西向横梁 / 十字 / 六向）完全由邻居推导，而
    * {@code setBlock} 只会去通知“邻居”重新计算，方块本身保持放下时的初始状态。
    * 于是当一格的所有邻居都早就放好时（典型的最后建成的那个角落立柱），
    * 它会永远停在放下时的 {@code east_west} —— 表现得就像“角落没有立柱、横梁朝外支棱”，
    * 而不是玩家手动摆放时会收敛出的立柱+四向横梁。</p>
    *
    * <p>这里把原版「邻居变动」触发的 {@code updateShape} 主动补跑一遍，收敛结果与手动摆放一致。</p>
    *
    * @return 形状是否真的发生了改变
    */
   private static boolean refreshShape(ServerLevel level, BlockPos pos) {
      BlockState state = level.getBlockState(pos);
      BlockState updated = state;

      for (Direction direction : Direction.values()) {
         updated = updated.updateShape(direction, level.getBlockState(pos.relative(direction)), level, pos, pos.relative(direction));
      }

      if (updated != state) {
         level.setBlock(pos, updated, 3);
         return true;
      } else {
         return false;
      }
   }

   private static BlockState placedSupport(ServerLevel level, ResolvedCrop crop, BlockPos fruitPos, BlockPos pos) {
      boolean beam = pos.getY() == fruitPos.getY() + TRELLIS_HEIGHT;
      String type = beam ? "east_west" : "single";
      BlockState state = crop.supportBlock().defaultBlockState();

      for (Property<?> property : state.getProperties()) {
         if ("type".equals(property.getName())) {
            state = withValue(state, property, type);
         }
      }

      return state;
   }

   /** 把待放方块的水泡属性对齐到目标位上已有的状态（水上方放干方块不会把水吃掉）。 */
   private static BlockState withWaterlog(BlockState placed, BlockState target) {
      if (target.hasProperty(BlockStateProperties.WATERLOGGED) && placed.hasProperty(BlockStateProperties.WATERLOGGED)) {
         return (BlockState)placed.setValue(BlockStateProperties.WATERLOGGED, (Boolean)target.getValue(BlockStateProperties.WATERLOGGED));
      }
      return placed;
   }

   private static BlockState withFlag(BlockState state, String name, boolean value) {
      for (Property<?> property : state.getProperties()) {
         if (name.equals(property.getName()) && property instanceof BooleanProperty flag) {
            return (BlockState)state.setValue(flag, value);
         }
      }

      return state;
   }

   private static BlockState withValue(BlockState state, Property property, String serialized) {
      for (Object value : property.getPossibleValues()) {
         Comparable comparable = (Comparable)value;
         if (serialized.equals(property.getName(comparable))) {
            return (BlockState)state.setValue(property, comparable);
         }
      }

      return state;
   }

   private static boolean plantableSupport(BlockState state) {
      return !booleanValue(state, "waxed");
   }

   private static boolean canBonemeal(ServerLevel level, BlockPos pos, BlockState state) {
      if (state.getBlock() instanceof BonemealableBlock growable && growable.isValidBonemealTarget(level, pos, state)) {
         return true;
      }

      return false;
   }

   private static boolean bonemeal(ServerLevel level, BlockPos pos, BlockState state) {
      if (state.getBlock() instanceof BonemealableBlock growable && growable.isValidBonemealTarget(level, pos, state)) {
         growable.performBonemeal(level, level.getRandom(), pos, state);
         return true;
      }

      return false;
   }

   private static Item supportItem(ResolvedCrop crop) {
      Block support = crop.supportBlock();
      if (support != null && support != Blocks.AIR) {
         Item item = support.asItem();
         return item == null ? Items.AIR : item;
      } else {
         return Items.AIR;
      }
   }

   /** 旧版藤架布局给 vinery 作物用的支撑方块；迁移到新布局时按杂物回收。 */
   private static boolean isLegacyTrellis(BlockState state) {
      if (legacyTrellis == null) {
         Block block = BuiltInRegistries.BLOCK.get(LEGACY_TRELLIS_ID);
         legacyTrellis = block == null ? Blocks.AIR : block;
      }

      return legacyTrellis != Blocks.AIR && state.is(legacyTrellis);
   }

   private static boolean booleanValue(BlockState state, String name) {
      for (Property<?> property : state.getProperties()) {
         if (name.equals(property.getName()) && property instanceof BooleanProperty flag) {
            return Boolean.TRUE.equals(state.getValue(flag));
         }
      }

      return false;
   }
}