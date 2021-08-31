package net.minecraft.world.level.chunk;

import com.google.common.base.Stopwatch;
import com.google.common.base.Suppliers;
import com.mojang.datafixers.Products.P1;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder.Instance;
import com.mojang.serialization.codecs.RecordCodecBuilder.Mu;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.protocol.game.DebugPackets;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureSpawnOverride;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.slf4j.Logger;

public abstract class ChunkGenerator {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final Codec<ChunkGenerator> CODEC = Registry.CHUNK_GENERATOR.byNameCodec().dispatchStable(ChunkGenerator::codec, Function.identity());
    public final Registry<StructureSet> structureSets;
    protected final BiomeSource biomeSource;
    private final Supplier<List<FeatureSorter.StepFeatureData>> featuresPerStep;
    public final Optional<HolderSet<StructureSet>> structureOverrides;
    private final Function<Holder<Biome>, BiomeGenerationSettings> generationSettingsGetter;
    private final Map<Structure, List<StructurePlacement>> placementsForStructure;
    private final Map<ConcentricRingsStructurePlacement, CompletableFuture<List<ChunkPos>>> ringPositions;
    private boolean hasGeneratedPositions;
    public org.spigotmc.SpigotWorldConfig conf; // Spigot

    protected static <T extends ChunkGenerator> P1<Mu<T>, Registry<StructureSet>> commonCodec(Instance<T> instance) {
        return instance.group(RegistryOps.retrieveRegistry(Registry.STRUCTURE_SET_REGISTRY).forGetter((chunkgenerator) -> {
            return chunkgenerator.structureSets;
        }));
    }

    public ChunkGenerator(Registry<StructureSet> structureSetRegistry, Optional<HolderSet<StructureSet>> structureOverrides, BiomeSource biomeSource) {
        this(structureSetRegistry, structureOverrides, biomeSource, (holder) -> {
            return ((Biome) holder.value()).getGenerationSettings();
        });
    }

    public ChunkGenerator(Registry<StructureSet> structureSetRegistry, Optional<HolderSet<StructureSet>> structureOverrides, BiomeSource biomeSource, Function<Holder<Biome>, BiomeGenerationSettings> generationSettingsGetter) {
        this.placementsForStructure = new Object2ObjectOpenHashMap();
        this.ringPositions = new Object2ObjectArrayMap();
        this.structureSets = structureSetRegistry;
        this.biomeSource = biomeSource;
        this.generationSettingsGetter = generationSettingsGetter;
        this.structureOverrides = structureOverrides;
        this.featuresPerStep = Suppliers.memoize(() -> {
            return FeatureSorter.buildFeaturesPerStep(List.copyOf(biomeSource.possibleBiomes()), (holder) -> {
                return ((BiomeGenerationSettings) generationSettingsGetter.apply(holder)).features();
            }, true);
        });
    }

    public Stream<Holder<StructureSet>> possibleStructureSets() {
        return this.structureOverrides.isPresent() ? ((HolderSet) this.structureOverrides.get()).stream() : this.structureSets.holders().map(Holder::hackyErase);
    }

    // Spigot start
    private Stream<StructureSet> possibleStructureSetsSpigot() {
        return this.possibleStructureSets().map(Holder::value).map((structureset) -> {
            if (structureset.placement() instanceof RandomSpreadStructurePlacement randomConfig) {
                String name = this.structureSets.getKey(structureset).getPath();
                int seed = randomConfig.salt;

                switch (name) {
                    case "desert_pyramids":
                        seed = conf.desertSeed;
                        break;
                    case "end_cities":
                        seed = conf.endCitySeed;
                        break;
                    case "nether_complexes":
                        seed = conf.netherSeed;
                        break;
                    case "igloos":
                        seed = conf.iglooSeed;
                        break;
                    case "jungle_temples":
                        seed = conf.jungleSeed;
                        break;
                    case "woodland_mansions":
                        seed = conf.mansionSeed;
                        break;
                    case "ocean_monuments":
                        seed = conf.monumentSeed;
                        break;
                    case "nether_fossils":
                        seed = conf.fossilSeed;
                        break;
                    case "ocean_ruins":
                        seed = conf.oceanSeed;
                        break;
                    case "pillager_outposts":
                        seed = conf.outpostSeed;
                        break;
                    case "ruined_portals":
                        seed = conf.portalSeed;
                        break;
                    case "shipwrecks":
                        seed = conf.shipwreckSeed;
                        break;
                    case "swamp_huts":
                        seed = conf.swampSeed;
                        break;
                    case "villages":
                        seed = conf.villageSeed;
                        break;
                }

                structureset = new StructureSet(structureset.structures(), new RandomSpreadStructurePlacement(randomConfig.locateOffset, randomConfig.frequencyReductionMethod, randomConfig.frequency, seed, randomConfig.exclusionZone, randomConfig.spacing(), randomConfig.separation(), randomConfig.spreadType()));
            }
            return structureset;
        });
    }
    // Spigot end

    private void generatePositions(RandomState noiseConfig) {
        Set<Holder<Biome>> set = this.biomeSource.possibleBiomes();

        // Spigot start
        this.possibleStructureSetsSpigot().forEach((holder) -> {
            StructureSet structureset = (StructureSet) holder;
            // Spigot end
            boolean flag = false;
            Iterator iterator = structureset.structures().iterator();

            while (iterator.hasNext()) {
                StructureSet.StructureSelectionEntry structureset_a = (StructureSet.StructureSelectionEntry) iterator.next();
                Structure structure = (Structure) structureset_a.structure().value();
                Stream stream = structure.biomes().stream();

                Objects.requireNonNull(set);
                if (stream.anyMatch(set::contains)) {
                    ((List) this.placementsForStructure.computeIfAbsent(structure, (structure1) -> {
                        return new ArrayList();
                    })).add(structureset.placement());
                    flag = true;
                }
            }

            if (flag) {
                StructurePlacement structureplacement = structureset.placement();

                if (structureplacement instanceof ConcentricRingsStructurePlacement) {
                    ConcentricRingsStructurePlacement concentricringsstructureplacement = (ConcentricRingsStructurePlacement) structureplacement;

                    this.ringPositions.put(concentricringsstructureplacement, this.generateRingPositions(holder, noiseConfig, concentricringsstructureplacement));
                }
            }

        });
    }

    private CompletableFuture<List<ChunkPos>> generateRingPositions(StructureSet holder, RandomState randomstate, ConcentricRingsStructurePlacement concentricringsstructureplacement) { // Spigot
        return concentricringsstructureplacement.count() == 0 ? CompletableFuture.completedFuture(List.of()) : CompletableFuture.supplyAsync(Util.wrapThreadWithTaskName("placement calculation", () -> {
            Stopwatch stopwatch = Stopwatch.createStarted(Util.TICKER);
            List<ChunkPos> list = new ArrayList();
            int i = concentricringsstructureplacement.distance();
            int j = concentricringsstructureplacement.count();
            int k = concentricringsstructureplacement.spread();
            HolderSet<Biome> holderset = concentricringsstructureplacement.preferredBiomes();
            RandomSource randomsource = RandomSource.create();

            randomsource.setSeed(this instanceof FlatLevelSource ? 0L : randomstate.legacyLevelSeed());
            double d0 = randomsource.nextDouble() * 3.141592653589793D * 2.0D;
            int l = 0;
            int i1 = 0;

            for (int j1 = 0; j1 < j; ++j1) {
                double d1 = (double) (4 * i + i * i1 * 6) + (randomsource.nextDouble() - 0.5D) * (double) i * 2.5D;
                int k1 = (int) Math.round(Math.cos(d0) * d1);
                int l1 = (int) Math.round(Math.sin(d0) * d1);
                BiomeSource worldchunkmanager = this.biomeSource;
                int i2 = SectionPos.sectionToBlockCoord(k1, 8);
                int j2 = SectionPos.sectionToBlockCoord(l1, 8);

                Objects.requireNonNull(holderset);
                Pair<BlockPos, Holder<Biome>> pair = worldchunkmanager.findBiomeHorizontal(i2, 0, j2, 112, holderset::contains, randomsource, randomstate.sampler());

                if (pair != null) {
                    BlockPos blockposition = (BlockPos) pair.getFirst();

                    k1 = SectionPos.blockToSectionCoord(blockposition.getX());
                    l1 = SectionPos.blockToSectionCoord(blockposition.getZ());
                }

                list.add(new ChunkPos(k1, l1));
                d0 += 6.283185307179586D / (double) k;
                ++l;
                if (l == k) {
                    ++i1;
                    l = 0;
                    k += 2 * k / (i1 + 1);
                    k = Math.min(k, j - j1);
                    d0 += randomsource.nextDouble() * 3.141592653589793D * 2.0D;
                }
            }

            double d2 = (double) stopwatch.stop().elapsed(TimeUnit.MILLISECONDS) / 1000.0D;

            ChunkGenerator.LOGGER.debug("Calculation for {} took {}s", holder, d2);
            return list;
        }), Util.backgroundExecutor());
    }

    protected abstract Codec<? extends ChunkGenerator> codec();

    public Optional<ResourceKey<Codec<? extends ChunkGenerator>>> getTypeNameForDataFixer() {
        return Registry.CHUNK_GENERATOR.getResourceKey(this.codec());
    }

    public CompletableFuture<ChunkAccess> createBiomes(Registry<Biome> biomeRegistry, Executor executor, RandomState noiseConfig, Blender blender, StructureManager structureAccessor, ChunkAccess chunk) {
        return CompletableFuture.supplyAsync(Util.wrapThreadWithTaskName("init_biomes", () -> {
            chunk.fillBiomesFromNoise(this.biomeSource, noiseConfig.sampler());
            return chunk;
        }), Util.backgroundExecutor());
    }

    public abstract void applyCarvers(WorldGenRegion chunkRegion, long seed, RandomState noiseConfig, BiomeManager biomeAccess, StructureManager structureAccessor, ChunkAccess chunk, GenerationStep.Carving carverStep);

    @Nullable
    public Pair<BlockPos, Holder<Structure>> findNearestMapStructure(ServerLevel world, HolderSet<Structure> structures, BlockPos center, int radius, boolean skipReferencedStructures) {
        // Paper start - StructureLocateEvent
        final org.bukkit.World bukkitWorld = world.getWorld();
        final org.bukkit.Location origin = net.minecraft.server.MCUtil.toLocation(world, center);
        final var paperRegistry = io.papermc.paper.registry.PaperRegistry.getRegistry(io.papermc.paper.registry.RegistryKey.CONFIGURED_STRUCTURE_REGISTRY);
        final List<io.papermc.paper.world.structure.ConfiguredStructure> configuredStructures = new ArrayList<>();
        paperRegistry.convertToApi(structures, configuredStructures::add, false); // gracefully handle missing api, use tests to check (or exclude)
        if (!configuredStructures.isEmpty()) {
            final io.papermc.paper.event.world.StructuresLocateEvent event = new io.papermc.paper.event.world.StructuresLocateEvent(bukkitWorld, origin, configuredStructures, radius, skipReferencedStructures);
            if (!event.callEvent()) {
                return null;
            }
            if (event.getResult() != null) {
                return Pair.of(net.minecraft.server.MCUtil.toBlockPosition(event.getResult().position()), paperRegistry.getMinecraftHolder(event.getResult().configuredStructure()));
            }
            center = net.minecraft.server.MCUtil.toBlockPosition(event.getOrigin());
            radius = event.getRadius();
            skipReferencedStructures = event.shouldFindUnexplored();
            structures = HolderSet.direct(paperRegistry::getMinecraftHolder, event.getConfiguredStructures());
        }
        // Paper end
        Map<StructurePlacement, Set<Holder<Structure>>> map = new Object2ObjectArrayMap();
        Iterator iterator = structures.iterator();

        while (iterator.hasNext()) {
            Holder<Structure> holder = (Holder) iterator.next();
            Iterator iterator1 = this.getPlacementsForStructure(holder, world.getChunkSource().randomState()).iterator();

            while (iterator1.hasNext()) {
                StructurePlacement structureplacement = (StructurePlacement) iterator1.next();

                ((Set) map.computeIfAbsent(structureplacement, (structureplacement1) -> {
                    return new ObjectArraySet();
                })).add(holder);
            }
        }

        if (map.isEmpty()) {
            return null;
        } else {
            Pair<BlockPos, Holder<Structure>> pair = null;
            double d0 = Double.MAX_VALUE;
            StructureManager structuremanager = world.structureManager();
            List<Entry<StructurePlacement, Set<Holder<Structure>>>> list = new ArrayList(map.size());
            Iterator iterator2 = map.entrySet().iterator();

            while (iterator2.hasNext()) {
                Entry<StructurePlacement, Set<Holder<Structure>>> entry = (Entry) iterator2.next();
                StructurePlacement structureplacement1 = (StructurePlacement) entry.getKey();

                if (structureplacement1 instanceof ConcentricRingsStructurePlacement) {
                    ConcentricRingsStructurePlacement concentricringsstructureplacement = (ConcentricRingsStructurePlacement) structureplacement1;
                    Pair<BlockPos, Holder<Structure>> pair1 = this.getNearestGeneratedStructure((Set) entry.getValue(), world, structuremanager, center, skipReferencedStructures, concentricringsstructureplacement);

                    if (pair1 != null) {
                        BlockPos blockposition1 = (BlockPos) pair1.getFirst();
                        double d1 = center.distSqr(blockposition1);

                        if (d1 < d0) {
                            d0 = d1;
                            pair = pair1;
                        }
                    }
                } else if (structureplacement1 instanceof RandomSpreadStructurePlacement) {
                    list.add(entry);
                }
            }

            if (!list.isEmpty()) {
                int j = SectionPos.blockToSectionCoord(center.getX());
                int k = SectionPos.blockToSectionCoord(center.getZ());

                for (int l = 0; l <= radius; ++l) {
                    boolean flag1 = false;
                    Iterator iterator3 = list.iterator();

                    while (iterator3.hasNext()) {
                        Entry<StructurePlacement, Set<Holder<Structure>>> entry1 = (Entry) iterator3.next();
                        RandomSpreadStructurePlacement randomspreadstructureplacement = (RandomSpreadStructurePlacement) entry1.getKey();
                        Pair<BlockPos, Holder<Structure>> pair2 = ChunkGenerator.getNearestGeneratedStructure((Set) entry1.getValue(), world, structuremanager, j, k, l, skipReferencedStructures, world.getSeed(), randomspreadstructureplacement);

                        if (pair2 != null) {
                            flag1 = true;
                            double d2 = center.distSqr((Vec3i) pair2.getFirst());

                            if (d2 < d0) {
                                d0 = d2;
                                pair = pair2;
                            }
                        }
                    }

                    if (flag1) {
                        return pair;
                    }
                }
            }

            return pair;
        }
    }

    @Nullable
    private Pair<BlockPos, Holder<Structure>> getNearestGeneratedStructure(Set<Holder<Structure>> structures, ServerLevel world, StructureManager structureAccessor, BlockPos center, boolean skipReferencedStructures, ConcentricRingsStructurePlacement placement) {
        List<ChunkPos> list = this.getRingPositionsFor(placement, world.getChunkSource().randomState());

        if (list == null) {
            throw new IllegalStateException("Somehow tried to find structures for a placement that doesn't exist");
        } else {
            Pair<BlockPos, Holder<Structure>> pair = null;
            double d0 = Double.MAX_VALUE;
            BlockPos.MutableBlockPos blockposition_mutableblockposition = new BlockPos.MutableBlockPos();
            Iterator iterator = list.iterator();

            while (iterator.hasNext()) {
                ChunkPos chunkcoordintpair = (ChunkPos) iterator.next();
                if (!world.getWorldBorder().isChunkInBounds(chunkcoordintpair.x, chunkcoordintpair.z)) { continue; } // Paper

                blockposition_mutableblockposition.set(SectionPos.sectionToBlockCoord(chunkcoordintpair.x, 8), 32, SectionPos.sectionToBlockCoord(chunkcoordintpair.z, 8));
                double d1 = blockposition_mutableblockposition.distSqr(center);
                boolean flag1 = pair == null || d1 < d0;

                if (flag1) {
                    Pair<BlockPos, Holder<Structure>> pair1 = ChunkGenerator.getStructureGeneratingAt(structures, world, structureAccessor, skipReferencedStructures, placement, chunkcoordintpair);

                    if (pair1 != null) {
                        pair = pair1;
                        d0 = d1;
                    }
                }
            }

            return pair;
        }
    }

    @Nullable
    private static Pair<BlockPos, Holder<Structure>> getNearestGeneratedStructure(Set<Holder<Structure>> structures, LevelReader world, StructureManager structureAccessor, int centerChunkX, int centerChunkZ, int radius, boolean skipReferencedStructures, long seed, RandomSpreadStructurePlacement placement) {
        int i1 = placement.spacing();

        for (int j1 = -radius; j1 <= radius; ++j1) {
            boolean flag1 = j1 == -radius || j1 == radius;

            for (int k1 = -radius; k1 <= radius; ++k1) {
                boolean flag2 = k1 == -radius || k1 == radius;

                if (flag1 || flag2) {
                    int l1 = centerChunkX + i1 * j1;
                    int i2 = centerChunkZ + i1 * k1;
                    ChunkPos chunkcoordintpair = placement.getPotentialStructureChunk(seed, l1, i2);
                    Pair<BlockPos, Holder<Structure>> pair = ChunkGenerator.getStructureGeneratingAt(structures, world, structureAccessor, skipReferencedStructures, placement, chunkcoordintpair);

                    if (pair != null) {
                        return pair;
                    }
                }
            }
        }

        return null;
    }

    @Nullable
    private static Pair<BlockPos, Holder<Structure>> getStructureGeneratingAt(Set<Holder<Structure>> structures, LevelReader world, StructureManager structureAccessor, boolean skipReferencedStructures, StructurePlacement placement, ChunkPos pos) {
        Iterator iterator = structures.iterator();

        Holder holder;
        StructureStart structurestart;

        do {
            do {
                do {
                    StructureCheckResult structurecheckresult;

                    do {
                        if (!iterator.hasNext()) {
                            return null;
                        }

                        holder = (Holder) iterator.next();
                        structurecheckresult = structureAccessor.checkStructurePresence(pos, (Structure) holder.value(), skipReferencedStructures);
                    } while (structurecheckresult == StructureCheckResult.START_NOT_PRESENT);

                    if (!skipReferencedStructures && structurecheckresult == StructureCheckResult.START_PRESENT) {
                        return Pair.of(placement.getLocatePos(pos), holder);
                    }

                    ChunkAccess ichunkaccess = world.getChunk(pos.x, pos.z, ChunkStatus.STRUCTURE_STARTS);

                    structurestart = structureAccessor.getStartForStructure(SectionPos.bottomOf(ichunkaccess), (Structure) holder.value(), ichunkaccess);
                } while (structurestart == null);
            } while (!structurestart.isValid());
        } while (skipReferencedStructures && !ChunkGenerator.tryAddReference(structureAccessor, structurestart));

        return Pair.of(placement.getLocatePos(structurestart.getChunkPos()), holder);
    }

    private static boolean tryAddReference(StructureManager structureAccessor, StructureStart start) {
        if (start.canBeReferenced()) {
            structureAccessor.addReference(start);
            return true;
        } else {
            return false;
        }
    }

    public void addVanillaDecorations(WorldGenLevel generatoraccessseed, ChunkAccess ichunkaccess, StructureManager structuremanager) { // CraftBukkit
        ChunkPos chunkcoordintpair = ichunkaccess.getPos();

        if (!SharedConstants.debugVoidTerrain(chunkcoordintpair)) {
            SectionPos sectionposition = SectionPos.of(chunkcoordintpair, generatoraccessseed.getMinSection());
            BlockPos blockposition = sectionposition.origin();
            Registry<Structure> iregistry = generatoraccessseed.registryAccess().registryOrThrow(Registry.STRUCTURE_REGISTRY);
            Map<Integer, List<Structure>> map = (Map) iregistry.stream().collect(Collectors.groupingBy((structure) -> {
                return structure.step().ordinal();
            }));
            List<FeatureSorter.StepFeatureData> list = (List) this.featuresPerStep.get();
            WorldgenRandom seededrandom = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
            long i = seededrandom.setDecorationSeed(generatoraccessseed.getSeed(), blockposition.getX(), blockposition.getZ());
            Set<Holder<Biome>> set = new ObjectArraySet();

            ChunkPos.rangeClosed(sectionposition.chunk(), 1).forEach((chunkcoordintpair1) -> {
                ChunkAccess ichunkaccess1 = generatoraccessseed.getChunk(chunkcoordintpair1.x, chunkcoordintpair1.z);
                LevelChunkSection[] achunksection = ichunkaccess1.getSections();
                int j = achunksection.length;

                for (int k = 0; k < j; ++k) {
                    LevelChunkSection chunksection = achunksection[k];
                    PalettedContainerRO<Holder<Biome>> palettedcontainerro = chunksection.getBiomes(); // CraftBukkit - decompile error

                    Objects.requireNonNull(set);
                    palettedcontainerro.getAll(set::add);
                }

            });
            set.retainAll(this.biomeSource.possibleBiomes());
            int j = list.size();

            try {
                Registry<PlacedFeature> iregistry1 = generatoraccessseed.registryAccess().registryOrThrow(Registry.PLACED_FEATURE_REGISTRY);
                int k = Math.max(GenerationStep.Decoration.values().length, j);

                for (int l = 0; l < k; ++l) {
                    int i1 = 0;
                    Iterator iterator;
                    CrashReportCategory crashreportsystemdetails;

                    if (structuremanager.shouldGenerateStructures()) {
                        List<Structure> list1 = (List) map.getOrDefault(l, Collections.emptyList());

                        for (iterator = list1.iterator(); iterator.hasNext(); ++i1) {
                            Structure structure = (Structure) iterator.next();

                            seededrandom.setFeatureSeed(i, i1, l);
                            Supplier<String> supplier = () -> { // CraftBukkit - decompile error
                                Optional optional = iregistry.getResourceKey(structure).map(Object::toString);

                                Objects.requireNonNull(structure);
                                return (String) optional.orElseGet(structure::toString);
                            };

                            try {
                                generatoraccessseed.setCurrentlyGenerating(supplier);
                                structuremanager.startsForStructure(sectionposition, structure).forEach((structurestart) -> {
                                    structurestart.placeInChunk(generatoraccessseed, structuremanager, this, seededrandom, ChunkGenerator.getWritableArea(ichunkaccess), chunkcoordintpair);
                                });
                            } catch (Exception exception) {
                                CrashReport crashreport = CrashReport.forThrowable(exception, "Feature placement");

                                crashreportsystemdetails = crashreport.addCategory("Feature");
                                Objects.requireNonNull(supplier);
                                crashreportsystemdetails.setDetail("Description", supplier::get);
                                throw new ReportedException(crashreport);
                            }
                        }
                    }

                    if (l < j) {
                        IntArraySet intarrayset = new IntArraySet();

                        iterator = set.iterator();

                        while (iterator.hasNext()) {
                            Holder<Biome> holder = (Holder) iterator.next();
                            List<HolderSet<PlacedFeature>> list2 = ((BiomeGenerationSettings) this.generationSettingsGetter.apply(holder)).features();

                            if (l < list2.size()) {
                                HolderSet<PlacedFeature> holderset = (HolderSet) list2.get(l);
                                FeatureSorter.StepFeatureData featuresorter_b = (FeatureSorter.StepFeatureData) list.get(l);

                                holderset.stream().map(Holder::value).forEach((placedfeature) -> {
                                    intarrayset.add(featuresorter_b.indexMapping().applyAsInt(placedfeature));
                                });
                            }
                        }

                        int j1 = intarrayset.size();
                        int[] aint = intarrayset.toIntArray();

                        Arrays.sort(aint);
                        FeatureSorter.StepFeatureData featuresorter_b1 = (FeatureSorter.StepFeatureData) list.get(l);

                        for (int k1 = 0; k1 < j1; ++k1) {
                            int l1 = aint[k1];
                            PlacedFeature placedfeature = (PlacedFeature) featuresorter_b1.features().get(l1);
                            Supplier<String> supplier1 = () -> {
                                Optional optional = iregistry1.getResourceKey(placedfeature).map(Object::toString);

                                Objects.requireNonNull(placedfeature);
                                return (String) optional.orElseGet(placedfeature::toString);
                            };

                            // Paper start - change populationSeed used in random
                            long featurePopulationSeed = i;
                            final long configFeatureSeed = generatoraccessseed.getMinecraftWorld().paperConfig().featureSeeds.features.getLong(placedfeature.feature());
                            if (configFeatureSeed != -1) {
                                featurePopulationSeed = seededrandom.setDecorationSeed(configFeatureSeed, blockposition.getX(), blockposition.getZ()); // See seededrandom.setDecorationSeed from above
                            }
                            seededrandom.setFeatureSeed(featurePopulationSeed, l1, l);
                            // Paper end

                            try {
                                generatoraccessseed.setCurrentlyGenerating(supplier1);
                                placedfeature.placeWithBiomeCheck(generatoraccessseed, this, seededrandom, blockposition);
                            } catch (Exception exception1) {
                                CrashReport crashreport1 = CrashReport.forThrowable(exception1, "Feature placement");

                                crashreportsystemdetails = crashreport1.addCategory("Feature");
                                Objects.requireNonNull(supplier1);
                                crashreportsystemdetails.setDetail("Description", supplier1::get);
                                throw new ReportedException(crashreport1);
                            }
                        }
                    }
                }

                generatoraccessseed.setCurrentlyGenerating((Supplier) null);
            } catch (Exception exception2) {
                CrashReport crashreport2 = CrashReport.forThrowable(exception2, "Biome decoration");

                crashreport2.addCategory("Generation").setDetail("CenterX", (Object) chunkcoordintpair.x).setDetail("CenterZ", (Object) chunkcoordintpair.z).setDetail("Seed", (Object) i);
                throw new ReportedException(crashreport2);
            }
        }
    }

    public void applyBiomeDecoration(WorldGenLevel world, ChunkAccess chunk, StructureManager structureAccessor) {
        // CraftBukkit start
        this.applyBiomeDecoration(world, chunk, structureAccessor, true);
    }

    public void applyBiomeDecoration(WorldGenLevel generatoraccessseed, ChunkAccess ichunkaccess, StructureManager structuremanager, boolean vanilla) {
        if (vanilla) {
            this.addVanillaDecorations(generatoraccessseed, ichunkaccess, structuremanager);
        }

        org.bukkit.World world = generatoraccessseed.getMinecraftWorld().getWorld();
        // only call when a populator is present (prevents unnecessary entity conversion)
        if (!world.getPopulators().isEmpty()) {
            org.bukkit.craftbukkit.generator.CraftLimitedRegion limitedRegion = new org.bukkit.craftbukkit.generator.CraftLimitedRegion(generatoraccessseed, ichunkaccess.getPos());
            int x = ichunkaccess.getPos().x;
            int z = ichunkaccess.getPos().z;
            for (org.bukkit.generator.BlockPopulator populator : world.getPopulators()) {
                WorldgenRandom seededrandom = new WorldgenRandom(new net.minecraft.world.level.levelgen.LegacyRandomSource(generatoraccessseed.getSeed()));
                seededrandom.setDecorationSeed(generatoraccessseed.getSeed(), x, z);
                populator.populate(world, new org.bukkit.craftbukkit.util.RandomSourceWrapper.RandomWrapper(seededrandom), x, z, limitedRegion);
            }
            limitedRegion.saveEntities();
            limitedRegion.breakLink();
        }
        // CraftBukkit end
    }

    public boolean hasStructureChunkInRange(Holder<StructureSet> structureSet, RandomState noiseConfig, long seed, int chunkX, int chunkZ, int chunkRange) {
        StructureSet structureset = (StructureSet) structureSet.value();

        if (structureset == null) {
            return false;
        } else {
            StructurePlacement structureplacement = structureset.placement();

            for (int i1 = chunkX - chunkRange; i1 <= chunkX + chunkRange; ++i1) {
                for (int j1 = chunkZ - chunkRange; j1 <= chunkZ + chunkRange; ++j1) {
                    if (structureplacement.isStructureChunk(this, noiseConfig, seed, i1, j1)) {
                        return true;
                    }
                }
            }

            return false;
        }
    }

    private static BoundingBox getWritableArea(ChunkAccess chunk) {
        ChunkPos chunkcoordintpair = chunk.getPos();
        int i = chunkcoordintpair.getMinBlockX();
        int j = chunkcoordintpair.getMinBlockZ();
        LevelHeightAccessor levelheightaccessor = chunk.getHeightAccessorForGeneration();
        int k = levelheightaccessor.getMinBuildHeight() + 1;
        int l = levelheightaccessor.getMaxBuildHeight() - 1;

        return new BoundingBox(i, k, j, i + 15, l, j + 15);
    }

    public abstract void buildSurface(WorldGenRegion region, StructureManager structures, RandomState noiseConfig, ChunkAccess chunk);

    public abstract void spawnOriginalMobs(WorldGenRegion region);

    public int getSpawnHeight(LevelHeightAccessor world) {
        return 64;
    }

    public BiomeSource getBiomeSource() {
        return this.biomeSource;
    }

    public abstract int getGenDepth();

    public WeightedRandomList<MobSpawnSettings.SpawnerData> getMobsAt(Holder<Biome> biome, StructureManager accessor, MobCategory group, BlockPos pos) {
        Map<Structure, LongSet> map = accessor.getAllStructuresAt(pos);
        Iterator iterator = map.entrySet().iterator();

        while (iterator.hasNext()) {
            Entry<Structure, LongSet> entry = (Entry) iterator.next();
            Structure structure = (Structure) entry.getKey();
            StructureSpawnOverride structurespawnoverride = (StructureSpawnOverride) structure.spawnOverrides().get(group);

            if (structurespawnoverride != null) {
                MutableBoolean mutableboolean = new MutableBoolean(false);
                Predicate<StructureStart> predicate = structurespawnoverride.boundingBox() == StructureSpawnOverride.BoundingBoxType.PIECE ? (structurestart) -> {
                    return accessor.structureHasPieceAt(pos, structurestart);
                } : (structurestart) -> {
                    return structurestart.getBoundingBox().isInside(pos);
                };

                accessor.fillStartsForStructure(structure, (LongSet) entry.getValue(), (structurestart) -> {
                    if (mutableboolean.isFalse() && predicate.test(structurestart)) {
                        mutableboolean.setTrue();
                    }

                });
                if (mutableboolean.isTrue()) {
                    return structurespawnoverride.spawns();
                }
            }
        }

        return ((Biome) biome.value()).getMobSettings().getMobs(group);
    }

    public void createStructures(RegistryAccess registryManager, RandomState noiseConfig, StructureManager structureAccessor, ChunkAccess chunk, StructureTemplateManager structureTemplateManager, long seed) {
        ChunkPos chunkcoordintpair = chunk.getPos();
        SectionPos sectionposition = SectionPos.bottomOf(chunk);

        // Spigot start
        this.possibleStructureSetsSpigot().forEach((holder) -> {
            StructurePlacement structureplacement = ((StructureSet) holder).placement();
            List<StructureSet.StructureSelectionEntry> list = ((StructureSet) holder).structures();
            // Spigot end
            Iterator iterator = list.iterator();

            while (iterator.hasNext()) {
                StructureSet.StructureSelectionEntry structureset_a = (StructureSet.StructureSelectionEntry) iterator.next();
                StructureStart structurestart = structureAccessor.getStartForStructure(sectionposition, (Structure) structureset_a.structure().value(), chunk);

                if (structurestart != null && structurestart.isValid()) {
                    return;
                }
            }

            if (structureplacement.isStructureChunk(this, noiseConfig, seed, chunkcoordintpair.x, chunkcoordintpair.z)) {
                if (list.size() == 1) {
                    this.tryGenerateStructure((StructureSet.StructureSelectionEntry) list.get(0), structureAccessor, registryManager, noiseConfig, structureTemplateManager, seed, chunk, chunkcoordintpair, sectionposition);
                } else {
                    ArrayList<StructureSet.StructureSelectionEntry> arraylist = new ArrayList(list.size());

                    arraylist.addAll(list);
                    WorldgenRandom seededrandom = new WorldgenRandom(new LegacyRandomSource(0L));

                    seededrandom.setLargeFeatureSeed(seed, chunkcoordintpair.x, chunkcoordintpair.z);
                    int j = 0;

                    StructureSet.StructureSelectionEntry structureset_a1;

                    for (Iterator iterator1 = arraylist.iterator(); iterator1.hasNext(); j += structureset_a1.weight()) {
                        structureset_a1 = (StructureSet.StructureSelectionEntry) iterator1.next();
                    }

                    while (!arraylist.isEmpty()) {
                        int k = seededrandom.nextInt(j);
                        int l = 0;
                        Iterator iterator2 = arraylist.iterator();

                        while (true) {
                            if (iterator2.hasNext()) {
                                StructureSet.StructureSelectionEntry structureset_a2 = (StructureSet.StructureSelectionEntry) iterator2.next();

                                k -= structureset_a2.weight();
                                if (k >= 0) {
                                    ++l;
                                    continue;
                                }
                            }

                            StructureSet.StructureSelectionEntry structureset_a3 = (StructureSet.StructureSelectionEntry) arraylist.get(l);

                            if (this.tryGenerateStructure(structureset_a3, structureAccessor, registryManager, noiseConfig, structureTemplateManager, seed, chunk, chunkcoordintpair, sectionposition)) {
                                return;
                            }

                            arraylist.remove(l);
                            j -= structureset_a3.weight();
                            break;
                        }
                    }

                }
            }
        });
    }

    private boolean tryGenerateStructure(StructureSet.StructureSelectionEntry weightedEntry, StructureManager structureAccessor, RegistryAccess dynamicRegistryManager, RandomState noiseConfig, StructureTemplateManager structureManager, long seed, ChunkAccess chunk, ChunkPos pos, SectionPos sectionPos) {
        Structure structure = (Structure) weightedEntry.structure().value();
        int j = ChunkGenerator.fetchReferences(structureAccessor, chunk, sectionPos, structure);
        HolderSet<Biome> holderset = structure.biomes();

        Objects.requireNonNull(holderset);
        Predicate<Holder<Biome>> predicate = holderset::contains;
        StructureStart structurestart = structure.generate(dynamicRegistryManager, this, this.biomeSource, noiseConfig, structureManager, seed, pos, j, chunk, predicate);

        if (structurestart.isValid()) {
            structureAccessor.setStartForStructure(sectionPos, structure, structurestart, chunk);
            return true;
        } else {
            return false;
        }
    }

    private static int fetchReferences(StructureManager structureAccessor, ChunkAccess chunk, SectionPos sectionPos, Structure structure) {
        StructureStart structurestart = structureAccessor.getStartForStructure(sectionPos, structure, chunk);

        return structurestart != null ? structurestart.getReferences() : 0;
    }

    public void createReferences(WorldGenLevel world, StructureManager structureAccessor, ChunkAccess chunk) {
        boolean flag = true;
        ChunkPos chunkcoordintpair = chunk.getPos();
        int i = chunkcoordintpair.x;
        int j = chunkcoordintpair.z;
        int k = chunkcoordintpair.getMinBlockX();
        int l = chunkcoordintpair.getMinBlockZ();
        SectionPos sectionposition = SectionPos.bottomOf(chunk);

        for (int i1 = i - 8; i1 <= i + 8; ++i1) {
            for (int j1 = j - 8; j1 <= j + 8; ++j1) {
                long k1 = ChunkPos.asLong(i1, j1);
                Iterator iterator = world.getChunk(i1, j1).getAllStarts().values().iterator();

                while (iterator.hasNext()) {
                    StructureStart structurestart = (StructureStart) iterator.next();

                    try {
                        if (structurestart.isValid() && structurestart.getBoundingBox().intersects(k, l, k + 15, l + 15)) {
                            structureAccessor.addReferenceForStructure(sectionposition, structurestart.getStructure(), k1, chunk);
                            DebugPackets.sendStructurePacket(world, structurestart);
                        }
                    } catch (Exception exception) {
                        CrashReport crashreport = CrashReport.forThrowable(exception, "Generating structure reference");
                        CrashReportCategory crashreportsystemdetails = crashreport.addCategory("Structure");
                        Optional<? extends Registry<Structure>> optional = world.registryAccess().registry(Registry.STRUCTURE_REGISTRY);

                        crashreportsystemdetails.setDetail("Id", () -> {
                            return (String) optional.map((iregistry) -> {
                                return iregistry.getKey(structurestart.getStructure()).toString();
                            }).orElse("UNKNOWN");
                        });
                        crashreportsystemdetails.setDetail("Name", () -> {
                            return Registry.STRUCTURE_TYPES.getKey(structurestart.getStructure().type()).toString();
                        });
                        crashreportsystemdetails.setDetail("Class", () -> {
                            return structurestart.getStructure().getClass().getCanonicalName();
                        });
                        throw new ReportedException(crashreport);
                    }
                }
            }
        }

    }

    public abstract CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, RandomState noiseConfig, StructureManager structureAccessor, ChunkAccess chunk);

    public abstract int getSeaLevel();

    public abstract int getMinY();

    public abstract int getBaseHeight(int x, int z, Heightmap.Types heightmap, LevelHeightAccessor world, RandomState noiseConfig);

    public abstract net.minecraft.world.level.NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor world, RandomState noiseConfig);

    public int getFirstFreeHeight(int x, int z, Heightmap.Types heightmap, LevelHeightAccessor world, RandomState noiseConfig) {
        return this.getBaseHeight(x, z, heightmap, world, noiseConfig);
    }

    public int getFirstOccupiedHeight(int x, int z, Heightmap.Types heightmap, LevelHeightAccessor world, RandomState noiseConfig) {
        return this.getBaseHeight(x, z, heightmap, world, noiseConfig) - 1;
    }

    public void ensureStructuresGenerated(RandomState noiseConfig) {
        if (!this.hasGeneratedPositions) {
            this.generatePositions(noiseConfig);
            this.hasGeneratedPositions = true;
        }

    }

    @Nullable
    public List<ChunkPos> getRingPositionsFor(ConcentricRingsStructurePlacement structurePlacement, RandomState noiseConfig) {
        this.ensureStructuresGenerated(noiseConfig);
        CompletableFuture<List<ChunkPos>> completablefuture = (CompletableFuture) this.ringPositions.get(structurePlacement);

        return completablefuture != null ? (List) completablefuture.join() : null;
    }

    private List<StructurePlacement> getPlacementsForStructure(Holder<Structure> structureEntry, RandomState noiseConfig) {
        this.ensureStructuresGenerated(noiseConfig);
        return (List) this.placementsForStructure.getOrDefault(structureEntry.value(), List.of());
    }

    public abstract void addDebugScreenInfo(List<String> text, RandomState noiseConfig, BlockPos pos);

    /** @deprecated */
    @Deprecated
    public BiomeGenerationSettings getBiomeGenerationSettings(Holder<Biome> biomeEntry) {
        return (BiomeGenerationSettings) this.generationSettingsGetter.apply(biomeEntry);
    }
}
