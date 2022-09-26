package net.minecraft.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.grower.AbstractTreeGrower;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
// CraftBukkit start
import org.bukkit.Location;
import org.bukkit.TreeType;
import org.bukkit.block.BlockState;
import org.bukkit.event.world.StructureGrowEvent;
// CraftBukkit end

public class SaplingBlock extends BushBlock implements BonemealableBlock {

    public static final IntegerProperty STAGE = BlockStateProperties.STAGE;
    protected static final float AABB_OFFSET = 6.0F;
    protected static final VoxelShape SHAPE = Block.box(2.0D, 0.0D, 2.0D, 14.0D, 12.0D, 14.0D);
    private final AbstractTreeGrower treeGrower;
    public static TreeType treeType; // CraftBukkit

    protected SaplingBlock(AbstractTreeGrower generator, BlockBehaviour.Properties settings) {
        super(settings);
        this.treeGrower = generator;
        this.registerDefaultState((net.minecraft.world.level.block.state.BlockState) ((net.minecraft.world.level.block.state.BlockState) this.stateDefinition.any()).setValue(SaplingBlock.STAGE, 0));
    }

    @Override
    public VoxelShape getShape(net.minecraft.world.level.block.state.BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return SaplingBlock.SHAPE;
    }

    @Override
    public void randomTick(net.minecraft.world.level.block.state.BlockState state, ServerLevel world, BlockPos pos, RandomSource random) {
        if (world.getMaxLocalRawBrightness(pos.above()) >= 9 && random.nextInt(Math.max(2, (int) (((100.0F / world.spigotConfig.saplingModifier) * 7) + 0.5F))) == 0) { // Spigot
            this.advanceTree(world, pos, state, random);
        }

    }

    public void advanceTree(ServerLevel world, BlockPos pos, net.minecraft.world.level.block.state.BlockState state, RandomSource random) {
        if ((Integer) state.getValue(SaplingBlock.STAGE) == 0) {
            world.setBlock(pos, (net.minecraft.world.level.block.state.BlockState) state.cycle(SaplingBlock.STAGE), 4);
        } else {
            // CraftBukkit start
            if (world.captureTreeGeneration) {
                this.treeGrower.growTree(world, world.getChunkSource().getGenerator(), pos, state, random);
            } else {
                world.captureTreeGeneration = true;
                this.treeGrower.growTree(world, world.getChunkSource().getGenerator(), pos, state, random);
                world.captureTreeGeneration = false;
                if (world.capturedBlockStates.size() > 0) {
                    TreeType treeType = SaplingBlock.treeType;
                    SaplingBlock.treeType = null;
                    Location location = new Location(world.getWorld(), pos.getX(), pos.getY(), pos.getZ());
                    java.util.List<BlockState> blocks = new java.util.ArrayList<>(world.capturedBlockStates.values());
                    world.capturedBlockStates.clear();
                    StructureGrowEvent event = null;
                    if (treeType != null) {
                        event = new StructureGrowEvent(location, treeType, false, null, blocks);
                        org.bukkit.Bukkit.getPluginManager().callEvent(event);
                    }
                    if (event == null || !event.isCancelled()) {
                        for (BlockState blockstate : blocks) {
                            blockstate.update(true);
                        }
                    }
                }
            }
            // CraftBukkit end
        }

    }

    @Override
    public boolean isValidBonemealTarget(BlockGetter world, BlockPos pos, net.minecraft.world.level.block.state.BlockState state, boolean isClient) {
        return true;
    }

    @Override
    public boolean isBonemealSuccess(Level world, RandomSource random, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        return (double) world.random.nextFloat() < 0.45D;
    }

    @Override
    public void performBonemeal(ServerLevel world, RandomSource random, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        this.advanceTree(world, pos, state, random);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, net.minecraft.world.level.block.state.BlockState> builder) {
        builder.add(SaplingBlock.STAGE);
    }
}
