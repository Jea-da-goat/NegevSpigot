package net.minecraft.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.bukkit.event.block.BlockFromToEvent; // CraftBukkit

public class DragonEggBlock extends FallingBlock {

    protected static final VoxelShape SHAPE = Block.box(1.0D, 0.0D, 1.0D, 15.0D, 16.0D, 15.0D);

    public DragonEggBlock(BlockBehaviour.Properties settings) {
        super(settings);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return DragonEggBlock.SHAPE;
    }

    @Override
    public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        this.teleport(state, world, pos);
        return InteractionResult.sidedSuccess(world.isClientSide);
    }

    @Override
    public void attack(BlockState state, Level world, BlockPos pos, Player player) {
        this.teleport(state, world, pos);
    }

    private void teleport(BlockState state, Level world, BlockPos pos) {
        WorldBorder worldborder = world.getWorldBorder();

        for (int i = 0; i < 1000; ++i) {
            BlockPos blockposition1 = pos.offset(world.random.nextInt(16) - world.random.nextInt(16), world.random.nextInt(8) - world.random.nextInt(8), world.random.nextInt(16) - world.random.nextInt(16));

            if (world.getBlockState(blockposition1).isAir() && worldborder.isWithinBounds(blockposition1)) {
                // CraftBukkit start
                org.bukkit.block.Block from = world.getWorld().getBlockAt(pos.getX(), pos.getY(), pos.getZ());
                org.bukkit.block.Block to = world.getWorld().getBlockAt(blockposition1.getX(), blockposition1.getY(), blockposition1.getZ());
                BlockFromToEvent event = new BlockFromToEvent(from, to);
                org.bukkit.Bukkit.getPluginManager().callEvent(event);

                if (event.isCancelled()) {
                    return;
                }

                blockposition1 = new BlockPos(event.getToBlock().getX(), event.getToBlock().getY(), event.getToBlock().getZ());
                // CraftBukkit end
                if (world.isClientSide) {
                    for (int j = 0; j < 128; ++j) {
                        double d0 = world.random.nextDouble();
                        float f = (world.random.nextFloat() - 0.5F) * 0.2F;
                        float f1 = (world.random.nextFloat() - 0.5F) * 0.2F;
                        float f2 = (world.random.nextFloat() - 0.5F) * 0.2F;
                        double d1 = Mth.lerp(d0, (double) blockposition1.getX(), (double) pos.getX()) + (world.random.nextDouble() - 0.5D) + 0.5D;
                        double d2 = Mth.lerp(d0, (double) blockposition1.getY(), (double) pos.getY()) + world.random.nextDouble() - 0.5D;
                        double d3 = Mth.lerp(d0, (double) blockposition1.getZ(), (double) pos.getZ()) + (world.random.nextDouble() - 0.5D) + 0.5D;

                        world.addParticle(ParticleTypes.PORTAL, d1, d2, d3, (double) f, (double) f1, (double) f2);
                    }
                } else {
                    world.setBlock(blockposition1, state, 2);
                    world.removeBlock(pos, false);
                }

                return;
            }
        }

    }

    @Override
    protected int getDelayAfterPlace() {
        return 5;
    }

    @Override
    public boolean isPathfindable(BlockState state, BlockGetter world, BlockPos pos, PathComputationType type) {
        return false;
    }
}
