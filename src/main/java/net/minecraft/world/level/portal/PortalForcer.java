package net.minecraft.world.level.portal;

import java.util.Comparator;
import java.util.Iterator;
import java.util.Optional;
import net.minecraft.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;

public class PortalForcer {

    private static final int TICKET_RADIUS = 3;
    private static final int SEARCH_RADIUS = 128;
    private static final int CREATE_RADIUS = 16;
    private static final int FRAME_HEIGHT = 5;
    private static final int FRAME_WIDTH = 4;
    private static final int FRAME_BOX = 3;
    private static final int FRAME_HEIGHT_START = -1;
    private static final int FRAME_HEIGHT_END = 4;
    private static final int FRAME_WIDTH_START = -1;
    private static final int FRAME_WIDTH_END = 3;
    private static final int FRAME_BOX_START = -1;
    private static final int FRAME_BOX_END = 2;
    private static final int NOTHING_FOUND = -1;
    private final ServerLevel level;

    public PortalForcer(ServerLevel world) {
        this.level = world;
    }

    public Optional<BlockUtil.FoundRectangle> findPortalAround(BlockPos pos, boolean destIsNether, WorldBorder worldBorder) {
        // CraftBukkit start
        return this.findPortalAround(pos, worldBorder, destIsNether ? level.paperConfig().environment.portalCreateRadius : level.paperConfig().environment.portalSearchRadius); // Search Radius // Paper - search Radius
    }

    public Optional<BlockUtil.FoundRectangle> findPortalAround(BlockPos blockposition, WorldBorder worldborder, int i) {
        PoiManager villageplace = this.level.getPoiManager();
        // int i = flag ? 16 : 128;
        // CraftBukkit end

        villageplace.ensureLoadedAndValid(this.level, blockposition, i);
        Optional<PoiRecord> optional = villageplace.getInSquare((holder) -> {
            return holder.is(PoiTypes.NETHER_PORTAL);
        }, blockposition, i, PoiManager.Occupancy.ANY).filter((villageplacerecord) -> {
            return worldborder.isWithinBounds(villageplacerecord.getPos());
        }).sorted(Comparator.comparingDouble((PoiRecord villageplacerecord) -> { // CraftBukkit - decompile error
            return villageplacerecord.getPos().distSqr(blockposition);
        }).thenComparingInt((villageplacerecord) -> {
            return villageplacerecord.getPos().getY();
        })).filter((villageplacerecord) -> {
            return this.level.getBlockState(villageplacerecord.getPos()).hasProperty(BlockStateProperties.HORIZONTAL_AXIS);
        }).findFirst();

        return optional.map((villageplacerecord) -> {
            BlockPos blockposition1 = villageplacerecord.getPos();

            this.level.getChunkSource().addRegionTicket(TicketType.PORTAL, new ChunkPos(blockposition1), 3, blockposition1);
            BlockState iblockdata = this.level.getBlockState(blockposition1);

            return BlockUtil.getLargestRectangleAround(blockposition1, (Direction.Axis) iblockdata.getValue(BlockStateProperties.HORIZONTAL_AXIS), 21, Direction.Axis.Y, 21, (blockposition2) -> {
                return this.level.getBlockState(blockposition2) == iblockdata;
            });
        });
    }

    public Optional<BlockUtil.FoundRectangle> createPortal(BlockPos pos, Direction.Axis axis) {
        // CraftBukkit start
        return this.createPortal(pos, axis, null, 16);
    }

    public Optional<BlockUtil.FoundRectangle> createPortal(BlockPos blockposition, Direction.Axis enumdirection_enumaxis, net.minecraft.world.entity.Entity entity, int createRadius) {
        // CraftBukkit end
        Direction enumdirection = Direction.get(Direction.AxisDirection.POSITIVE, enumdirection_enumaxis);
        double d0 = -1.0D;
        BlockPos blockposition1 = null;
        double d1 = -1.0D;
        BlockPos blockposition2 = null;
        WorldBorder worldborder = this.level.getWorldBorder();
        int i = Math.min(this.level.getMaxBuildHeight(), this.level.getMinBuildHeight() + this.level.getLogicalHeight()) - 1;
        BlockPos.MutableBlockPos blockposition_mutableblockposition = blockposition.mutable();
        Iterator iterator = BlockPos.spiralAround(blockposition, createRadius, Direction.EAST, Direction.SOUTH).iterator(); // CraftBukkit

        int j;
        int k;
        int l;

        while (iterator.hasNext()) {
            BlockPos.MutableBlockPos blockposition_mutableblockposition1 = (BlockPos.MutableBlockPos) iterator.next();

            j = Math.min(i, this.level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockposition_mutableblockposition1.getX(), blockposition_mutableblockposition1.getZ()));
            boolean flag = true;

            if (worldborder.isWithinBounds((BlockPos) blockposition_mutableblockposition1) && worldborder.isWithinBounds((BlockPos) blockposition_mutableblockposition1.move(enumdirection, 1))) {
                blockposition_mutableblockposition1.move(enumdirection.getOpposite(), 1);

                for (k = j; k >= this.level.getMinBuildHeight(); --k) {
                    blockposition_mutableblockposition1.setY(k);
                    if (this.level.isEmptyBlock(blockposition_mutableblockposition1)) {
                        for (l = k; k > this.level.getMinBuildHeight() && this.level.isEmptyBlock(blockposition_mutableblockposition1.move(Direction.DOWN)); --k) {
                            ;
                        }

                        if (k + 4 <= i) {
                            int i1 = l - k;

                            if (i1 <= 0 || i1 >= 3) {
                                blockposition_mutableblockposition1.setY(k);
                                if (this.canHostFrame(blockposition_mutableblockposition1, blockposition_mutableblockposition, enumdirection, 0)) {
                                    double d2 = blockposition.distSqr(blockposition_mutableblockposition1);

                                    if (this.canHostFrame(blockposition_mutableblockposition1, blockposition_mutableblockposition, enumdirection, -1) && this.canHostFrame(blockposition_mutableblockposition1, blockposition_mutableblockposition, enumdirection, 1) && (d0 == -1.0D || d0 > d2)) {
                                        d0 = d2;
                                        blockposition1 = blockposition_mutableblockposition1.immutable();
                                    }

                                    if (d0 == -1.0D && (d1 == -1.0D || d1 > d2)) {
                                        d1 = d2;
                                        blockposition2 = blockposition_mutableblockposition1.immutable();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (d0 == -1.0D && d1 != -1.0D) {
            blockposition1 = blockposition2;
            d0 = d1;
        }

        int j1;
        int k1;

        org.bukkit.craftbukkit.util.BlockStateListPopulator blockList = new org.bukkit.craftbukkit.util.BlockStateListPopulator(this.level); // CraftBukkit - Use BlockStateListPopulator
        if (d0 == -1.0D) {
            j1 = Math.max(this.level.getMinBuildHeight() - -1, 70);
            k1 = i - 9;
            if (k1 < j1) {
                return Optional.empty();
            }

            blockposition1 = (new BlockPos(blockposition.getX(), Mth.clamp(blockposition.getY(), j1, k1), blockposition.getZ())).immutable();
            Direction enumdirection1 = enumdirection.getClockWise();

            if (!worldborder.isWithinBounds(blockposition1)) {
                return Optional.empty();
            }

            for (int l1 = -1; l1 < 2; ++l1) {
                for (k = 0; k < 2; ++k) {
                    for (l = -1; l < 3; ++l) {
                        BlockState iblockdata = l < 0 ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState();

                        blockposition_mutableblockposition.setWithOffset(blockposition1, k * enumdirection.getStepX() + l1 * enumdirection1.getStepX(), l, k * enumdirection.getStepZ() + l1 * enumdirection1.getStepZ());
                        blockList.setBlock(blockposition_mutableblockposition, iblockdata, 3); // CraftBukkit
                    }
                }
            }
        }

        for (j1 = -1; j1 < 3; ++j1) {
            for (k1 = -1; k1 < 4; ++k1) {
                if (j1 == -1 || j1 == 2 || k1 == -1 || k1 == 3) {
                    blockposition_mutableblockposition.setWithOffset(blockposition1, j1 * enumdirection.getStepX(), k1, j1 * enumdirection.getStepZ());
                    blockList.setBlock(blockposition_mutableblockposition, Blocks.OBSIDIAN.defaultBlockState(), 3); // CraftBukkit
                }
            }
        }

        BlockState iblockdata1 = (BlockState) Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, enumdirection_enumaxis);

        for (k1 = 0; k1 < 2; ++k1) {
            for (j = 0; j < 3; ++j) {
                blockposition_mutableblockposition.setWithOffset(blockposition1, k1 * enumdirection.getStepX(), j, k1 * enumdirection.getStepZ());
                blockList.setBlock(blockposition_mutableblockposition, iblockdata1, 18); // CraftBukkit
            }
        }

        // CraftBukkit start
        org.bukkit.World bworld = this.level.getWorld();
        org.bukkit.event.world.PortalCreateEvent event = new org.bukkit.event.world.PortalCreateEvent((java.util.List<org.bukkit.block.BlockState>) (java.util.List) blockList.getList(), bworld, (entity == null) ? null : entity.getBukkitEntity(), org.bukkit.event.world.PortalCreateEvent.CreateReason.NETHER_PAIR);

        this.level.getCraftServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return Optional.empty();
        }
        blockList.updateList();
        // CraftBukkit end
        return Optional.of(new BlockUtil.FoundRectangle(blockposition1.immutable(), 2, 3));
    }

    private boolean canHostFrame(BlockPos pos, BlockPos.MutableBlockPos temp, Direction portalDirection, int distanceOrthogonalToPortal) {
        Direction enumdirection1 = portalDirection.getClockWise();

        for (int j = -1; j < 3; ++j) {
            for (int k = -1; k < 4; ++k) {
                temp.setWithOffset(pos, portalDirection.getStepX() * j + enumdirection1.getStepX() * distanceOrthogonalToPortal, k, portalDirection.getStepZ() * j + enumdirection1.getStepZ() * distanceOrthogonalToPortal);
                if (k < 0 && !this.level.getBlockState(temp).getMaterial().isSolid()) {
                    return false;
                }

                if (k >= 0 && !this.level.isEmptyBlock(temp)) {
                    return false;
                }
            }
        }

        return true;
    }
}
