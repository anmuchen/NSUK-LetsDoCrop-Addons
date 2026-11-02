package com.xiaolianganmuchen.letsdocropaddons.crop;

import java.util.Locale;

public enum CropLayout {
   FULL,
   STEM,
   TRELLIS,
   /**
    * 丛林葡萄（Vinery 的 {@code jungle_grape_seeds_red/white}）：「一个种子对应一个木格架」。
    *
    * <p>布局：{@code cell-1} 铺土壤，{@code cell} 放<b>任意</b>木格架（{@code vinery:lattice}
    * 标签内的全部变体，由 {@code plant_tag} 识别），种子经原生 {@code useItemOn} 直接种在
    * 格架上——一格架同时只长一株（原生 age==0 门槛保证），种下即 age=1 + grape 属性 +
    * 方块实体同步（格架藤叶由 {@code LatticeBlockEntity} 渲染）。</p>
    *
    * <p>生长 / 收获全部走原生逻辑，不做任何代打：
    * <ul>
    *   <li>生长：{@code randomTick} 98% 概率 age+1（无光照要求）；</li>
    *   <li>收获：age&gt;2 时空手右键格架 → 掉葡萄 + age 复位 1（可持续收割，不耗种子）；</li>
    *   <li>骨粉：{@code isValidBonemealTarget}（age&gt;0 且未熟）→ +1~2 封顶 4。</li>
    * </ul>
    * 原生没有机械蔓延：格架之间按 FACING/BOTTOM 相同自动连线（{@code getConnection}），
    * 藤叶由方块实体渲染——视觉上的「蔓延」即由此产生，农田盒一格一格铺过去即是整片藤架。</p>
    */
   LATTICE,
   /**
    * 普通葡萄（Vinery 的 6 种 {@code grapevine_stem}）：原生「两格藤茎堆」葡萄园。
    *
    * <p>布局：{@code cell-1} 铺土壤，{@code cell} 放底茎，{@code above(1)} 放顶茎，
    * 种子经原生 {@code useItemOn} 点在<b>顶茎</b>上——顶茎下方是茎，{@code hasTrunk} 成立，
    * 且因此不会触发 {@code LEAVES_PENDING} 定时自毁（原生只在「下方不是茎」时才挂定时刻）。</p>
    *
    * <p>生长 / 收获走原生：
    * <ul>
    *   <li>生长：{@code randomTick} 98% 概率 + 光照≥9 + {@code hasTrunk} → age+1；</li>
    *   <li>收获：顶茎 age&gt;3 空手右键 → 掉葡萄 + age 复位 2（可持续收割，不耗种子）；</li>
    *   <li>骨粉：{@code isValidBonemealTarget}（下方是茎且 age&gt;0）→ +1~2 封顶 4。</li>
    * </ul>
    * 藤茎站泥土即可（{@code isRedstoneConductor}），不需要酒馆格架。</p>
    */
   VINE_STACK;

   public static CropLayout fromName(String name) {
      if (name != null && !name.isBlank()) {
         try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
         } catch (IllegalArgumentException var2) {
            return FULL;
         }
      } else {
         return FULL;
      }
   }
}
