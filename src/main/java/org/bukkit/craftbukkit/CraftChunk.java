package org.bukkit.craftbukkit;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.mojang.serialization.Codec;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;

public class CraftChunk implements Chunk {
    private WeakReference<net.minecraft.world.level.chunk.LevelChunk> weakChunk;
    private final ServerLevel worldServer;
    private final int x;
    private final int z;
    private static final PalettedContainer<net.minecraft.world.level.block.state.BlockState> emptyBlockIDs = new PalettedContainer<>(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
    private static final byte[] emptyLight = new byte[2048];

    public CraftChunk(net.minecraft.world.level.chunk.LevelChunk chunk) {
        this.weakChunk = new WeakReference<net.minecraft.world.level.chunk.LevelChunk>(chunk);

        this.worldServer = (ServerLevel) this.getHandle().level;
        this.x = this.getHandle().getPos().x;
        this.z = this.getHandle().getPos().z;
    }

    public CraftChunk(ServerLevel worldServer, int x, int z) {
        this.weakChunk = new WeakReference<>(null);
        this.worldServer = worldServer;
        this.x = x;
        this.z = z;
    }

    @Override
    public World getWorld() {
        return this.worldServer.getWorld();
    }

    public CraftWorld getCraftWorld() {
        return (CraftWorld) this.getWorld();
    }

    public net.minecraft.world.level.chunk.LevelChunk getHandle() {
        net.minecraft.world.level.chunk.LevelChunk c = this.weakChunk.get();

        if (c == null) {
            c = this.worldServer.getChunk(x, z);

            this.weakChunk = new WeakReference<net.minecraft.world.level.chunk.LevelChunk>(c);
        }

        return c;
    }

    void breakLink() {
        this.weakChunk.clear();
    }

    @Override
    public int getX() {
        return this.x;
    }

    @Override
    public int getZ() {
        return this.z;
    }

    @Override
    public String toString() {
        return "CraftChunk{" + "x=" + this.getX() + "z=" + this.getZ() + '}';
    }

    @Override
    public Block getBlock(int x, int y, int z) {
        CraftChunk.validateChunkCoordinates(this.getHandle().getMinBuildHeight(), this.getHandle().getMaxBuildHeight(), x, y, z);

        return new CraftBlock(this.worldServer, new BlockPos((this.x << 4) | x, y, (this.z << 4) | z));
    }

    @Override
    public boolean isEntitiesLoaded() {
        return this.getCraftWorld().getHandle().entityManager.areEntitiesLoaded(ChunkPos.asLong(x, z));
    }

    @Override
    public Entity[] getEntities() {
        if (!this.isLoaded()) {
            this.getWorld().getChunkAt(x, z); // Transient load for this tick
        }

        PersistentEntitySectionManager<net.minecraft.world.entity.Entity> entityManager = this.getCraftWorld().getHandle().entityManager;
        long pair = ChunkPos.asLong(x, z);

        if (entityManager.areEntitiesLoaded(pair)) {
            return entityManager.getEntities(new ChunkPos(this.x, this.z)).stream()
                    .map(net.minecraft.world.entity.Entity::getBukkitEntity)
                    .filter(Objects::nonNull).toArray(Entity[]::new);
        }

        entityManager.ensureChunkQueuedForLoad(pair); // Start entity loading

        // SPIGOT-6772: Use entity mailbox and re-schedule entities if they get unloaded
        ProcessorMailbox<Runnable> mailbox = ((EntityStorage) entityManager.permanentStorage).entityDeserializerQueue;
        BooleanSupplier supplier = () -> {
            // only execute inbox if our entities are not present
            if (entityManager.areEntitiesLoaded(pair)) {
                return true;
            }

            if (!entityManager.isPending(pair)) {
                // Our entities got unloaded, this should normally not happen.
                entityManager.ensureChunkQueuedForLoad(pair); // Re-start entity loading
            }

            // tick loading inbox, which loads the created entities to the world
            // (if present)
            entityManager.tick();
            // check if our entities are loaded
            return entityManager.areEntitiesLoaded(pair);
        };

        // now we wait until the entities are loaded,
        // the converting from NBT to entity object is done on the main Thread which is why we wait
        while (!supplier.getAsBoolean()) {
            if (mailbox.size() != 0) {
                mailbox.run();
            } else {
                Thread.yield();
                LockSupport.parkNanos("waiting for entity loading", 100000L);
            }
        }

        return entityManager.getEntities(new ChunkPos(this.x, this.z)).stream()
                .map(net.minecraft.world.entity.Entity::getBukkitEntity)
                .filter(Objects::nonNull).toArray(Entity[]::new);
    }

    @Override
    public BlockState[] getTileEntities() {
        if (!this.isLoaded()) {
            this.getWorld().getChunkAt(x, z); // Transient load for this tick
        }
        int index = 0;
        net.minecraft.world.level.chunk.LevelChunk chunk = this.getHandle();

        BlockState[] entities = new BlockState[chunk.blockEntities.size()];

        for (Object obj : chunk.blockEntities.keySet().toArray()) {
            if (!(obj instanceof BlockPos)) {
                continue;
            }

            BlockPos position = (BlockPos) obj;
            entities[index++] = this.worldServer.getWorld().getBlockAt(position.getX(), position.getY(), position.getZ()).getState();
        }

        return entities;
    }

    @Override
    public boolean isLoaded() {
        return this.getWorld().isChunkLoaded(this);
    }

    @Override
    public boolean load() {
        return this.getWorld().loadChunk(this.getX(), this.getZ(), true);
    }

    @Override
    public boolean load(boolean generate) {
        return this.getWorld().loadChunk(this.getX(), this.getZ(), generate);
    }

    @Override
    public boolean unload() {
        return this.getWorld().unloadChunk(this.getX(), this.getZ());
    }

    @Override
    public boolean isSlimeChunk() {
        // 987234911L is deterimined in EntitySlime when seeing if a slime can spawn in a chunk
        return this.worldServer.paperConfig().entities.spawning.allChunksAreSlimeChunks || WorldgenRandom.seedSlimeChunk(this.getX(), this.getZ(), this.getWorld().getSeed(), worldServer.spigotConfig.slimeSeed).nextInt(10) == 0; // Paper
    }

    @Override
    public boolean unload(boolean save) {
        return this.getWorld().unloadChunk(this.getX(), this.getZ(), save);
    }

    @Override
    public boolean isForceLoaded() {
        return this.getWorld().isChunkForceLoaded(this.getX(), this.getZ());
    }

    @Override
    public void setForceLoaded(boolean forced) {
        this.getWorld().setChunkForceLoaded(this.getX(), this.getZ(), forced);
    }

    @Override
    public boolean addPluginChunkTicket(Plugin plugin) {
        return this.getWorld().addPluginChunkTicket(this.getX(), this.getZ(), plugin);
    }

    @Override
    public boolean removePluginChunkTicket(Plugin plugin) {
        return this.getWorld().removePluginChunkTicket(this.getX(), this.getZ(), plugin);
    }

    @Override
    public Collection<Plugin> getPluginChunkTickets() {
        return this.getWorld().getPluginChunkTickets(this.getX(), this.getZ());
    }

    @Override
    public long getInhabitedTime() {
        return this.getHandle().getInhabitedTime();
    }

    @Override
    public void setInhabitedTime(long ticks) {
        Preconditions.checkArgument(ticks >= 0, "ticks cannot be negative");

        this.getHandle().setInhabitedTime(ticks);
    }

    @Override
    public boolean contains(BlockData block) {
        Preconditions.checkArgument(block != null, "Block cannot be null");

        Predicate<net.minecraft.world.level.block.state.BlockState> nms = Predicates.equalTo(((CraftBlockData) block).getState());
        for (LevelChunkSection section : this.getHandle().getSections()) {
            if (section != null && section.getStates().maybeHas(nms)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public ChunkSnapshot getChunkSnapshot() {
        return this.getChunkSnapshot(true, false, false);
    }

    @Override
    public ChunkSnapshot getChunkSnapshot(boolean includeMaxBlockY, boolean includeBiome, boolean includeBiomeTempRain) {
        net.minecraft.world.level.chunk.LevelChunk chunk = this.getHandle();

        LevelChunkSection[] cs = chunk.getSections();
        PalettedContainer[] sectionBlockIDs = new PalettedContainer[cs.length];
        byte[][] sectionSkyLights = new byte[cs.length][];
        byte[][] sectionEmitLights = new byte[cs.length][];
        boolean[] sectionEmpty = new boolean[cs.length];
        PalettedContainerRO<Holder<Biome>>[] biome = (includeBiome || includeBiomeTempRain) ? new PalettedContainer[cs.length] : null;

        Registry<Biome> iregistry = this.worldServer.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
        Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec = PalettedContainer.codecRO(iregistry.asHolderIdMap(), iregistry.holderByNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES, iregistry.getHolderOrThrow(Biomes.PLAINS));

        for (int i = 0; i < cs.length; i++) {
            CompoundTag data = new CompoundTag();

            data.put("block_states", ChunkSerializer.BLOCK_STATE_CODEC.encodeStart(NbtOps.INSTANCE, cs[i].getStates()).get().left().get());
            sectionBlockIDs[i] = ChunkSerializer.BLOCK_STATE_CODEC.parse(NbtOps.INSTANCE, data.getCompound("block_states")).get().left().get();

            LevelLightEngine lightengine = chunk.level.getLightEngine();
            DataLayer skyLightArray = lightengine.getLayerListener(LightLayer.SKY).getDataLayerData(SectionPos.of(x, i, z));
            if (skyLightArray == null) {
                sectionSkyLights[i] = CraftChunk.emptyLight;
            } else {
                sectionSkyLights[i] = new byte[2048];
                System.arraycopy(skyLightArray.getData(), 0, sectionSkyLights[i], 0, 2048);
            }
            DataLayer emitLightArray = lightengine.getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(x, i, z));
            if (emitLightArray == null) {
                sectionEmitLights[i] = CraftChunk.emptyLight;
            } else {
                sectionEmitLights[i] = new byte[2048];
                System.arraycopy(emitLightArray.getData(), 0, sectionEmitLights[i], 0, 2048);
            }

            if (biome != null) {
                data.put("biomes", biomeCodec.encodeStart(NbtOps.INSTANCE, cs[i].getBiomes()).get().left().get());
                biome[i] = biomeCodec.parse(NbtOps.INSTANCE, data.getCompound("biomes")).get().left().get();
            }
        }

        Heightmap hmap = null;

        if (includeMaxBlockY) {
            hmap = new Heightmap(chunk, Heightmap.Types.MOTION_BLOCKING);
            hmap.setRawData(chunk, Heightmap.Types.MOTION_BLOCKING, chunk.heightmaps.get(Heightmap.Types.MOTION_BLOCKING).getRawData());
        }

        World world = this.getWorld();
        return new CraftChunkSnapshot(this.getX(), this.getZ(), chunk.getMinBuildHeight(), chunk.getMaxBuildHeight(), world.getName(), world.getFullTime(), sectionBlockIDs, sectionSkyLights, sectionEmitLights, sectionEmpty, hmap, iregistry, biome);
    }

    @Override
    public PersistentDataContainer getPersistentDataContainer() {
        return this.getHandle().persistentDataContainer;
    }

    public static ChunkSnapshot getEmptyChunkSnapshot(int x, int z, CraftWorld world, boolean includeBiome, boolean includeBiomeTempRain) {
        ChunkAccess actual = world.getHandle().getChunk(x, z, (includeBiome || includeBiomeTempRain) ? ChunkStatus.BIOMES : ChunkStatus.EMPTY);

        /* Fill with empty data */
        int hSection = actual.getSectionsCount();
        PalettedContainer[] blockIDs = new PalettedContainer[hSection];
        byte[][] skyLight = new byte[hSection][];
        byte[][] emitLight = new byte[hSection][];
        boolean[] empty = new boolean[hSection];
        Registry<Biome> iregistry = world.getHandle().registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
        PalettedContainer<Holder<Biome>>[] biome = (includeBiome || includeBiomeTempRain) ? new PalettedContainer[hSection] : null;
        Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec = PalettedContainer.codecRO(iregistry.asHolderIdMap(), iregistry.holderByNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES, iregistry.getHolderOrThrow(Biomes.PLAINS));

        for (int i = 0; i < hSection; i++) {
            blockIDs[i] = CraftChunk.emptyBlockIDs;
            skyLight[i] = CraftChunk.emptyLight;
            emitLight[i] = CraftChunk.emptyLight;
            empty[i] = true;

            if (biome != null) {
                biome[i] = (PalettedContainer<Holder<Biome>>) biomeCodec.parse(NbtOps.INSTANCE, biomeCodec.encodeStart(NbtOps.INSTANCE, actual.getSection(i).getBiomes()).get().left().get()).get().left().get();
            }
        }

        return new CraftChunkSnapshot(x, z, world.getMinHeight(), world.getMaxHeight(), world.getName(), world.getFullTime(), blockIDs, skyLight, emitLight, empty, new Heightmap(actual, Heightmap.Types.MOTION_BLOCKING), iregistry, biome);
    }

    static void validateChunkCoordinates(int minY, int maxY, int x, int y, int z) {
        Preconditions.checkArgument(0 <= x && x <= 15, "x out of range (expected 0-15, got %s)", x);
        Preconditions.checkArgument(minY <= y && y <= maxY, "y out of range (expected %s-%s, got %s)", minY, maxY, y);
        Preconditions.checkArgument(0 <= z && z <= 15, "z out of range (expected 0-15, got %s)", z);
    }

    static {
        Arrays.fill(emptyLight, (byte) 0xFF);
    }
}
