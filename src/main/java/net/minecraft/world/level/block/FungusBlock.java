package net.minecraft.world.level.block;

import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.HugeFungusConfiguration;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class FungusBlock extends BushBlock implements BonemealableBlock {

    protected static final VoxelShape SHAPE = Block.box(4.0D, 0.0D, 4.0D, 12.0D, 9.0D, 12.0D);
    private static final double BONEMEAL_SUCCESS_PROBABILITY = 0.4D;
    private final Supplier<Holder<ConfiguredFeature<HugeFungusConfiguration, ?>>> feature;

    protected FungusBlock(BlockBehaviour.Properties settings, Supplier<Holder<ConfiguredFeature<HugeFungusConfiguration, ?>>> feature) {
        super(settings);
        this.feature = feature;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return FungusBlock.SHAPE;
    }

    @Override
    protected boolean mayPlaceOn(BlockState floor, BlockGetter world, BlockPos pos) {
        return floor.is(BlockTags.NYLIUM) || floor.is(Blocks.MYCELIUM) || floor.is(Blocks.SOUL_SOIL) || super.mayPlaceOn(floor, world, pos);
    }

    @Override
    public boolean isValidBonemealTarget(BlockGetter world, BlockPos pos, BlockState state, boolean isClient) {
        Block block = ((HugeFungusConfiguration) ((ConfiguredFeature) ((Holder) this.feature.get()).value()).config()).validBaseState.getBlock();
        BlockState iblockdata1 = world.getBlockState(pos.below());

        return iblockdata1.is(block);
    }

    @Override
    public boolean isBonemealSuccess(Level world, RandomSource random, BlockPos pos, BlockState state) {
        return (double) random.nextFloat() < 0.4D;
    }

    @Override
    public void performBonemeal(ServerLevel world, RandomSource random, BlockPos pos, BlockState state) {
        // CraftBukkit start
        if (this == Blocks.WARPED_FUNGUS) {
            SaplingBlock.treeType = org.bukkit.TreeType.WARPED_FUNGUS;
        } else if (this == Blocks.CRIMSON_FUNGUS) {
            SaplingBlock.treeType = org.bukkit.TreeType.CRIMSON_FUNGUS;
        }
        // CraftBukkit end
        ((ConfiguredFeature) ((Holder) this.feature.get()).value()).place(world, world.getChunkSource().getGenerator(), random, pos);
    }
}
