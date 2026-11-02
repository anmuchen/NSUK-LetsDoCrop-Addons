package com.xiaolianganmuchen.letsdocropaddons;

import com.xiaolianganmuchen.letsdocropaddons.client.DelightModConfigScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(
   value = "letsdocropaddons",
   dist = {Dist.CLIENT}
)
public class LetsDoCropAddonsClient {
   public LetsDoCropAddonsClient(ModContainer container) {
      container.registerExtensionPoint(IConfigScreenFactory.class, (IConfigScreenFactory)(modContainer, parent) -> new DelightModConfigScreen(parent));
   }
}
