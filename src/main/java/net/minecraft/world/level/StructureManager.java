package net.minecraft.world.level;

import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.StructureAccess;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

public class StructureManager {
    private final LevelAccessor level;
    private final WorldGenSettings worldGenSettings;
    private final StructureCheck structureCheck;

    public StructureManager(LevelAccessor world, WorldGenSettings options, StructureCheck locator) {
        this.level = world;
        this.worldGenSettings = options;
        this.structureCheck = locator;
    }

    public StructureManager forWorldGenRegion(WorldGenRegion region) {
        if (region.getLevel() != this.level) {
            throw new IllegalStateException("Using invalid structure manager (source level: " + region.getLevel() + ", region: " + region);
        } else {
            return new StructureManager(region, this.worldGenSettings, this.structureCheck);
        }
    }

    public List<StructureStart> startsForStructure(ChunkPos pos, Predicate<Structure> predicate) {
        Map<Structure, LongSet> map = this.level.getChunk(pos.x, pos.z, ChunkStatus.STRUCTURE_REFERENCES).getAllReferences();
        ImmutableList.Builder<StructureStart> builder = ImmutableList.builder();

        for(Map.Entry<Structure, LongSet> entry : map.entrySet()) {
            Structure structure = entry.getKey();
            if (predicate.test(structure)) {
                this.fillStartsForStructure(structure, entry.getValue(), builder::add);
            }
        }

        return builder.build();
    }

    public List<StructureStart> startsForStructure(SectionPos sectionPos, Structure structure) {
        LongSet longSet = this.level.getChunk(sectionPos.x(), sectionPos.z(), ChunkStatus.STRUCTURE_REFERENCES).getReferencesForStructure(structure);
        ImmutableList.Builder<StructureStart> builder = ImmutableList.builder();
        this.fillStartsForStructure(structure, longSet, builder::add);
        return builder.build();
    }

    public void fillStartsForStructure(Structure structure, LongSet structureStartPositions, Consumer<StructureStart> consumer) {
        for(long l : structureStartPositions) {
            SectionPos sectionPos = SectionPos.of(new ChunkPos(l), this.level.getMinSection());
            StructureStart structureStart = this.getStartForStructure(sectionPos, structure, this.level.getChunk(sectionPos.x(), sectionPos.z(), ChunkStatus.STRUCTURE_STARTS));
            if (structureStart != null && structureStart.isValid()) {
                consumer.accept(structureStart);
            }
        }

    }

    @Nullable
    public StructureStart getStartForStructure(SectionPos pos, Structure structure, StructureAccess holder) {
        return holder.getStartForStructure(structure);
    }

    public void setStartForStructure(SectionPos pos, Structure structure, StructureStart structureStart, StructureAccess holder) {
        holder.setStartForStructure(structure, structureStart);
    }

    public void addReferenceForStructure(SectionPos pos, Structure structure, long reference, StructureAccess holder) {
        holder.addReferenceForStructure(structure, reference);
    }

    public boolean shouldGenerateStructures() {
        return this.worldGenSettings.generateStructures();
    }

    public StructureStart getStructureAt(BlockPos pos, Structure structure) {
        for(StructureStart structureStart : this.startsForStructure(SectionPos.of(pos), structure)) {
            if (structureStart.getBoundingBox().isInside(pos)) {
                return structureStart;
            }
        }

        return StructureStart.INVALID_START;
    }

    public StructureStart getStructureWithPieceAt(BlockPos pos, ResourceKey<Structure> structure) {
        Structure structure2 = this.registryAccess().registryOrThrow(Registry.STRUCTURE_REGISTRY).get(structure);
        return structure2 == null ? StructureStart.INVALID_START : this.getStructureWithPieceAt(pos, structure2);
    }

    public StructureStart getStructureWithPieceAt(BlockPos pos, TagKey<Structure> structureTag) {
        Registry<Structure> registry = this.registryAccess().registryOrThrow(Registry.STRUCTURE_REGISTRY);

        for(StructureStart structureStart : this.startsForStructure(new ChunkPos(pos), (structure) -> {
            return registry.getHolder(registry.getId(structure)).map((holder) -> {
                return holder.is(structureTag);
            }).orElse(false);
        })) {
            if (this.structureHasPieceAt(pos, structureStart)) {
                return structureStart;
            }
        }

        return StructureStart.INVALID_START;
    }

    public StructureStart getStructureWithPieceAt(BlockPos pos, Structure structure) {
        for(StructureStart structureStart : this.startsForStructure(SectionPos.of(pos), structure)) {
            if (this.structureHasPieceAt(pos, structureStart)) {
                return structureStart;
            }
        }

        return StructureStart.INVALID_START;
    }

    public boolean structureHasPieceAt(BlockPos pos, StructureStart structureStart) {
        for(StructurePiece structurePiece : structureStart.getPieces()) {
            if (structurePiece.getBoundingBox().isInside(pos)) {
                return true;
            }
        }

        return false;
    }

    public boolean hasAnyStructureAt(BlockPos pos) {
        SectionPos sectionPos = SectionPos.of(pos);
        return this.level.getChunk(sectionPos.x(), sectionPos.z(), ChunkStatus.STRUCTURE_REFERENCES).hasAnyStructureReferences();
    }

    public Map<Structure, LongSet> getAllStructuresAt(BlockPos pos) {
        SectionPos sectionPos = SectionPos.of(pos);
        return this.level.getChunk(sectionPos.x(), sectionPos.z(), ChunkStatus.STRUCTURE_REFERENCES).getAllReferences();
    }

    public StructureCheckResult checkStructurePresence(ChunkPos chunkPos, Structure structure, boolean skipExistingChunk) {
        return this.structureCheck.checkStart(chunkPos, structure, skipExistingChunk);
    }

    public void addReference(StructureStart structureStart) {
        structureStart.addReference();
        this.structureCheck.incrementReference(structureStart.getChunkPos(), structureStart.getStructure());
    }

    public RegistryAccess registryAccess() {
        return this.level.registryAccess();
    }
}