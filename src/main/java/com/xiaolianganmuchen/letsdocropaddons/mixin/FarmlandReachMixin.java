package com.xiaolianganmuchen.letsdocropaddons.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.xiaolianganmuchen.letsdocropaddons.crop.CropContext;
import com.xiaolianganmuchen.letsdocropaddons.crop.ResolvedCrop;
import com.xiaolianganmuchen.letsdocropaddons.crop.TrellisCropWork;
import common.cn.kafei.simukraft.citizen.CitizenData;
import common.cn.kafei.simukraft.citizen.CitizenTeleportService;
import common.cn.kafei.simukraft.entity.CitizenEntity;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * 藤架作物密集种植时的「够不着」兜底。
 *
 * <p>宿主的 {@code isCloseEnoughToWork} 要求农夫站在离目标格 2.4 格内才肯动手，
 * 站位由 {@code FarmlandWorkGeometry.workAnchorFor} 在目标格同层四周找。普通葡萄
 * （藤茎堆：cell 底茎 + above(1) 顶茎）密集种满时，同层邻位全被邻列藤杆占住、
 * 下层又是实心地面，宿主一个位都找不到，只能退回农田盒停车点甚至目标格中心
 * （被藤杆占据）—— 农夫寻路到被占据的坐标，永远走不到，表现为农夫在田边
 * 一动不动、头顶状态文字也不跳。</p>
 *
 * <p>{@link FarmlandWorkGeometryMixin} 已把站位搜索扩展到藤架顶，但藤架顶比地面高
 * 1~2 格，原版寻路爬不上去（stepHeight 只有 0.6）。这里在宿主判定「不够近」且
 * 作物是藤架类时，先用 NSUK 自带的 {@link CitizenTeleportService#teleportLoadedCitizen}
 * 把农夫送到藤架 / 茎堆顶的作业锚点（藤茎是完整实心方块，{@code isFaceSturdy}
 * 成立，是合法落点），再判定可达。宿主随后当 tick 就执行作业（setBlock/useOn
 * 都是按坐标操作，不校验真实距离）。</p>
 *
 * <p>节流：每个公民按「农田盒」记状态——首格给 2 秒走路机会（非密集时农夫自己
 * 就走到了，完全不需要传送），之后每 0.5 秒最多传一次，避免每 tick 重复传送。</p>
 */
@Mixin(
   value = {common.cn.kafei.simukraft.farmland.FarmlandFarmingService.class},
   remap = false
)
public abstract class FarmlandReachMixin {
   /** 公民 UUID -> {boxPos, firstFailTick, lastTeleportTick} */
   private static final Map<UUID, Object[]> letsdo$reachState = new ConcurrentHashMap<>();
   /** 首格发现「够不着」后，再等这么多 tick 才传送（给走路留时间）。 */
   private static final long letsdo$WALK_GRACE_TICKS = 40L;
   /** 同一公民两次传送的最小间隔。 */
   private static final long letsdo$TELEPORT_COOLDOWN_TICKS = 10L;

   private static volatile Method letsdo$cropPosAccessor;

   @WrapMethod(
      method = {"isCloseEnoughToWork"}
   )
   private static boolean letsdo$trellisReach(
      ServerLevel level, BlockPos boxPos, CitizenData farmer, CitizenEntity farmerEntity,
      @Coerce Object target, Operation<Boolean> original
   ) {
      // 宿主原逻辑：已够近 → true；不够近时宿主已向 workAnchorFor 的锚点发起寻路。
      if ((Boolean)original.call(new Object[]{level, boxPos, farmer, farmerEntity, target})) {
         return true;
      }

      ResolvedCrop crop = CropContext.current().orElse(null);
      if (level == null || farmer == null || farmerEntity == null || crop == null || !crop.isTrellisWork()) {
         // 非藤架作物（原版小麦 / 啤酒花 / 自定义土壤作物）：维持宿主行为，等它走路。
         return false;
      }

      BlockPos cropPos = letsdo$cropPosOf(target);
      if (cropPos == null) {
         return false;
      }

      long now = level.getGameTime();
      UUID citizenId = farmer.uuid();
      Object[] state = letsdo$reachState.get(citizenId);
      if (state == null || !state[0].equals(boxPos)) {
         state = new Object[]{boxPos, now, Long.valueOf(-1000L)};
         letsdo$reachState.put(citizenId, state);
      }
      // 首格先等 walk-grace，让农夫有机会自己走过去（正常密度下足够）。
      if (now - ((Long)state[1]).longValue() < letsdo$WALK_GRACE_TICKS) {
         return false;
      }
      if (now - ((Long)state[2]).longValue() < letsdo$TELEPORT_COOLDOWN_TICKS) {
         return false;
      }

      // 只取「结构顶」锚点（藤架 / 茎堆顶）；地面锚点说明是密度问题之外的寻路受阻，
      // 交给宿主寻路系统自己解决，不传送。
      Vec3 anchor = TrellisCropWork.teleportAnchor(level, cropPos);
      if (anchor == null) {
         return false;
      }

      boolean teleported = CitizenTeleportService.teleportLoadedCitizen(level, farmer, anchor);
      if (!teleported) {
         // 宿主那个方法要先按目标位置把公民实体「认领」回来（reconcileLoadedCitizenEntities），
         // 公民离目标几格远时认领不到实体就直接返回 false —— 恰恰密集种植时农夫都在远处，
         // 于是兜底形同虚设。这里照宿主 teleportLoadedCitizen 的收尾动作自己送一次：
         // 停掉寻路 → 清零速度 → teleportTo，落地距离合格就算成功。
         farmerEntity.getNavigation().stop();
         farmerEntity.setDeltaMovement(Vec3.ZERO);
         farmerEntity.teleportTo(anchor.x, anchor.y, anchor.z);
         teleported = farmerEntity.position().distanceToSqr(anchor) <= 1.0;
      }
      state[2] = Long.valueOf(now);
      return teleported;
   }

   /** 反射读包私有 record {@code FarmlandWorkTarget} 的 cropPos 组件。 */
   private static BlockPos letsdo$cropPosOf(Object target) {
      if (target == null) {
         return null;
      }
      try {
         Method accessor = letsdo$cropPosAccessor;
         if (accessor == null) {
            accessor = target.getClass().getDeclaredMethod("cropPos");
            accessor.setAccessible(true);
            letsdo$cropPosAccessor = accessor;
         }
         return (BlockPos)accessor.invoke(target);
      } catch (ReflectiveOperationException | RuntimeException var3) {
         return null;
      }
   }
}
