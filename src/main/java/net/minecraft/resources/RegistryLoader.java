package net.minecraft.resources;

import com.google.common.collect.Maps;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Lifecycle;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.WritableRegistry;

public class RegistryLoader {
    private final RegistryResourceAccess resources;
    private final Map<ResourceKey<? extends Registry<?>>, RegistryLoader.ReadCache<?>> readCache = new IdentityHashMap<>();

    RegistryLoader(RegistryResourceAccess entryLoader) {
        this.resources = entryLoader;
    }

    public <E> DataResult<? extends Registry<E>> overrideRegistryFromResources(WritableRegistry<E> registry, ResourceKey<? extends Registry<E>> registryRef, Codec<E> codec, DynamicOps<JsonElement> ops) {
        Map<ResourceKey<E>, RegistryResourceAccess.EntryThunk<E>> map = this.resources.listResources(registryRef);
        DataResult<WritableRegistry<E>> dataResult = DataResult.success(registry, Lifecycle.stable());

        for(Map.Entry<ResourceKey<E>, RegistryResourceAccess.EntryThunk<E>> entry : map.entrySet()) {
            dataResult = dataResult.flatMap((reg) -> {
                return this.overrideElementFromResources(reg, registryRef, codec, entry.getKey(), Optional.of(entry.getValue()), ops).map((entryx) -> {
                    return reg;
                });
            });
        }

        return dataResult.setPartial(registry);
    }

    <E> DataResult<Holder<E>> overrideElementFromResources(WritableRegistry<E> registry, ResourceKey<? extends Registry<E>> registryRef, Codec<E> codec, ResourceKey<E> entryKey, DynamicOps<JsonElement> ops) {
        Optional<RegistryResourceAccess.EntryThunk<E>> optional = this.resources.getResource(entryKey);
        return this.overrideElementFromResources(registry, registryRef, codec, entryKey, optional, ops);
    }

    private <E> DataResult<Holder<E>> overrideElementFromResources(WritableRegistry<E> registry, ResourceKey<? extends Registry<E>> registryRef, Codec<E> codec, ResourceKey<E> entryKey, Optional<RegistryResourceAccess.EntryThunk<E>> parseable, DynamicOps<JsonElement> ops) {
        RegistryLoader.ReadCache<E> readCache = this.readCache(registryRef);
        DataResult<Holder<E>> dataResult = readCache.values.get(entryKey);
        if (dataResult != null) {
            return dataResult;
        } else {
            Holder<E> holder = registry.getOrCreateHolderOrThrow(entryKey);
            readCache.values.put(entryKey, DataResult.success(holder));
            DataResult<Holder<E>> dataResult2;
            if (parseable.isEmpty()) {
                if (registry.containsKey(entryKey)) {
                    dataResult2 = DataResult.success(holder, Lifecycle.stable());
                } else {
                    dataResult2 = DataResult.error("Missing referenced custom/removed registry entry for registry " + registryRef + " named " + entryKey.location());
                }
            } else {
                DataResult<RegistryResourceAccess.ParsedEntry<E>> dataResult4 = parseable.get().parseElement(ops, codec);
                Optional<RegistryResourceAccess.ParsedEntry<E>> optional = dataResult4.result();
                if (optional.isPresent()) {
                    RegistryResourceAccess.ParsedEntry<E> parsedEntry = optional.get();
                    registry.registerOrOverride(parsedEntry.fixedId(), entryKey, parsedEntry.value(), dataResult4.lifecycle());
                }

                dataResult2 = dataResult4.map((entry) -> {
                    return holder;
                });
            }

            readCache.values.put(entryKey, dataResult2);
            return dataResult2;
        }
    }

    private <E> RegistryLoader.ReadCache<E> readCache(ResourceKey<? extends Registry<E>> registryRef) {
        return (RegistryLoader.ReadCache<E>) this.readCache.computeIfAbsent(registryRef, (ref) -> { // Paper - decompile fix
            return new RegistryLoader.ReadCache();
        });
    }

    public RegistryLoader.Bound bind(RegistryAccess.Writable dynamicRegistryManager) {
        return new RegistryLoader.Bound(dynamicRegistryManager, this);
    }

    public static record Bound(RegistryAccess.Writable access, RegistryLoader loader) {
        public <E> DataResult<? extends Registry<E>> overrideRegistryFromResources(ResourceKey<? extends Registry<E>> registryRef, Codec<E> codec, DynamicOps<JsonElement> ops) {
            WritableRegistry<E> writableRegistry = this.access.ownedWritableRegistryOrThrow(registryRef);
            return this.loader.overrideRegistryFromResources(writableRegistry, registryRef, codec, ops);
        }

        public <E> DataResult<Holder<E>> overrideElementFromResources(ResourceKey<? extends Registry<E>> registryRef, Codec<E> codec, ResourceKey<E> entryKey, DynamicOps<JsonElement> ops) {
            WritableRegistry<E> writableRegistry = this.access.ownedWritableRegistryOrThrow(registryRef);
            return this.loader.overrideElementFromResources(writableRegistry, registryRef, codec, entryKey, ops);
        }
    }

    static final class ReadCache<E> {
        final Map<ResourceKey<E>, DataResult<Holder<E>>> values = Maps.newIdentityHashMap();
    }
}
