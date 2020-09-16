package net.minecraft.world.level.levelgen.structure;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.resources.RegistryFileCodec;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

public abstract class Structure {
    public static final Codec<Structure> DIRECT_CODEC = Registry.STRUCTURE_TYPES.byNameCodec().dispatch(Structure::type, StructureType::codec);
    public static final Codec<Holder<Structure>> CODEC = RegistryFileCodec.create(Registry.STRUCTURE_REGISTRY, DIRECT_CODEC);
    protected final Structure.StructureSettings settings;
    static { io.papermc.paper.world.structure.PaperConfiguredStructure.init(); } // Paper

    public static <S extends Structure> RecordCodecBuilder<S, Structure.StructureSettings> settingsCodec(RecordCodecBuilder.Instance<S> instance) {
        return Structure.StructureSettings.CODEC.forGetter((feature) -> {
            return feature.settings;
        });
    }

    public static <S extends Structure> Codec<S> simpleCodec(Function<Structure.StructureSettings, S> featureCreator) {
        return RecordCodecBuilder.create((instance) -> {
            return instance.group(settingsCodec(instance)).apply(instance, featureCreator);
        });
    }

    protected Structure(Structure.StructureSettings config) {
        this.settings = config;
    }

    public HolderSet<Biome> biomes() {
        return this.settings.biomes;
    }

    public Map<MobCategory, StructureSpawnOverride> spawnOverrides() {
        return this.settings.spawnOverrides;
    }

    public GenerationStep.Decoration step() {
        return this.settings.step;
    }

    public TerrainAdjustment terrainAdaptation() {
        return this.settings.terrainAdaptation;
    }

    public BoundingBox adjustBoundingBox(BoundingBox box) {
        return this.terrainAdaptation() != TerrainAdjustment.NONE ? box.inflatedBy(12) : box;
    }

    public StructureStart generate(RegistryAccess dynamicRegistryManager, ChunkGenerator chunkGenerator, BiomeSource biomeSource, RandomState noiseConfig, StructureTemplateManager structureTemplateManager, long seed, ChunkPos chunkPos, int references, LevelHeightAccessor world, Predicate<Holder<Biome>> validBiomes) {
        Optional<Structure.GenerationStub> optional = this.findGenerationPoint(new Structure.GenerationContext(dynamicRegistryManager, chunkGenerator, biomeSource, noiseConfig, structureTemplateManager, seed, chunkPos, world, validBiomes));
        if (optional.isPresent() && isValidBiome(optional.get(), chunkGenerator, noiseConfig, validBiomes)) {
            StructurePiecesBuilder structurePiecesBuilder = optional.get().getPiecesBuilder();
            StructureStart structureStart = new StructureStart(this, chunkPos, references, structurePiecesBuilder.build());
            if (structureStart.isValid()) {
                return structureStart;
            }
        }

        return StructureStart.INVALID_START;
    }

    protected static Optional<Structure.GenerationStub> onTopOfChunkCenter(Structure.GenerationContext context, Heightmap.Types heightmap, Consumer<StructurePiecesBuilder> generator) {
        ChunkPos chunkPos = context.chunkPos();
        int i = chunkPos.getMiddleBlockX();
        int j = chunkPos.getMiddleBlockZ();
        int k = context.chunkGenerator().getFirstOccupiedHeight(i, j, heightmap, context.heightAccessor(), context.randomState());
        return Optional.of(new Structure.GenerationStub(new BlockPos(i, k, j), generator));
    }

    private static boolean isValidBiome(Structure.GenerationStub result, ChunkGenerator chunkGenerator, RandomState noiseConfig, Predicate<Holder<Biome>> validBiomes) {
        BlockPos blockPos = result.position();
        return validBiomes.test(chunkGenerator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(blockPos.getX()), QuartPos.fromBlock(blockPos.getY()), QuartPos.fromBlock(blockPos.getZ()), noiseConfig.sampler()));
    }

    public void afterPlace(WorldGenLevel world, StructureManager structureAccessor, ChunkGenerator chunkGenerator, RandomSource random, BoundingBox box, ChunkPos chunkPos, PiecesContainer pieces) {
    }

    private static int[] getCornerHeights(Structure.GenerationContext context, int x, int width, int z, int height) {
        ChunkGenerator chunkGenerator = context.chunkGenerator();
        LevelHeightAccessor levelHeightAccessor = context.heightAccessor();
        RandomState randomState = context.randomState();
        return new int[]{chunkGenerator.getFirstOccupiedHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, levelHeightAccessor, randomState), chunkGenerator.getFirstOccupiedHeight(x, z + height, Heightmap.Types.WORLD_SURFACE_WG, levelHeightAccessor, randomState), chunkGenerator.getFirstOccupiedHeight(x + width, z, Heightmap.Types.WORLD_SURFACE_WG, levelHeightAccessor, randomState), chunkGenerator.getFirstOccupiedHeight(x + width, z + height, Heightmap.Types.WORLD_SURFACE_WG, levelHeightAccessor, randomState)};
    }

    protected static int getLowestY(Structure.GenerationContext context, int width, int height) {
        ChunkPos chunkPos = context.chunkPos();
        int i = chunkPos.getMinBlockX();
        int j = chunkPos.getMinBlockZ();
        return getLowestY(context, i, j, width, height);
    }

    protected static int getLowestY(Structure.GenerationContext context, int x, int z, int width, int height) {
        int[] is = getCornerHeights(context, x, width, z, height);
        return Math.min(Math.min(is[0], is[1]), Math.min(is[2], is[3]));
    }

    /** @deprecated */
    @Deprecated
    protected BlockPos getLowestYIn5by5BoxOffset7Blocks(Structure.GenerationContext context, Rotation rotation) {
        int i = 5;
        int j = 5;
        if (rotation == Rotation.CLOCKWISE_90) {
            i = -5;
        } else if (rotation == Rotation.CLOCKWISE_180) {
            i = -5;
            j = -5;
        } else if (rotation == Rotation.COUNTERCLOCKWISE_90) {
            j = -5;
        }

        ChunkPos chunkPos = context.chunkPos();
        int k = chunkPos.getBlockX(7);
        int l = chunkPos.getBlockZ(7);
        return new BlockPos(k, getLowestY(context, k, l, i, j), l);
    }

    public abstract Optional<Structure.GenerationStub> findGenerationPoint(Structure.GenerationContext context);

    public abstract StructureType<?> type();

    public static record GenerationContext(RegistryAccess registryAccess, ChunkGenerator chunkGenerator, BiomeSource biomeSource, RandomState randomState, StructureTemplateManager structureTemplateManager, WorldgenRandom random, long seed, ChunkPos chunkPos, LevelHeightAccessor heightAccessor, Predicate<Holder<Biome>> validBiome) {
        public GenerationContext(RegistryAccess dynamicRegistryManager, ChunkGenerator chunkGenerator, BiomeSource biomeSource, RandomState noiseConfig, StructureTemplateManager structureTemplateManager, long seed, ChunkPos chunkPos, LevelHeightAccessor world, Predicate<Holder<Biome>> biomePredicate) {
            this(dynamicRegistryManager, chunkGenerator, biomeSource, noiseConfig, structureTemplateManager, makeRandom(seed, chunkPos), seed, chunkPos, world, biomePredicate);
        }

        private static WorldgenRandom makeRandom(long seed, ChunkPos chunkPos) {
            WorldgenRandom worldgenRandom = new WorldgenRandom(new LegacyRandomSource(0L));
            worldgenRandom.setLargeFeatureSeed(seed, chunkPos.x, chunkPos.z);
            return worldgenRandom;
        }
    }

    public static record GenerationStub(BlockPos position, Either<Consumer<StructurePiecesBuilder>, StructurePiecesBuilder> generator) {
        public GenerationStub(BlockPos pos, Consumer<StructurePiecesBuilder> generator) {
            this(pos, Either.left(generator));
        }

        public StructurePiecesBuilder getPiecesBuilder() {
            return this.generator.map((generator) -> {
                StructurePiecesBuilder structurePiecesBuilder = new StructurePiecesBuilder();
                generator.accept(structurePiecesBuilder);
                return structurePiecesBuilder;
            }, (collector) -> {
                return collector;
            });
        }
    }

    public static record StructureSettings(HolderSet<Biome> biomes, Map<MobCategory, StructureSpawnOverride> spawnOverrides, GenerationStep.Decoration step, TerrainAdjustment terrainAdaptation) {
        public static final MapCodec<Structure.StructureSettings> CODEC = RecordCodecBuilder.mapCodec((instance) -> {
            return instance.group(RegistryCodecs.homogeneousList(Registry.BIOME_REGISTRY).fieldOf("biomes").forGetter(Structure.StructureSettings::biomes), Codec.simpleMap(MobCategory.CODEC, StructureSpawnOverride.CODEC, StringRepresentable.keys(MobCategory.values())).fieldOf("spawn_overrides").forGetter(Structure.StructureSettings::spawnOverrides), GenerationStep.Decoration.CODEC.fieldOf("step").forGetter(Structure.StructureSettings::step), TerrainAdjustment.CODEC.optionalFieldOf("terrain_adaptation", TerrainAdjustment.NONE).forGetter(Structure.StructureSettings::terrainAdaptation)).apply(instance, Structure.StructureSettings::new);
        });
    }
}
