package org.bukkit;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.support.AbstractTestingBase;
import org.junit.Assert;
import org.junit.Test;

public class EnchantmentTest extends AbstractTestingBase {

    @Test
    public void verifyMapping() {
        for (ResourceLocation key : Registry.ENCHANTMENT.keySet()) {
            net.minecraft.world.item.enchantment.Enchantment nms = Registry.ENCHANTMENT.get(key);

            Enchantment bukkitById = Enchantment.getByKey(CraftNamespacedKey.fromMinecraft(key));

            Assert.assertFalse("Unknown enchant name for " + key, bukkitById.getName().startsWith("UNKNOWN"));

            Assert.assertNotNull("Unknown target for " + key, bukkitById.getItemTarget());
        }
    }
}
