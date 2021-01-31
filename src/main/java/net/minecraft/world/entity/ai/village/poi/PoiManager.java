package net.minecraft.world.entity.ai.village.poi;

import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.SectionTracker;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.SectionStorage;

public class PoiManager extends SectionStorage<PoiSection> {
    public static final int MAX_VILLAGE_DISTANCE = 6;
    public static final int VILLAGE_SECTION_SIZE = 1;
    private final PoiManager.DistanceTracker distanceTracker;
    private final LongSet loadedChunks = new LongOpenHashSet();
    public final net.minecraft.server.level.ServerLevel world; // Paper // Paper public

    public PoiManager(Path path, DataFixer dataFixer, boolean dsync, RegistryAccess registryManager, LevelHeightAccessor world) {
        super(path, PoiSection::codec, PoiSection::new, dataFixer, DataFixTypes.POI_CHUNK, dsync, registryManager, world);
        this.world = (net.minecraft.server.level.ServerLevel)world; // Paper
        this.distanceTracker = new PoiManager.DistanceTracker();
    }

    public void add(BlockPos pos, Holder<PoiType> type) {
        this.getOrCreate(SectionPos.asLong(pos)).add(pos, type);
    }

    public void remove(BlockPos pos) {
        this.getOrLoad(SectionPos.asLong(pos)).ifPresent((poiSet) -> {
            poiSet.remove(pos);
        });
    }

    public long getCountInRange(Predicate<Holder<PoiType>> typePredicate, BlockPos pos, int radius, PoiManager.Occupancy occupationStatus) {
        return this.getInRange(typePredicate, pos, radius, occupationStatus).count();
    }

    public boolean existsAtPosition(ResourceKey<PoiType> type, BlockPos pos) {
        return this.exists(pos, (entry) -> {
            return entry.is(type);
        });
    }

    public Stream<PoiRecord> getInSquare(Predicate<Holder<PoiType>> typePredicate, BlockPos pos, int radius, PoiManager.Occupancy occupationStatus) {
        int i = Math.floorDiv(radius, 16) + 1;
        return ChunkPos.rangeClosed(new ChunkPos(pos), i).flatMap((chunkPos) -> {
            return this.getInChunk(typePredicate, chunkPos, occupationStatus);
        }).filter((poi) -> {
            BlockPos blockPos2 = poi.getPos();
            return Math.abs(blockPos2.getX() - pos.getX()) <= radius && Math.abs(blockPos2.getZ() - pos.getZ()) <= radius;
        });
    }

    public Stream<PoiRecord> getInRange(Predicate<Holder<PoiType>> typePredicate, BlockPos pos, int radius, PoiManager.Occupancy occupationStatus) {
        int i = radius * radius;
        return this.getInSquare(typePredicate, pos, radius, occupationStatus).filter((poi) -> {
            return poi.getPos().distSqr(pos) <= (double)i;
        });
    }

    @VisibleForDebug
    public Stream<PoiRecord> getInChunk(Predicate<Holder<PoiType>> typePredicate, ChunkPos chunkPos, PoiManager.Occupancy occupationStatus) {
        return IntStream.range(this.levelHeightAccessor.getMinSection(), this.levelHeightAccessor.getMaxSection()).boxed().map((integer) -> {
            return this.getOrLoad(SectionPos.of(chunkPos, integer).asLong());
        }).filter(Optional::isPresent).flatMap((optional) -> {
            return optional.get().getRecords(typePredicate, occupationStatus);
        });
    }

    public Stream<BlockPos> findAll(Predicate<Holder<PoiType>> typePredicate, Predicate<BlockPos> posPredicate, BlockPos pos, int radius, PoiManager.Occupancy occupationStatus) {
        return this.getInRange(typePredicate, pos, radius, occupationStatus).map(PoiRecord::getPos).filter(posPredicate);
    }

    public Stream<Pair<Holder<PoiType>, BlockPos>> findAllWithType(Predicate<Holder<PoiType>> typePredicate, Predicate<BlockPos> posPredicate, BlockPos pos, int radius, PoiManager.Occupancy occupationStatus) {
        return this.getInRange(typePredicate, pos, radius, occupationStatus).filter((poi) -> {
            return posPredicate.test(poi.getPos());
        }).map((poi) -> {
            return Pair.of(poi.getPoiType(), poi.getPos());
        });
    }

    public Stream<Pair<Holder<PoiType>, BlockPos>> findAllClosestFirstWithType(Predicate<Holder<PoiType>> typePredicate, Predicate<BlockPos> posPredicate, BlockPos pos, int radius, PoiManager.Occupancy occupationStatus) {
        return this.findAllWithType(typePredicate, posPredicate, pos, radius, occupationStatus).sorted(Comparator.comparingDouble((pair) -> {
            return pair.getSecond().distSqr(pos);
        }));
    }

    public Optional<BlockPos> find(Predicate<Holder<PoiType>> typePredicate, Predicate<BlockPos> posPredicate, BlockPos pos, int radius, PoiManager.Occupancy occupationStatus) {
        // Paper start - re-route to faster logic
        BlockPos ret = io.papermc.paper.util.PoiAccess.findAnyPoiPosition(this, typePredicate, posPredicate, pos, radius, occupationStatus, false);
        return Optional.ofNullable(ret);
        // Paper end
    }

    public Optional<BlockPos> findClosest(Predicate<Holder<PoiType>> typePredicate, BlockPos pos, int radius, PoiManager.Occupancy occupationStatus) {
        // Paper start - re-route to faster logic
        BlockPos ret = io.papermc.paper.util.PoiAccess.findClosestPoiDataPosition(this, typePredicate, null, pos, radius, radius * radius, occupationStatus, false);
        return Optional.ofNullable(ret);
        // Paper end - re-route to faster logic
    }

    public Optional<Pair<Holder<PoiType>, BlockPos>> findClosestWithType(Predicate<Holder<PoiType>> typePredicate, BlockPos pos, int radius, PoiManager.Occupancy occupationStatus) {
        // Paper start - re-route to faster logic
        return Optional.ofNullable(io.papermc.paper.util.PoiAccess.findClosestPoiDataTypeAndPosition(
            this, typePredicate, null, pos, radius, radius * radius, occupationStatus, false
        ));
        // Paper end - re-route to faster logic
    }

    public Optional<BlockPos> findClosest(Predicate<Holder<PoiType>> typePredicate, Predicate<BlockPos> posPredicate, BlockPos pos, int radius, PoiManager.Occupancy occupationStatus) {
        // Paper start - re-route to faster logic
        BlockPos ret = io.papermc.paper.util.PoiAccess.findClosestPoiDataPosition(this, typePredicate, posPredicate, pos, radius, radius * radius, occupationStatus, false);
        return Optional.ofNullable(ret);
        // Paper end - re-route to faster logic
    }

    public Optional<BlockPos> take(Predicate<Holder<PoiType>> typePredicate, BiPredicate<Holder<PoiType>, BlockPos> biPredicate, BlockPos pos, int radius) {
        // Paper start - re-route to faster logic
        final @javax.annotation.Nullable PoiRecord closest = io.papermc.paper.util.PoiAccess.findClosestPoiDataRecord(
            this, typePredicate, biPredicate, pos, radius, radius * radius, Occupancy.HAS_SPACE, false
        );
        return Optional.ofNullable(closest).map(poi -> {
            // Paper end - re-route to faster logic
            poi.acquireTicket();
            return poi.getPos();
        });
    }

    public Optional<BlockPos> getRandom(Predicate<Holder<PoiType>> typePredicate, Predicate<BlockPos> positionPredicate, PoiManager.Occupancy occupationStatus, BlockPos pos, int radius, RandomSource random) {
        // Paper start - re-route to faster logic
        List<PoiRecord> list = new java.util.ArrayList<>();
        io.papermc.paper.util.PoiAccess.findAnyPoiRecords(
            this, typePredicate, positionPredicate, pos, radius, occupationStatus, false, Integer.MAX_VALUE, list
        );

        // the old method shuffled the list and then tried to find the first element in it that
        // matched positionPredicate, however we moved positionPredicate into the poi search. This means we can avoid a
        // shuffle entirely, and just pick a random element from list
        if (list.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(list.get(random.nextInt(list.size())).getPos());
        // Paper end - re-route to faster logic
    }

    public boolean release(BlockPos pos) {
        return this.getOrLoad(SectionPos.asLong(pos)).map((poiSet) -> {
            return poiSet.release(pos);
        }).orElseThrow(() -> {
            return Util.pauseInIde(new IllegalStateException("POI never registered at " + pos));
        });
    }

    public boolean exists(BlockPos pos, Predicate<Holder<PoiType>> predicate) {
        return this.getOrLoad(SectionPos.asLong(pos)).map((poiSet) -> {
            return poiSet.exists(pos, predicate);
        }).orElse(false);
    }

    public Optional<Holder<PoiType>> getType(BlockPos pos) {
        return this.getOrLoad(SectionPos.asLong(pos)).flatMap((poiSet) -> {
            return poiSet.getType(pos);
        });
    }

    /** @deprecated */
    @Deprecated
    @VisibleForDebug
    public int getFreeTickets(BlockPos pos) {
        return this.getOrLoad(SectionPos.asLong(pos)).map((poiSet) -> {
            return poiSet.getFreeTickets(pos);
        }).orElse(0);
    }

    public int sectionsToVillage(SectionPos pos) {
        this.distanceTracker.runAllUpdates();
        return this.distanceTracker.getLevel(pos.asLong());
    }

    boolean isVillageCenter(long pos) {
        Optional<PoiSection> optional = this.get(pos);
        return optional == null ? false : optional.map((poiSet) -> {
            return poiSet.getRecords((entry) -> {
                return entry.is(PoiTypeTags.VILLAGE);
            }, PoiManager.Occupancy.IS_OCCUPIED).findAny().isPresent();
        }).orElse(false);
    }

    @Override
    public void tick(BooleanSupplier shouldKeepTicking) {
        // Paper start - async chunk io
        while (!this.dirty.isEmpty() && shouldKeepTicking.getAsBoolean()) {
            ChunkPos chunkcoordintpair = SectionPos.of(this.dirty.firstLong()).chunk();

            net.minecraft.nbt.CompoundTag data;
            try (co.aikar.timings.Timing ignored1 = this.world.timings.poiSaveDataSerialization.startTiming()) {
                data = this.getData(chunkcoordintpair);
            }
            com.destroystokyo.paper.io.PaperFileIOThread.Holder.INSTANCE.scheduleSave(this.world,
                chunkcoordintpair.x, chunkcoordintpair.z, data, null, com.destroystokyo.paper.io.PrioritizedTaskQueue.NORMAL_PRIORITY);
        }
        // Paper end
        this.distanceTracker.runAllUpdates();
    }

    @Override
    protected void setDirty(long pos) {
        super.setDirty(pos);
        this.distanceTracker.update(pos, this.distanceTracker.getLevelFromSource(pos), false);
    }

    @Override
    protected void onSectionLoad(long pos) {
        this.distanceTracker.update(pos, this.distanceTracker.getLevelFromSource(pos), false);
    }

    public void checkConsistencyWithBlocks(ChunkPos chunkPos, LevelChunkSection chunkSection) {
        SectionPos sectionPos = SectionPos.of(chunkPos, SectionPos.blockToSectionCoord(chunkSection.bottomBlockY()));
        Util.ifElse(this.getOrLoad(sectionPos.asLong()), (poiSet) -> {
            poiSet.refresh((biConsumer) -> {
                if (mayHavePoi(chunkSection)) {
                    this.updateFromSection(chunkSection, sectionPos, biConsumer);
                }

            });
        }, () -> {
            if (mayHavePoi(chunkSection)) {
                PoiSection poiSection = this.getOrCreate(sectionPos.asLong());
                this.updateFromSection(chunkSection, sectionPos, poiSection::add);
            }

        });
    }

    private static boolean mayHavePoi(LevelChunkSection chunkSection) {
        return chunkSection.maybeHas(PoiTypes.ALL_STATES::contains);
    }

    private void updateFromSection(LevelChunkSection chunkSection, SectionPos sectionPos, BiConsumer<BlockPos, Holder<PoiType>> biConsumer) {
        sectionPos.blocksInside().forEach((pos) -> {
            BlockState blockState = chunkSection.getBlockState(SectionPos.sectionRelative(pos.getX()), SectionPos.sectionRelative(pos.getY()), SectionPos.sectionRelative(pos.getZ()));
            PoiTypes.forState(blockState).ifPresent((poiType) -> {
                biConsumer.accept(pos, poiType);
            });
        });
    }

    public void ensureLoadedAndValid(LevelReader world, BlockPos pos, int radius) {
        SectionPos.aroundChunk(new ChunkPos(pos), Math.floorDiv(radius, 16), this.levelHeightAccessor.getMinSection(), this.levelHeightAccessor.getMaxSection()).map((sectionPos) -> {
            return Pair.of(sectionPos, this.getOrLoad(sectionPos.asLong()));
        }).filter((pair) -> {
            return !pair.getSecond().map(PoiSection::isValid).orElse(false);
        }).map((pair) -> {
            return pair.getFirst().chunk();
        }).filter((chunkPos) -> {
            return this.loadedChunks.add(chunkPos.toLong());
        }).forEach((chunkPos) -> {
            world.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.EMPTY);
        });
    }

    final class DistanceTracker extends SectionTracker {
        private final Long2ByteMap levels = new Long2ByteOpenHashMap();

        protected DistanceTracker() {
            super(7, 16, 256);
            this.levels.defaultReturnValue((byte)7);
        }

        @Override
        protected int getLevelFromSource(long id) {
            return PoiManager.this.isVillageCenter(id) ? 0 : 7;
        }

        @Override
        protected int getLevel(long id) {
            return this.levels.get(id);
        }

        @Override
        protected void setLevel(long id, int level) {
            if (level > 6) {
                this.levels.remove(id);
            } else {
                this.levels.put(id, (byte)level);
            }

        }

        public void runAllUpdates() {
            super.runUpdates(Integer.MAX_VALUE);
        }
    }

    // Paper start - Asynchronous chunk io
    @javax.annotation.Nullable
    @Override
    public net.minecraft.nbt.CompoundTag read(ChunkPos chunkcoordintpair) throws java.io.IOException {
        if (this.world != null && Thread.currentThread() != com.destroystokyo.paper.io.PaperFileIOThread.Holder.INSTANCE) {
            net.minecraft.nbt.CompoundTag ret = com.destroystokyo.paper.io.PaperFileIOThread.Holder.INSTANCE
                .loadChunkDataAsyncFuture(this.world, chunkcoordintpair.x, chunkcoordintpair.z, com.destroystokyo.paper.io.IOUtil.getPriorityForCurrentThread(),
                    true, false, true).join().poiData;

            if (ret == com.destroystokyo.paper.io.PaperFileIOThread.FAILURE_VALUE) {
                throw new java.io.IOException("See logs for further detail");
            }
            return ret;
        }
        return super.read(chunkcoordintpair);
    }

    @Override
    public void write(ChunkPos chunkcoordintpair, net.minecraft.nbt.CompoundTag nbttagcompound) throws java.io.IOException {
        if (this.world != null && Thread.currentThread() != com.destroystokyo.paper.io.PaperFileIOThread.Holder.INSTANCE) {
            com.destroystokyo.paper.io.PaperFileIOThread.Holder.INSTANCE.scheduleSave(
                this.world, chunkcoordintpair.x, chunkcoordintpair.z, nbttagcompound, null,
                com.destroystokyo.paper.io.IOUtil.getPriorityForCurrentThread());
            return;
        }
        super.write(chunkcoordintpair, nbttagcompound);
    }
    // Paper end

    public static enum Occupancy {
        HAS_SPACE(PoiRecord::hasSpace),
        IS_OCCUPIED(PoiRecord::isOccupied),
        ANY((poiRecord) -> {
            return true;
        });

        private final Predicate<? super PoiRecord> test;

        private Occupancy(Predicate<? super PoiRecord> predicate) {
            this.test = predicate;
        }

        public Predicate<? super PoiRecord> getTest() {
            return this.test;
        }
    }
}
