package net.minecraft.world.level.chunk;

import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public class LevelChunkSection {

    public static final int SECTION_WIDTH = 16;
    public static final int SECTION_HEIGHT = 16;
    public static final int SECTION_SIZE = 4096;
    public static final int BIOME_CONTAINER_BITS = 2;
    private final int bottomBlockY;
    short nonEmptyBlockCount; // Paper - package-private
    private short tickingBlockCount;
    private short tickingFluidCount;
    public final PalettedContainer<BlockState> states;
    // CraftBukkit start - read/write
    private PalettedContainer<Holder<Biome>> biomes;
    public final com.destroystokyo.paper.util.maplist.IBlockDataList tickingList = new com.destroystokyo.paper.util.maplist.IBlockDataList(); // Paper

    public LevelChunkSection(int i, PalettedContainer<BlockState> datapaletteblock, PalettedContainer<Holder<Biome>> palettedcontainerro) {
        // CraftBukkit end
        this.bottomBlockY = LevelChunkSection.getBottomBlockY(i);
        this.states = datapaletteblock;
        this.biomes = palettedcontainerro;
        this.recalcBlockCounts();
    }

    // Paper start - Anti-Xray - Add parameters
    @Deprecated @io.papermc.paper.annotation.DoNotUse public LevelChunkSection(int chunkPos, Registry<Biome> biomeRegistry) { this(chunkPos, biomeRegistry, null, null); }
    public LevelChunkSection(int chunkPos, Registry<Biome> biomeRegistry, net.minecraft.world.level.ChunkPos pos, net.minecraft.world.level.Level level) {
        // Paper end
        this.bottomBlockY = LevelChunkSection.getBottomBlockY(chunkPos);
        this.states = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES, level == null || level.chunkPacketBlockController == null ? null : level.chunkPacketBlockController.getPresetBlockStates(level, pos, this.bottomBlockY())); // Paper - Anti-Xray - Add preset block states
        this.biomes = new PalettedContainer<>(biomeRegistry.asHolderIdMap(), biomeRegistry.getHolderOrThrow(Biomes.PLAINS), PalettedContainer.Strategy.SECTION_BIOMES, null); // Paper - Anti-Xray - Add preset biomes
    }

    // Paper start
    protected int specialCollidingBlocks;
    // blockIndex = x | (z << 4) | (y << 8)
    private long[] knownBlockCollisionData;

    private long[] initKnownDataField() {
        return this.knownBlockCollisionData = new long[16 * 16 * 16 * 2 / Long.SIZE];
    }

    public final boolean hasSpecialCollidingBlocks() {
        return this.specialCollidingBlocks != 0;
    }

    public static long getKnownBlockInfo(final int blockIndex, final long value) {
        final int valueShift = (blockIndex & (Long.SIZE / 2 - 1));

        return (value >>> (valueShift << 1)) & 0b11L;
    }

    public final long getKnownBlockInfo(final int blockIndex) {
        if (this.knownBlockCollisionData == null) {
            return 0L;
        }

        final int arrayIndex = (blockIndex >>> (6 - 1)); // blockIndex / (64/2)
        final int valueShift = (blockIndex & (Long.SIZE / 2 - 1));

        final long value = this.knownBlockCollisionData[arrayIndex];

        return (value >>> (valueShift << 1)) & 0b11L;
    }

    // important detail: this returns 32 values, one for localZ = localZ & (~1) and one for localZ = localZ | 1
    // the even localZ is the lower 32 bits, the odd is the upper 32 bits
    public final long getKnownBlockInfoHorizontalRaw(final int localY, final int localZ) {
        if (this.knownBlockCollisionData == null) {
            return 0L;
        }

        final int horizontalIndex = (localZ << 4) | (localY << 8);
        return this.knownBlockCollisionData[horizontalIndex >>> (6 - 1)];
    }

    private void initBlockCollisionData() {
        this.specialCollidingBlocks = 0;
        // In 1.18 all sections will be initialised, whether or not they have blocks (fucking stupid btw)
        // This means we can't aggressively initialise the backing long[], or else memory usage will just skyrocket.
        // So only init if we contain non-empty blocks.
        if (this.nonEmptyBlockCount == 0) {
            this.knownBlockCollisionData = null;
            return;
        }
        this.initKnownDataField();
        for (int index = 0; index < (16 * 16 * 16); ++index) {
            final BlockState state = this.states.get(index);
            this.setKnownBlockInfo(index, state);
            if (io.papermc.paper.util.CollisionUtil.isSpecialCollidingBlock(state)) {
                ++this.specialCollidingBlocks;
            }
        }
    }

    // only use for initBlockCollisionData
    private void setKnownBlockInfo(final int blockIndex, final BlockState blockState) {
        final int arrayIndex = (blockIndex >>> (6 - 1)); // blockIndex / (64/2)
        final int valueShift = (blockIndex & (Long.SIZE / 2 - 1)) << 1;

        long value = this.knownBlockCollisionData[arrayIndex];

        value &= ~(0b11L << valueShift);
        value |= blockState.getBlockCollisionBehavior() << valueShift;

        this.knownBlockCollisionData[arrayIndex] = value;
    }

    public void updateKnownBlockInfo(final int blockIndex, final BlockState from, final BlockState to) {
        if (io.papermc.paper.util.CollisionUtil.isSpecialCollidingBlock(from)) {
            --this.specialCollidingBlocks;
        }
        if (io.papermc.paper.util.CollisionUtil.isSpecialCollidingBlock(to)) {
            ++this.specialCollidingBlocks;
        }

        if (this.nonEmptyBlockCount == 0) {
            this.knownBlockCollisionData = null;
            return;
        }

        if (this.knownBlockCollisionData == null) {
            this.initKnownDataField();
        }

        final int arrayIndex = (blockIndex >>> (6 - 1)); // blockIndex / (64/2)
        final int valueShift = (blockIndex & (Long.SIZE / 2 - 1)) << 1;

        long value = this.knownBlockCollisionData[arrayIndex];

        value &= ~(0b11L << valueShift);
        value |= to.getBlockCollisionBehavior() << valueShift;

        this.knownBlockCollisionData[arrayIndex] = value;
    }
    // Paper end

    public static int getBottomBlockY(int chunkPos) {
        return chunkPos << 4;
    }

    public BlockState getBlockState(int x, int y, int z) {
        return (BlockState) this.states.get(x, y, z);
    }

    public FluidState getFluidState(int x, int y, int z) {
        return this.states.get(x, y, z).getFluidState(); // Paper - diff on change - we expect this to be effectively just getType(x, y, z).getFluid(). If this changes we need to check other patches that use IBlockData#getFluid.
    }

    public void acquire() {
        this.states.acquire();
    }

    public void release() {
        this.states.release();
    }

    public BlockState setBlockState(int x, int y, int z, BlockState state) {
        return this.setBlockState(x, y, z, state, true);
    }

    public BlockState setBlockState(int x, int y, int z, BlockState state, boolean lock) {  // Paper - state -> new state
        BlockState iblockdata1; // Paper - iblockdata1 -> oldState

        if (lock) {
            iblockdata1 = (BlockState) this.states.getAndSet(x, y, z, state);
        } else {
            iblockdata1 = (BlockState) this.states.getAndSetUnchecked(x, y, z, state);
        }

        FluidState fluid = iblockdata1.getFluidState();
        FluidState fluid1 = state.getFluidState();

        if (!iblockdata1.isAir()) {
            --this.nonEmptyBlockCount;
            if (iblockdata1.isRandomlyTicking()) {
                --this.tickingBlockCount;
                // Paper start
                this.tickingList.remove(x, y, z);
                // Paper end
            }
        }

        if (!fluid.isEmpty()) {
            --this.tickingFluidCount;
        }

        if (!state.isAir()) {
            ++this.nonEmptyBlockCount;
            if (state.isRandomlyTicking()) {
                ++this.tickingBlockCount;
                // Paper start
                this.tickingList.add(x, y, z, state);
                // Paper end
            }
        }

        if (!fluid1.isEmpty()) {
            ++this.tickingFluidCount;
        }

        this.updateKnownBlockInfo(x | (z << 4) | (y << 8), iblockdata1, state); // Paper
        return iblockdata1;
    }

    public boolean hasOnlyAir() {
        return this.nonEmptyBlockCount == 0;
    }

    public boolean isRandomlyTicking() {
        return this.isRandomlyTickingBlocks() || this.isRandomlyTickingFluids();
    }

    public boolean isRandomlyTickingBlocks() {
        return this.tickingBlockCount > 0;
    }

    public boolean isRandomlyTickingFluids() {
        return this.tickingFluidCount > 0;
    }

    public int bottomBlockY() {
        return this.bottomBlockY;
    }

    public void recalcBlockCounts() {
        // Paper start - unfuck this
        this.tickingList.clear();
        this.nonEmptyBlockCount = 0;
        this.tickingBlockCount = 0;
        this.tickingFluidCount = 0;
        this.states.forEachLocation((BlockState iblockdata, int i) -> {
            FluidState fluid = iblockdata.getFluidState();

            if (!iblockdata.isAir()) {
                this.nonEmptyBlockCount = (short) (this.nonEmptyBlockCount + 1);
                if (iblockdata.isRandomlyTicking()) {
                    this.tickingBlockCount = (short)(this.tickingBlockCount + 1);
                    this.tickingList.add(i, iblockdata);
                }
            }

            if (!fluid.isEmpty()) {
                this.nonEmptyBlockCount = (short) (this.nonEmptyBlockCount + 1);
                if (fluid.isRandomlyTicking()) {
                    this.tickingFluidCount = (short) (this.tickingFluidCount + 1);
                }
            }

        });
        // Paper end
        this.initBlockCollisionData(); // Paper
    }

    public PalettedContainer<BlockState> getStates() {
        return this.states;
    }

    public PalettedContainerRO<Holder<Biome>> getBiomes() {
        return this.biomes;
    }

    public void read(FriendlyByteBuf buf) {
        this.nonEmptyBlockCount = buf.readShort();
        this.states.read(buf);
        PalettedContainer<Holder<Biome>> datapaletteblock = this.biomes.recreate();

        datapaletteblock.read(buf);
        this.biomes = datapaletteblock;
    }

    // Paper start - Anti-Xray - Add chunk packet info
    @Deprecated @io.papermc.paper.annotation.DoNotUse public void write(FriendlyByteBuf buf) { this.write(buf, null); }
    public void write(FriendlyByteBuf buf, com.destroystokyo.paper.antixray.ChunkPacketInfo<BlockState> chunkPacketInfo) {
        buf.writeShort(this.nonEmptyBlockCount);
        this.states.write(buf, chunkPacketInfo, this.bottomBlockY());
        this.biomes.write(buf, null, this.bottomBlockY());
        // Paper end
    }

    public int getSerializedSize() {
        return 2 + this.states.getSerializedSize() + this.biomes.getSerializedSize();
    }

    public boolean maybeHas(Predicate<BlockState> predicate) {
        return this.states.maybeHas(predicate);
    }

    public Holder<Biome> getNoiseBiome(int x, int y, int z) {
        return (Holder) this.biomes.get(x, y, z);
    }

    // CraftBukkit start
    public void setBiome(int i, int j, int k, Holder<Biome> biome) {
        this.biomes.set(i, j, k, biome);
    }
    // CraftBukkit end

    public void fillBiomesFromNoise(BiomeResolver biomeSupplier, Climate.Sampler sampler, int x, int z) {
        PalettedContainer<Holder<Biome>> datapaletteblock = this.biomes.recreate();
        int k = QuartPos.fromBlock(this.bottomBlockY());
        boolean flag = true;

        for (int l = 0; l < 4; ++l) {
            for (int i1 = 0; i1 < 4; ++i1) {
                for (int j1 = 0; j1 < 4; ++j1) {
                    datapaletteblock.getAndSetUnchecked(l, i1, j1, biomeSupplier.getNoiseBiome(x + l, k + i1, z + j1, sampler));
                }
            }
        }

        this.biomes = datapaletteblock;
    }
}
