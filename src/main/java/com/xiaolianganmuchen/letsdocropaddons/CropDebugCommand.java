package com.xiaolianganmuchen.letsdocropaddons;

import com.mojang.brigadier.Command;
import com.nsukcropapi.api.BoxView;
import com.nsukcropapi.api.Boxes;
import com.xiaolianganmuchen.letsdocropaddons.crop.CropCatalog;
import com.xiaolianganmuchen.letsdocropaddons.crop.ResolvedCrop;
import com.xiaolianganmuchen.letsdocropaddons.crop.TrellisCropWork;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;

/**
 * {@code /letsdocropaddons diag}：列出本世界所有农田盒的判定结果。
 *
 * <p>按“离玩家最近”只报一个盒容易点到空盒，所以这里改成全量输出；
 * 格架布局会额外打印每格的分类（水槽 / 过道 / 已放架 / 缺架 / 杂物）与箱子材料情况。</p>
 */
@EventBusSubscriber(modid = LetsDoCropAddons.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class CropDebugCommand {

    private static final int MAX_BOXES = 12;

    private CropDebugCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("letsdocropaddons")
                .then(Commands.literal("diag").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    ServerLevel level = player.serverLevel();

                    int shown = 0;
                    for (BoxView box : Boxes.all(level)) {
                        if (shown++ >= MAX_BOXES) {
                            player.displayClientMessage(Component.literal("[diag] …… 农田盒过多，已截断"), false);
                            break;
                        }
                        String cropId = box.cropId() == null ? "" : box.cropId();
                        BlockPos boxPos = box.boxPos();
                        String where = boxPos.toShortString();
                        if (!player.blockPosition().closerThan(boxPos, 24.0)) {
                            where = where + "(远)";
                        }
                        player.displayClientMessage(Component.literal("[diag] 盒=" + where
                                + " 作物=" + (cropId.isBlank() ? "(未设置)" : cropId)
                                + " 运行=" + box.running()), false);

                        if (cropId.isBlank()) {
                            continue;
                        }
                        ResolvedCrop crop = CropCatalog.resolve(cropId).orElse(null);
                        if (crop == null) {
                            player.displayClientMessage(Component.literal("[diag]   作物未注册 / 被配置禁用"), false);
                            continue;
                        }
                        if (!box.hasPlot()) {
                            player.displayClientMessage(Component.literal("[diag]   没有圈地"), false);
                            continue;
                        }
                        List<BlockPos> chests = box.chests(level);
                        if (crop.isLattice()) {
                            player.displayClientMessage(Component.literal("[diag]   "
                                    + TrellisCropWork.diagnoseLattice(level, crop, box.plot(), boxPos, chests)), false);
                        } else {
                            player.displayClientMessage(Component.literal("[diag]   布局="
                                    + (crop.isVineStack() ? "vine_stack" : crop.isTrellis() ? "trellis" : "普通耕地")
                                    + " 箱子=" + chests.size() + " 个"), false);
                        }
                    }
                    if (shown == 0) {
                        player.displayClientMessage(Component.literal("[diag] 本世界没有农田盒数据"), false);
                    }
                    return Command.SINGLE_SUCCESS;
                })));
    }
}
