package com.xiaolianganmuchen.letsdocropaddons;

import com.mojang.logging.LogUtils;
import com.xiaolianganmuchen.letsdocropaddons.config.DelightCropConfig;
import com.xiaolianganmuchen.letsdocropaddons.crop.CropJsonReloadListener;
import com.xiaolianganmuchen.letsdocropaddons.nsukapi.CropApiRegistration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;

@Mod("letsdocropaddons")
public class LetsDoCropAddons {
   public static final String MODID = "letsdocropaddons";
   public static final Logger LOGGER = LogUtils.getLogger();

   public LetsDoCropAddons(IEventBus modEventBus, ModContainer modContainer) {
      modEventBus.addListener(this::commonSetup);
      NeoForge.EVENT_BUS.register(this);
      modContainer.registerConfig(Type.COMMON, DelightCropConfig.SPEC);
   }

   private void commonSetup(FMLCommonSetupEvent event) {
      event.enqueueWork(() -> {
         CropJsonReloadListener.loadBuiltInFromJar();
         CropApiRegistration.sync();
         LOGGER.info("Let's Do Crop Addons ready. Crops are registered into NSUK crop API.");
      });
   }

   @SubscribeEvent
   public void onAddReloadListeners(AddReloadListenerEvent event) {
      event.addListener(CropJsonReloadListener.INSTANCE);
   }
}
