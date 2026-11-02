package com.xiaolianganmuchen.letsdocropaddons.nsukapi;

import com.nsukcropapi.api.CropSpec;
import com.nsukcropapi.api.CropWork;
import com.nsukcropapi.api.CropWorkContext;
import com.nsukcropapi.api.StageOutcome;
import com.nsukcropapi.nsuk.WorkContainers;
import com.xiaolianganmuchen.letsdocropaddons.crop.CropCatalog;
import com.xiaolianganmuchen.letsdocropaddons.crop.CropContext;
import com.xiaolianganmuchen.letsdocropaddons.crop.CropHarvest;
import com.xiaolianganmuchen.letsdocropaddons.crop.ResolvedCrop;
import com.xiaolianganmuchen.letsdocropaddons.crop.SoilCropWork;
import com.xiaolianganmuchen.letsdocropaddons.crop.TrellisCropWork;
import common.cn.kafei.simukraft.farmland.FarmlandBoxData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 把乐事作物附属原先写在 Mixin 里的作业逻辑搬进 NSUK 作物 API 的五阶段钩子。
 *
 * <p>分工与迁移前完全一致：</p>
 * <ul>
 *   <li>藤架（葡萄）：翻地/播种/骨粉/收割全走 {@link TrellisCropWork}</li>
 *   <li>自定义土壤（末影薄荷、灵魂辣椒、回响作物……）：翻地/播种走 {@link SoilCropWork}，收割走 {@link CropHarvest}</li>
 *   <li>普通耕地作物：全部返回 {@code null}，交回宿主原逻辑（行为与迁移前一致）</li>
 * </ul>
 */
public final class LetsDoCropWork implements CropWork {

    private static final String STATUS_MISSING_SEED = "gui.simukraft.farmland.status.missing_seed";
    private static final String STATUS_TILL = "gui.simukraft.farmland.status.till";
    private static final String STATUS_PLANT = "gui.simukraft.farmland.status.plant";

    /** 所有乐事作物共用一个实例：具体作物由 {@link CropSpec#id()} 在调用时解析。 */
    public static final LetsDoCropWork INSTANCE = new LetsDoCropWork();

    private LetsDoCropWork() {
    }

    @Override
    public void onTickStart(CropWorkContext ctx, CropSpec spec) {
        CropContext.push(box(ctx));
    }

    @Override
    public void onTickEnd(CropWorkContext ctx, CropSpec spec) {
        CropContext.pop();
    }

    /** 沿用乐事自己的种植状态（葡萄的 {@code grape=red}、种下即熟等）。 */
    @Override
    public BlockState plantState(CropSpec spec) {
        ResolvedCrop crop = crop(spec);
        return crop == null ? null : crop.plantState();
    }

    /** 沿用乐事自己的成熟判定（多格作物、按收获年龄配置等）。 */
    @Override
    public boolean isMature(CropWorkContext ctx, BlockState state, CropSpec spec) {
        ResolvedCrop crop = crop(spec);
        return crop != null ? crop.isMatureFull(state) : CropWork.super.isMature(ctx, state, spec);
    }

    /** 沿用乐事自己的"是不是本作物的方块"判定（含身体格、藤蔓、果实）。 */
    @Override
    public boolean isOwnPlant(BlockState state, CropSpec spec) {
        ResolvedCrop crop = crop(spec);
        return crop != null ? crop.isOwnPlant(state) : CropWork.super.isOwnPlant(state, spec);
    }

    @Override
    public Boolean needsWater(CropWorkContext ctx, CropSpec spec) {
        ResolvedCrop crop = crop(spec);
        // 自定义土壤作物不喝水槽：继续走宿主的水槽逻辑只会白挖一格
        return crop != null && crop.usesCustomSoil() ? Boolean.FALSE : null;
    }

    @Override
    public Boolean needsTill(CropWorkContext ctx, CropSpec spec) {
        ResolvedCrop crop = crop(spec);
        ServerLevel level = ctx.level();
        BlockPos pos = ctx.cropPos();
        if (crop == null || level == null || pos == null) {
            return null;
        }
        if (crop.isTrellisWork()) {
            return TrellisCropWork.needsTill(level, crop, pos);
        }
        if (crop.usesCustomSoil()) {
            return SoilCropWork.needsTill(level, crop, pos);
        }
        return null;
    }

    @Override
    public StageOutcome applyTill(CropWorkContext ctx, CropSpec spec) {
        ResolvedCrop crop = crop(spec);
        ServerLevel level = ctx.level();
        BlockPos pos = ctx.cropPos();
        if (crop == null || level == null || pos == null) {
            return null;
        }
        boolean trellis = crop.isTrellisWork();
        if (!trellis && !crop.usesCustomSoil()) {
            return null;
        }
        boolean needed = trellis
                ? TrellisCropWork.needsTill(level, crop, pos)
                : SoilCropWork.needsTill(level, crop, pos);
        if (!needed) {
            return StageOutcome.SKIPPED;
        }
        boolean acted = trellis
                ? TrellisCropWork.applyTill(level, crop, ctx.chests(), pos)
                : SoilCropWork.applyTill(level, crop, ctx.chests(), pos);
        return acted ? StageOutcome.PROCESSED : StageOutcome.WAITING_MATERIAL;
    }

    @Override
    public Boolean needsPlant(CropWorkContext ctx, CropSpec spec) {
        ResolvedCrop crop = crop(spec);
        ServerLevel level = ctx.level();
        BlockPos pos = ctx.cropPos();
        if (crop == null || level == null || pos == null) {
            return null;
        }
        if (crop.isTrellisWork()) {
            return TrellisCropWork.needsPlant(level, crop, pos);
        }
        if (crop.usesCustomSoil()) {
            return SoilCropWork.needsPlant(level, crop, pos);
        }
        return null;
    }

    @Override
    public StageOutcome applyPlant(CropWorkContext ctx, CropSpec spec) {
        ResolvedCrop crop = crop(spec);
        ServerLevel level = ctx.level();
        BlockPos pos = ctx.cropPos();
        if (crop == null || level == null || pos == null) {
            return null;
        }
        boolean trellis = crop.isTrellisWork();
        if (!trellis && !crop.usesCustomSoil()) {
            return null;
        }
        boolean needed = trellis
                ? TrellisCropWork.needsPlant(level, crop, pos)
                : SoilCropWork.needsPlant(level, crop, pos);
        if (!needed) {
            return StageOutcome.SKIPPED;
        }
        // 藤架是"先放横梁、再插葡萄藤"两步：放下横梁后属于正常进度，不能报缺料
        boolean acted = trellis
                ? TrellisCropWork.applyPlant(level, crop, ctx.chests(), pos)
                : SoilCropWork.applyPlant(level, crop, ctx.chests(), pos);
        return acted ? StageOutcome.PROCESSED : StageOutcome.WAITING_MATERIAL;
    }

    @Override
    public Boolean needsBonemeal(CropWorkContext ctx, CropSpec spec) {
        ResolvedCrop crop = crop(spec);
        ServerLevel level = ctx.level();
        BlockPos pos = ctx.cropPos();
        if (crop == null || level == null || pos == null || !crop.isTrellisWork()) {
            return null;
        }
        return TrellisCropWork.needsBonemeal(level, crop, pos)
                && WorkContainers.has(level, ctx.chests(), Items.BONE_MEAL);
    }

    @Override
    public StageOutcome applyBonemeal(CropWorkContext ctx, CropSpec spec) {
        ResolvedCrop crop = crop(spec);
        ServerLevel level = ctx.level();
        BlockPos pos = ctx.cropPos();
        if (crop == null || level == null || pos == null || !crop.isTrellisWork()) {
            return null;
        }
        if (!TrellisCropWork.needsBonemeal(level, crop, pos)) {
            return StageOutcome.SKIPPED;
        }
        if (!WorkContainers.consume(level, ctx.chests(), Items.BONE_MEAL)) {
            return StageOutcome.WAITING_MATERIAL;
        }
        TrellisCropWork.applyBonemeal(level, crop, pos);
        return StageOutcome.PROCESSED;
    }

    @Override
    public Boolean needsHarvest(CropWorkContext ctx, CropSpec spec) {
        ResolvedCrop crop = crop(spec);
        ServerLevel level = ctx.level();
        BlockPos pos = ctx.cropPos();
        if (crop == null || level == null || pos == null) {
            return null;
        }
        if (crop.isTrellisWork()) {
            return TrellisCropWork.isReadyToHarvest(level, crop, pos);
        }
        // 茎类作物（原版西瓜/南瓜那种）仍走宿主的"收周围产物"
        return crop.isStem() ? null : CropHarvest.isReady(level, crop, pos);
    }

    @Override
    public StageOutcome applyHarvest(CropWorkContext ctx, CropSpec spec) {
        ResolvedCrop crop = crop(spec);
        ServerLevel level = ctx.level();
        BlockPos pos = ctx.cropPos();
        if (crop == null || level == null || pos == null) {
            return null;
        }
        if (crop.isTrellisWork()) {
            if (!TrellisCropWork.isReadyToHarvest(level, crop, pos)) {
                return StageOutcome.SKIPPED;
            }
            TrellisCropWork.harvest(level, crop, ctx.chests(), pos);
            return StageOutcome.PROCESSED;
        }
        if (crop.isStem()) {
            return null;
        }
        if (!CropHarvest.isReady(level, crop, pos)) {
            return StageOutcome.SKIPPED;
        }
        CropHarvest.harvest(level, crop, ctx.chests(), pos);
        CropHarvest.replantIfNeeded(level, crop, ctx.chests(), pos);
        return StageOutcome.PROCESSED;
    }

    @Override
    public Boolean seedAvailable(CropWorkContext ctx, CropSpec spec) {
        ResolvedCrop crop = crop(spec);
        if (crop == null || ctx.level() == null || !crop.isTrellisWork()) {
            return null;
        }
        // 只有"还要搭结构 / 还要补品种"时才无视种子强行扫 PLANT；
        // 结构齐了只是等种子的话交回宿主判断，别让空格一直占着 PLANT 把 HARVEST 饿死
        return TrellisCropWork.needsSeedlessPlantWork(ctx.level(), crop) ? Boolean.TRUE : null;
    }

    @Override
    public Boolean usesGroupedFarmScan(CropWorkContext ctx, CropSpec spec) {
        ResolvedCrop crop = crop(spec);
        return crop != null && crop.usesCustomSoil() ? Boolean.FALSE : null;
    }

    @Override
    public Component statusLabel(CropWorkContext ctx, String statusKey, CropSpec spec) {
        ResolvedCrop crop = crop(spec);
        if (crop == null || !crop.usesCustomSoil() || !isSupplyStatus(statusKey)) {
            return null;
        }
        ServerLevel level = ctx.level();
        if (level == null) {
            return null;
        }
        Item item = TrellisCropWork.statusItemForPhase(level, crop, box(ctx), statusKey);
        Component itemName = item != null && item != Items.AIR
                ? item.getName(new ItemStack(item))
                : Component.translatable(crop.statusTranslationKey());
        boolean missingSeed = STATUS_MISSING_SEED.equals(statusKey);
        boolean till = STATUS_TILL.equals(statusKey);
        boolean soilItem = item != null && item != Items.AIR && crop.soilItems().contains(item);
        boolean seedItem = item != null && item != Items.AIR && item == crop.seed();
        String key;
        if (missingSeed && soilItem) {
            key = "gui.letsdocropaddons.waiting_soil";
        } else if (missingSeed && seedItem) {
            key = statusKey;
        } else if (missingSeed) {
            key = "gui.letsdocropaddons.trellis.waiting_item";
        } else if (till) {
            key = "gui.letsdocropaddons.trellis.placing_soil";
        } else if (crop.isTrellisWork() && !seedItem) {
            key = "gui.letsdocropaddons.trellis.placing_item";
        } else {
            key = statusKey;
        }
        return Component.translatable(key, itemName);
    }

    private static boolean isSupplyStatus(String statusKey) {
        return STATUS_MISSING_SEED.equals(statusKey)
                || STATUS_TILL.equals(statusKey)
                || STATUS_PLANT.equals(statusKey);
    }

    private static ResolvedCrop crop(CropSpec spec) {
        if (spec == null || spec.id() == null) {
            return null;
        }
        return CropCatalog.resolve(spec.id()).orElse(null);
    }

    private static FarmlandBoxData box(CropWorkContext ctx) {
        return ctx != null && ctx.data() instanceof FarmlandBoxData data ? data : null;
    }
}
