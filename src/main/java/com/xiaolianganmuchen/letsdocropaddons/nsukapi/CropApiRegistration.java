package com.xiaolianganmuchen.letsdocropaddons.nsukapi;

import com.nsukcropapi.api.CropRegistry;
import com.xiaolianganmuchen.letsdocropaddons.LetsDoCropAddons;
import com.xiaolianganmuchen.letsdocropaddons.crop.CropCatalog;
import com.xiaolianganmuchen.letsdocropaddons.crop.ResolvedCrop;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * 把乐事作物注册进 NSUK 作物 API。
 *
 * <p>作物 id 直接沿用迁移前的 {@code CropCatalog} id（它本来就存在存档的 {@code Crop} 字段里），
 * 所以旧存档里已经设置了作物的农田盒会被 API 直接认领，不需要玩家重选。</p>
 */
public final class CropApiRegistration {

    public static final String API_MODID = "nsukcropapi";

    private CropApiRegistration() {
    }

    /** 幂等：启动时调用一次，数据包重载后再调用一次（新出现的作物补注册）。 */
    public static void sync() {
        if (!ModList.get().isLoaded(API_MODID)) {
            return;
        }
        List<com.nsukcropapi.api.CropSpec> specs = new ArrayList<>();
        for (ResolvedCrop crop : CropCatalog.selectable()) {
            if (crop.isMissing() || crop.id() == null || crop.id().isBlank()) {
                continue;
            }
            if (crop.seed() == null || crop.seed() == Items.AIR) {
                continue;
            }
            if (crop.plantBlock() == null || crop.plantBlock() == Blocks.AIR) {
                continue;
            }
            specs.add(new com.nsukcropapi.api.CropSpec(
                    crop.id(),
                    crop.seed(),
                    crop.seed(),
                    crop.plantBlock(),
                    crop.translationKey(),
                    crop.sourceModId(),
                    CropCatalog.sourceLabelKey(crop.sourceModId()),
                    LetsDoCropWork.INSTANCE));
        }
        CropRegistry.registerAll(specs);
        LetsDoCropAddons.LOGGER.info("Synced {} crops into NSUK crop API", specs.size());
    }
}
