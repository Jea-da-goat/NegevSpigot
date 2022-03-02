package io.papermc.paper.registry;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import org.bukkit.Keyed;

public record RegistryKey<API extends Keyed, MINECRAFT>(Class<API> apiClass, ResourceKey<? extends Registry<MINECRAFT>> resourceKey) {
}
