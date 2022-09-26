package net.minecraft.world.level.block;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SculkSensorBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.SculkSensorPhase;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
// CraftBukkit start
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.event.block.BlockRedstoneEvent;
// CraftBukkit end

public class SculkSensorBlock extends BaseEntityBlock implements SimpleWaterloggedBlock {

    public static final int ACTIVE_TICKS = 40;
    public static final int COOLDOWN_TICKS = 1;
    public static final Object2IntMap<GameEvent> VIBRATION_FREQUENCY_FOR_EVENT = Object2IntMaps.unmodifiable((Object2IntMap) Util.make(new Object2IntOpenHashMap(), (object2intopenhashmap) -> {
        object2intopenhashmap.put(GameEvent.STEP, 1);
        object2intopenhashmap.put(GameEvent.FLAP, 2);
        object2intopenhashmap.put(GameEvent.SWIM, 3);
        object2intopenhashmap.put(GameEvent.ELYTRA_GLIDE, 4);
        object2intopenhashmap.put(GameEvent.HIT_GROUND, 5);
        object2intopenhashmap.put(GameEvent.TELEPORT, 5);
        object2intopenhashmap.put(GameEvent.SPLASH, 6);
        object2intopenhashmap.put(GameEvent.ENTITY_SHAKE, 6);
        object2intopenhashmap.put(GameEvent.BLOCK_CHANGE, 6);
        object2intopenhashmap.put(GameEvent.NOTE_BLOCK_PLAY, 6);
        object2intopenhashmap.put(GameEvent.PROJECTILE_SHOOT, 7);
        object2intopenhashmap.put(GameEvent.DRINK, 7);
        object2intopenhashmap.put(GameEvent.PRIME_FUSE, 7);
        object2intopenhashmap.put(GameEvent.PROJECTILE_LAND, 8);
        object2intopenhashmap.put(GameEvent.EAT, 8);
        object2intopenhashmap.put(GameEvent.ENTITY_INTERACT, 8);
        object2intopenhashmap.put(GameEvent.ENTITY_DAMAGE, 8);
        object2intopenhashmap.put(GameEvent.EQUIP, 9);
        object2intopenhashmap.put(GameEvent.SHEAR, 9);
        object2intopenhashmap.put(GameEvent.ENTITY_ROAR, 9);
        object2intopenhashmap.put(GameEvent.BLOCK_CLOSE, 10);
        object2intopenhashmap.put(GameEvent.BLOCK_DEACTIVATE, 10);
        object2intopenhashmap.put(GameEvent.BLOCK_DETACH, 10);
        object2intopenhashmap.put(GameEvent.DISPENSE_FAIL, 10);
        object2intopenhashmap.put(GameEvent.BLOCK_OPEN, 11);
        object2intopenhashmap.put(GameEvent.BLOCK_ACTIVATE, 11);
        object2intopenhashmap.put(GameEvent.BLOCK_ATTACH, 11);
        object2intopenhashmap.put(GameEvent.ENTITY_PLACE, 12);
        object2intopenhashmap.put(GameEvent.BLOCK_PLACE, 12);
        object2intopenhashmap.put(GameEvent.FLUID_PLACE, 12);
        object2intopenhashmap.put(GameEvent.ENTITY_DIE, 13);
        object2intopenhashmap.put(GameEvent.BLOCK_DESTROY, 13);
        object2intopenhashmap.put(GameEvent.FLUID_PICKUP, 13);
        object2intopenhashmap.put(GameEvent.ITEM_INTERACT_FINISH, 14);
        object2intopenhashmap.put(GameEvent.CONTAINER_CLOSE, 14);
        object2intopenhashmap.put(GameEvent.PISTON_CONTRACT, 14);
        object2intopenhashmap.put(GameEvent.PISTON_EXTEND, 15);
        object2intopenhashmap.put(GameEvent.CONTAINER_OPEN, 15);
        object2intopenhashmap.put(GameEvent.ITEM_INTERACT_START, 15);
        object2intopenhashmap.put(GameEvent.EXPLODE, 15);
        object2intopenhashmap.put(GameEvent.LIGHTNING_STRIKE, 15);
        object2intopenhashmap.put(GameEvent.INSTRUMENT_PLAY, 15);
    }));
    public static final EnumProperty<SculkSensorPhase> PHASE = BlockStateProperties.SCULK_SENSOR_PHASE;
    public static final IntegerProperty POWER = BlockStateProperties.POWER;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    protected static final VoxelShape SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 8.0D, 16.0D);
    private final int listenerRange;

    public SculkSensorBlock(BlockBehaviour.Properties settings, int range) {
        super(settings);
        this.registerDefaultState((BlockState) ((BlockState) ((BlockState) ((BlockState) this.stateDefinition.any()).setValue(SculkSensorBlock.PHASE, SculkSensorPhase.INACTIVE)).setValue(SculkSensorBlock.POWER, 0)).setValue(SculkSensorBlock.WATERLOGGED, false));
        this.listenerRange = range;
    }

    public int getListenerRange() {
        return this.listenerRange;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockPos blockposition = ctx.getClickedPos();
        FluidState fluid = ctx.getLevel().getFluidState(blockposition);

        return (BlockState) this.defaultBlockState().setValue(SculkSensorBlock.WATERLOGGED, fluid.getType() == Fluids.WATER);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return (Boolean) state.getValue(SculkSensorBlock.WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    public void tick(BlockState state, ServerLevel world, BlockPos pos, RandomSource random) {
        if (SculkSensorBlock.getPhase(state) != SculkSensorPhase.ACTIVE) {
            if (SculkSensorBlock.getPhase(state) == SculkSensorPhase.COOLDOWN) {
                world.setBlock(pos, (BlockState) state.setValue(SculkSensorBlock.PHASE, SculkSensorPhase.INACTIVE), 3);
            }

        } else {
            SculkSensorBlock.deactivate(world, pos, state);
        }
    }

    @Override
    public void stepOn(Level world, BlockPos pos, BlockState state, Entity entity) {
        if (!world.isClientSide() && SculkSensorBlock.canActivate(state) && entity.getType() != EntityType.WARDEN) {
            // CraftBukkit start
            org.bukkit.event.Cancellable cancellable;
            if (entity instanceof Player) {
                cancellable = org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerInteractEvent((Player) entity, org.bukkit.event.block.Action.PHYSICAL, pos, null, null, null);
            } else {
                cancellable = new org.bukkit.event.entity.EntityInteractEvent(entity.getBukkitEntity(), world.getWorld().getBlockAt(pos.getX(), pos.getY(), pos.getZ()));
                world.getCraftServer().getPluginManager().callEvent((org.bukkit.event.entity.EntityInteractEvent) cancellable);
            }
            if (cancellable.isCancelled()) {
                return;
            }
            // CraftBukkit end
            BlockEntity tileentity = world.getBlockEntity(pos);

            if (tileentity instanceof SculkSensorBlockEntity) {
                SculkSensorBlockEntity sculksensorblockentity = (SculkSensorBlockEntity) tileentity;

                sculksensorblockentity.setLastVibrationFrequency(SculkSensorBlock.VIBRATION_FREQUENCY_FOR_EVENT.get(GameEvent.STEP));
            }

            SculkSensorBlock.activate(entity, world, pos, state, 15);
        }

        super.stepOn(world, pos, state, entity);
    }

    @Override
    public void onPlace(BlockState state, Level world, BlockPos pos, BlockState oldState, boolean notify) {
        if (!world.isClientSide() && !state.is(oldState.getBlock())) {
            if ((Integer) state.getValue(SculkSensorBlock.POWER) > 0 && !world.getBlockTicks().hasScheduledTick(pos, this)) {
                world.setBlock(pos, (BlockState) state.setValue(SculkSensorBlock.POWER, 0), 18);
            }

            world.scheduleTick(new BlockPos(pos), state.getBlock(), 1);
        }
    }

    @Override
    public void onRemove(BlockState state, Level world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock())) {
            if (SculkSensorBlock.getPhase(state) == SculkSensorPhase.ACTIVE) {
                SculkSensorBlock.updateNeighbours(world, pos);
            }

            super.onRemove(state, world, pos, newState, moved);
        }
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor world, BlockPos pos, BlockPos neighborPos) {
        if ((Boolean) state.getValue(SculkSensorBlock.WATERLOGGED)) {
            world.scheduleTick(pos, (Fluid) Fluids.WATER, Fluids.WATER.getTickDelay(world));
        }

        return super.updateShape(state, direction, neighborState, world, pos, neighborPos);
    }

    private static void updateNeighbours(Level world, BlockPos pos) {
        world.updateNeighborsAt(pos, Blocks.SCULK_SENSOR);
        world.updateNeighborsAt(pos.relative(Direction.UP.getOpposite()), Blocks.SCULK_SENSOR);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SculkSensorBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> GameEventListener getListener(ServerLevel world, T blockEntity) {
        return blockEntity instanceof SculkSensorBlockEntity ? ((SculkSensorBlockEntity) blockEntity).getListener() : null;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
        return !world.isClientSide ? createTickerHelper(type, BlockEntityType.SCULK_SENSOR, (world1, blockposition, iblockdata1, sculksensorblockentity) -> {
            sculksensorblockentity.getListener().tick(world1);
        }) : null;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return SculkSensorBlock.SHAPE;
    }

    @Override
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    public int getSignal(BlockState state, BlockGetter world, BlockPos pos, Direction direction) {
        return (Integer) state.getValue(SculkSensorBlock.POWER);
    }

    public static SculkSensorPhase getPhase(BlockState state) {
        return (SculkSensorPhase) state.getValue(SculkSensorBlock.PHASE);
    }

    public static boolean canActivate(BlockState state) {
        return SculkSensorBlock.getPhase(state) == SculkSensorPhase.INACTIVE;
    }

    public static void deactivate(Level world, BlockPos pos, BlockState state) {
        // CraftBukkit start
        BlockRedstoneEvent eventRedstone = new BlockRedstoneEvent(CraftBlock.at(world, pos), state.getValue(SculkSensorBlock.POWER), 0);
        world.getCraftServer().getPluginManager().callEvent(eventRedstone);

        if (eventRedstone.getNewCurrent() > 0) {
            world.setBlock(pos, state.setValue(SculkSensorBlock.POWER, eventRedstone.getNewCurrent()), 3);
            return;
        }
        // CraftBukkit end
        world.setBlock(pos, (BlockState) ((BlockState) state.setValue(SculkSensorBlock.PHASE, SculkSensorPhase.COOLDOWN)).setValue(SculkSensorBlock.POWER, 0), 3);
        world.scheduleTick(pos, state.getBlock(), 1);
        if (!(Boolean) state.getValue(SculkSensorBlock.WATERLOGGED)) {
            world.playSound((Player) null, pos, SoundEvents.SCULK_CLICKING_STOP, SoundSource.BLOCKS, 1.0F, world.random.nextFloat() * 0.2F + 0.8F);
        }

        SculkSensorBlock.updateNeighbours(world, pos);
    }

    public static void activate(@Nullable Entity entity, Level world, BlockPos pos, BlockState state, int power) {
        // CraftBukkit start
        BlockRedstoneEvent eventRedstone = new BlockRedstoneEvent(CraftBlock.at(world, pos), state.getValue(SculkSensorBlock.POWER), power);
        world.getCraftServer().getPluginManager().callEvent(eventRedstone);

        if (eventRedstone.getNewCurrent() <= 0) {
            return;
        }
        power = eventRedstone.getNewCurrent();
        // CraftBukkit end
        world.setBlock(pos, (BlockState) ((BlockState) state.setValue(SculkSensorBlock.PHASE, SculkSensorPhase.ACTIVE)).setValue(SculkSensorBlock.POWER, power), 3);
        world.scheduleTick(pos, state.getBlock(), 40);
        SculkSensorBlock.updateNeighbours(world, pos);
        world.gameEvent(entity, GameEvent.SCULK_SENSOR_TENDRILS_CLICKING, pos);
        if (!(Boolean) state.getValue(SculkSensorBlock.WATERLOGGED)) {
            world.playSound((Player) null, (double) pos.getX() + 0.5D, (double) pos.getY() + 0.5D, (double) pos.getZ() + 0.5D, SoundEvents.SCULK_CLICKING, SoundSource.BLOCKS, 1.0F, world.random.nextFloat() * 0.2F + 0.8F);
        }

    }

    @Override
    public void animateTick(BlockState state, Level world, BlockPos pos, RandomSource random) {
        if (SculkSensorBlock.getPhase(state) == SculkSensorPhase.ACTIVE) {
            Direction enumdirection = Direction.getRandom(random);

            if (enumdirection != Direction.UP && enumdirection != Direction.DOWN) {
                double d0 = (double) pos.getX() + 0.5D + (enumdirection.getStepX() == 0 ? 0.5D - random.nextDouble() : (double) enumdirection.getStepX() * 0.6D);
                double d1 = (double) pos.getY() + 0.25D;
                double d2 = (double) pos.getZ() + 0.5D + (enumdirection.getStepZ() == 0 ? 0.5D - random.nextDouble() : (double) enumdirection.getStepZ() * 0.6D);
                double d3 = (double) random.nextFloat() * 0.04D;

                world.addParticle(DustColorTransitionOptions.SCULK_TO_REDSTONE, d0, d1, d2, 0.0D, d3, 0.0D);
            }
        }
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SculkSensorBlock.PHASE, SculkSensorBlock.POWER, SculkSensorBlock.WATERLOGGED);
    }

    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level world, BlockPos pos) {
        BlockEntity tileentity = world.getBlockEntity(pos);

        if (tileentity instanceof SculkSensorBlockEntity) {
            SculkSensorBlockEntity sculksensorblockentity = (SculkSensorBlockEntity) tileentity;

            return SculkSensorBlock.getPhase(state) == SculkSensorPhase.ACTIVE ? sculksensorblockentity.getLastVibrationFrequency() : 0;
        } else {
            return 0;
        }
    }

    @Override
    public boolean isPathfindable(BlockState state, BlockGetter world, BlockPos pos, PathComputationType type) {
        return false;
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }

    @Override
    public void spawnAfterBreak(BlockState state, ServerLevel world, BlockPos pos, ItemStack stack, boolean dropExperience) {
        super.spawnAfterBreak(state, world, pos, stack, dropExperience);
        // CraftBukkit start - Delegate to getExpDrop
    }

    @Override
    public int getExpDrop(BlockState iblockdata, ServerLevel worldserver, BlockPos blockposition, ItemStack itemstack, boolean flag) {
        if (flag) {
            return this.tryDropExperience(worldserver, blockposition, itemstack, ConstantInt.of(5));
        }

        return 0;
        // CraftBukkit end
    }
}
