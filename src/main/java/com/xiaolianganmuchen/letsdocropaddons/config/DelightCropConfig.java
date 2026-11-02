package com.xiaolianganmuchen.letsdocropaddons.config;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.Builder;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;

public final class DelightCropConfig {
   private static final Builder BUILDER = new Builder();
   private static final BooleanValue ENABLE_FARM_AND_CHARM = BUILDER.comment(
         "Allow Farm & Charm farmland crops (tomato, oat, barley, corn, lettuce, onion, strawberry) in the NSUK farmland box."
      )
      .define("enableFarmAndCharm", true);
   private static final BooleanValue ENABLE_BREWERY = BUILDER.comment(
         "Allow Brewery farmland crops (hops, planted straight on farmland) in the NSUK farmland box."
      )
      .define("enableBrewery", true);
   private static final BooleanValue ENABLE_VINERY = BUILDER.comment(
         "Allow Vinery grapevines (red, white, savanna, taiga, jungle) in the NSUK farmland box."
      )
      .define("enableVinery", true);
   private static final BooleanValue AUTO_DISCOVERY = BUILDER.comment("Scan loaded mods for CropBlock seeds that were not listed in JSON.")
      .define("autoDiscovery", false);
   private static final ConfigValue<List<? extends String>> DISABLED_CROPS = BUILDER.comment("Catalog ids to hide, for example kc_tomato or kg_onion.")
      .defineListAllowEmpty("disabledCrops", List.of(), () -> "", DelightCropConfig::isString);
   public static final ModConfigSpec SPEC = BUILDER.build();

   private DelightCropConfig() {
   }

   public static boolean configReady() {
      try {
         return SPEC.isLoaded();
      } catch (RuntimeException var1) {
         return false;
      }
   }

   public static boolean autoDiscovery() {
      return flag(AUTO_DISCOVERY, false);
   }

   public static boolean isSourceEnabled(String modId) {
      if (modId != null && !modId.isBlank()) {
         return switch (modId) {
            case "farm_and_charm" -> flag(ENABLE_FARM_AND_CHARM, true);
            case "brewery" -> flag(ENABLE_BREWERY, true);
            case "vinery" -> flag(ENABLE_VINERY, true);
            case "custom" -> true;
            default -> true;
         };
      } else {
         return false;
      }
   }

   public static List<DelightCropConfig.SourceToggle> sourceToggles() {
      return List.of(
         new DelightCropConfig.SourceToggle("farm_and_charm", ENABLE_FARM_AND_CHARM),
         new DelightCropConfig.SourceToggle("brewery", ENABLE_BREWERY),
         new DelightCropConfig.SourceToggle("vinery", ENABLE_VINERY)
      );
   }

   public static void setAutoDiscovery(boolean value) {
      AUTO_DISCOVERY.set(value);
   }

   public static boolean isCropEnabled(String cropId) {
      if (cropId != null && !cropId.isBlank()) {
         try {
            if (!configReady()) {
               return true;
            } else {
               Set<String> disabled = ((List<?>)DISABLED_CROPS.get())
                  .stream()
                  .map(value -> String.valueOf(value).toLowerCase(Locale.ROOT))
                  .collect(Collectors.toSet());
               return !disabled.contains(cropId.toLowerCase(Locale.ROOT));
            }
         } catch (RuntimeException var21) {
            return true;
         }
      } else {
         return false;
      }
   }

   private static boolean flag(BooleanValue value, boolean fallback) {
      try {
         return !configReady() ? fallback : (Boolean)value.get();
      } catch (RuntimeException var3) {
         return fallback;
      }
   }

   private static boolean isString(Object value) {
      return value instanceof String;
   }

   public static record SourceToggle(String modId, BooleanValue value) {
      public boolean enabled() {
         return DelightCropConfig.flag(this.value, true);
      }

      public void setEnabled(boolean enabled) {
         this.value.set(enabled);
      }

      public String labelKey() {
         return "source.letsdocropaddons." + this.modId;
      }
   }
}
