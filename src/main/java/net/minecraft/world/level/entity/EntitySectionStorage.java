package net.minecraft.world.level.entity;

import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import java.util.Objects;
import java.util.PrimitiveIterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import net.minecraft.core.SectionPos;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

public class EntitySectionStorage<T extends EntityAccess> {
    private final Class<T> entityClass;
    private final Long2ObjectFunction<Visibility> intialSectionVisibility;
    private final Long2ObjectMap<EntitySection<T>> sections = new Long2ObjectOpenHashMap<>();
    private final LongSortedSet sectionIds = new LongAVLTreeSet();

    public EntitySectionStorage(Class<T> entityClass, Long2ObjectFunction<Visibility> chunkStatusDiscriminator) {
        this.entityClass = entityClass;
        this.intialSectionVisibility = chunkStatusDiscriminator;
    }

    public void forEachAccessibleNonEmptySection(AABB box, Consumer<EntitySection<T>> action) {
        int i = 2;
        int j = SectionPos.posToSectionCoord(box.minX - 2.0D);
        int k = SectionPos.posToSectionCoord(box.minY - 4.0D);
        int l = SectionPos.posToSectionCoord(box.minZ - 2.0D);
        int m = SectionPos.posToSectionCoord(box.maxX + 2.0D);
        int n = SectionPos.posToSectionCoord(box.maxY + 0.0D);
        int o = SectionPos.posToSectionCoord(box.maxZ + 2.0D);

        for(int p = j; p <= m; ++p) {
            long q = SectionPos.asLong(p, 0, 0);
            long r = SectionPos.asLong(p, -1, -1);
            LongIterator longIterator = this.sectionIds.subSet(q, r + 1L).iterator();

            while(longIterator.hasNext()) {
                long s = longIterator.nextLong();
                int t = SectionPos.y(s);
                int u = SectionPos.z(s);
                if (t >= k && t <= n && u >= l && u <= o) {
                    EntitySection<T> entitySection = this.sections.get(s);
                    if (entitySection != null && !entitySection.isEmpty() && entitySection.getStatus().isAccessible()) {
                        action.accept(entitySection);
                    }
                }
            }
        }

    }

    public LongStream getExistingSectionPositionsInChunk(long chunkPos) {
        int i = ChunkPos.getX(chunkPos);
        int j = ChunkPos.getZ(chunkPos);
        LongSortedSet longSortedSet = this.getChunkSections(i, j);
        if (longSortedSet.isEmpty()) {
            return LongStream.empty();
        } else {
            PrimitiveIterator.OfLong ofLong = longSortedSet.iterator();
            return StreamSupport.longStream(Spliterators.spliteratorUnknownSize(ofLong, 1301), false);
        }
    }

    private LongSortedSet getChunkSections(int chunkX, int chunkZ) {
        long l = SectionPos.asLong(chunkX, 0, chunkZ);
        long m = SectionPos.asLong(chunkX, -1, chunkZ);
        return this.sectionIds.subSet(l, m + 1L);
    }

    public Stream<EntitySection<T>> getExistingSectionsInChunk(long chunkPos) {
        return this.getExistingSectionPositionsInChunk(chunkPos).mapToObj(this.sections::get).filter(Objects::nonNull);
    }

    private static long getChunkKeyFromSectionKey(long sectionPos) {
        return ChunkPos.asLong(SectionPos.x(sectionPos), SectionPos.z(sectionPos));
    }

    public EntitySection<T> getOrCreateSection(long sectionPos) {
        return this.sections.computeIfAbsent(sectionPos, this::createSection);
    }

    @Nullable
    public EntitySection<T> getSection(long sectionPos) {
        return this.sections.get(sectionPos);
    }

    private EntitySection<T> createSection(long sectionPos) {
        long l = getChunkKeyFromSectionKey(sectionPos);
        Visibility visibility = this.intialSectionVisibility.get(l);
        this.sectionIds.add(sectionPos);
        return new EntitySection<>(this.entityClass, visibility);
    }

    public LongSet getAllChunksWithExistingSections() {
        LongSet longSet = new LongOpenHashSet();
        this.sections.keySet().forEach((sectionPos) -> {
            longSet.add(getChunkKeyFromSectionKey(sectionPos));
        });
        return longSet;
    }

    public void getEntities(AABB box, Consumer<T> action) {
        // Paper start
        this.getEntities(box, action, false);
    }
    public void getEntities(AABB box, Consumer<T> action, boolean isContainerSearch) {
        // Paper end
        this.forEachAccessibleNonEmptySection(box, (section) -> {
            if (isContainerSearch && section.inventoryEntityCount <= 0) return; // Paper
            section.getEntities(box, action);
        });
    }

    public <U extends T> void getEntities(EntityTypeTest<T, U> filter, AABB box, Consumer<U> action) {
        this.forEachAccessibleNonEmptySection(box, (section) -> {
            if (filter.getBaseClass() == net.minecraft.world.entity.item.ItemEntity.class && section.itemCount <= 0) return; // Paper
            section.getEntities(filter, box, action);
        });
    }

    public void remove(long sectionPos) {
        this.sections.remove(sectionPos);
        this.sectionIds.remove(sectionPos);
    }

    @VisibleForDebug
    public int count() {
        return this.sectionIds.size();
    }
}
