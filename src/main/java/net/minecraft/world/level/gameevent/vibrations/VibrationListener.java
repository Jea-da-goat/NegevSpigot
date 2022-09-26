package net.minecraft.world.level.gameevent.vibrations;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.VibrationParticleOption;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.GameEventTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipBlockStateContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.bukkit.event.block.BlockReceiveGameEvent;
// CraftBukkit end

public class VibrationListener implements GameEventListener {

    protected final PositionSource listenerSource;
    public int listenerRange;
    protected final VibrationListener.VibrationListenerConfig config;
    @Nullable
    protected VibrationListener.ReceivingEvent receivingEvent;
    protected float receivingDistance;
    protected int travelTimeInTicks;

    public static Codec<VibrationListener> codec(VibrationListener.VibrationListenerConfig callback) {
        return RecordCodecBuilder.create((instance) -> {
            return instance.group(PositionSource.CODEC.fieldOf("source").forGetter((vibrationlistener) -> {
                return vibrationlistener.listenerSource;
            }), ExtraCodecs.NON_NEGATIVE_INT.fieldOf("range").forGetter((vibrationlistener) -> {
                return vibrationlistener.listenerRange;
            }), VibrationListener.ReceivingEvent.CODEC.optionalFieldOf("event").forGetter((vibrationlistener) -> {
                return Optional.ofNullable(vibrationlistener.receivingEvent);
            }), Codec.floatRange(0.0F, Float.MAX_VALUE).fieldOf("event_distance").orElse(0.0F).forGetter((vibrationlistener) -> {
                return vibrationlistener.receivingDistance;
            }), ExtraCodecs.NON_NEGATIVE_INT.fieldOf("event_delay").orElse(0).forGetter((vibrationlistener) -> {
                return vibrationlistener.travelTimeInTicks;
            })).apply(instance, (positionsource, integer, optional, ofloat, integer1) -> {
                return new VibrationListener(positionsource, integer, callback, (VibrationListener.ReceivingEvent) optional.orElse(null), ofloat, integer1); // CraftBukkit - decompile error
            });
        });
    }

    public VibrationListener(PositionSource positionSource, int range, VibrationListener.VibrationListenerConfig callback, @Nullable VibrationListener.ReceivingEvent vibration, float distance, int delay) {
        this.listenerSource = positionSource;
        this.listenerRange = range;
        this.config = callback;
        this.receivingEvent = vibration;
        this.receivingDistance = distance;
        this.travelTimeInTicks = delay;
    }

    public void tick(Level world) {
        if (world instanceof ServerLevel) {
            ServerLevel worldserver = (ServerLevel) world;

            if (this.receivingEvent != null) {
                --this.travelTimeInTicks;
                if (this.travelTimeInTicks <= 0) {
                    this.travelTimeInTicks = 0;
                    this.config.onSignalReceive(worldserver, this, new BlockPos(this.receivingEvent.pos), this.receivingEvent.gameEvent, (Entity) this.receivingEvent.getEntity(worldserver).orElse(null), (Entity) this.receivingEvent.getProjectileOwner(worldserver).orElse(null), this.receivingDistance); // CraftBukkit - decompile error
                    this.receivingEvent = null;
                }
            }
        }

    }

    @Override
    public PositionSource getListenerSource() {
        return this.listenerSource;
    }

    @Override
    public int getListenerRadius() {
        return this.listenerRange;
    }

    @Override
    public boolean handleGameEvent(ServerLevel world, GameEvent.Message event) {
        if (this.receivingEvent != null) {
            return false;
        } else {
            GameEvent gameevent = event.gameEvent();
            GameEvent.Context gameevent_a = event.context();

            if (!this.config.isValidVibration(gameevent, gameevent_a)) {
                return false;
            } else {
                Optional<Vec3> optional = this.listenerSource.getPosition(world);

                if (optional.isEmpty()) {
                    return false;
                } else {
                    Vec3 vec3d = event.source();
                    Vec3 vec3d1 = (Vec3) optional.get();

                    // CraftBukkit start
                    boolean defaultCancel = !this.config.shouldListen(world, this, new BlockPos(vec3d), gameevent, gameevent_a);
                    Entity entity = gameevent_a.sourceEntity();
                    BlockReceiveGameEvent event1 = new BlockReceiveGameEvent(org.bukkit.GameEvent.getByKey(CraftNamespacedKey.fromMinecraft(Registry.GAME_EVENT.getKey(gameevent))), CraftBlock.at(world, new BlockPos(vec3d1)), (entity == null) ? null : entity.getBukkitEntity());
                    event1.setCancelled(defaultCancel);
                    world.getCraftServer().getPluginManager().callEvent(event1);
                    if (event1.isCancelled()) {
                        // CraftBukkit end
                        return false;
                    } else if (VibrationListener.isOccluded(world, vec3d, vec3d1)) {
                        return false;
                    } else {
                        this.scheduleSignal(world, gameevent, gameevent_a, vec3d, vec3d1);
                        return true;
                    }
                }
            }
        }
    }

    private void scheduleSignal(ServerLevel world, GameEvent gameEvent, GameEvent.Context emitter, Vec3 start, Vec3 end) {
        this.receivingDistance = (float) start.distanceTo(end);
        this.receivingEvent = new VibrationListener.ReceivingEvent(gameEvent, this.receivingDistance, start, emitter.sourceEntity());
        this.travelTimeInTicks = Mth.floor(this.receivingDistance);
        world.sendParticles(new VibrationParticleOption(this.listenerSource, this.travelTimeInTicks), start.x, start.y, start.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        this.config.onSignalSchedule();
    }

    private static boolean isOccluded(Level world, Vec3 start, Vec3 end) {
        Vec3 vec3d2 = new Vec3((double) Mth.floor(start.x) + 0.5D, (double) Mth.floor(start.y) + 0.5D, (double) Mth.floor(start.z) + 0.5D);
        Vec3 vec3d3 = new Vec3((double) Mth.floor(end.x) + 0.5D, (double) Mth.floor(end.y) + 0.5D, (double) Mth.floor(end.z) + 0.5D);
        Direction[] aenumdirection = Direction.values();
        int i = aenumdirection.length;

        for (int j = 0; j < i; ++j) {
            Direction enumdirection = aenumdirection[j];
            Vec3 vec3d4 = vec3d2.relative(enumdirection, 9.999999747378752E-6D);

            if (world.isBlockInLine(new ClipBlockStateContext(vec3d4, vec3d3, (iblockdata) -> {
                return iblockdata.is(BlockTags.OCCLUDES_VIBRATION_SIGNALS);
            })).getType() != HitResult.Type.BLOCK) {
                return false;
            }
        }

        return true;
    }

    public interface VibrationListenerConfig {

        default TagKey<GameEvent> getListenableEvents() {
            return GameEventTags.VIBRATIONS;
        }

        default boolean canTriggerAvoidVibration() {
            return false;
        }

        default boolean isValidVibration(GameEvent gameEvent, GameEvent.Context emitter) {
            if (!gameEvent.is(this.getListenableEvents())) {
                return false;
            } else {
                Entity entity = emitter.sourceEntity();

                if (entity != null) {
                    if (entity.isSpectator()) {
                        return false;
                    }

                    if (entity.isSteppingCarefully() && gameEvent.is(GameEventTags.IGNORE_VIBRATIONS_SNEAKING)) {
                        if (this.canTriggerAvoidVibration() && entity instanceof ServerPlayer) {
                            ServerPlayer entityplayer = (ServerPlayer) entity;

                            CriteriaTriggers.AVOID_VIBRATION.trigger(entityplayer);
                        }

                        return false;
                    }

                    if (entity.dampensVibrations()) {
                        return false;
                    }
                }

                return emitter.affectedState() != null ? !emitter.affectedState().is(BlockTags.DAMPENS_VIBRATIONS) : true;
            }
        }

        boolean shouldListen(ServerLevel world, GameEventListener listener, BlockPos pos, GameEvent event, GameEvent.Context emitter);

        void onSignalReceive(ServerLevel world, GameEventListener listener, BlockPos pos, GameEvent event, @Nullable Entity entity, @Nullable Entity sourceEntity, float distance);

        default void onSignalSchedule() {}
    }

    public static record ReceivingEvent(GameEvent gameEvent, float distance, Vec3 pos, @Nullable UUID uuid, @Nullable UUID projectileOwnerUuid, @Nullable Entity entity) {

        public static final Codec<VibrationListener.ReceivingEvent> CODEC = RecordCodecBuilder.create((instance) -> {
            return instance.group(Registry.GAME_EVENT.byNameCodec().fieldOf("game_event").forGetter(VibrationListener.ReceivingEvent::gameEvent), Codec.floatRange(0.0F, Float.MAX_VALUE).fieldOf("distance").forGetter(VibrationListener.ReceivingEvent::distance), Vec3.CODEC.fieldOf("pos").forGetter(VibrationListener.ReceivingEvent::pos), ExtraCodecs.UUID.optionalFieldOf("source").forGetter((vibrationlistener_a) -> {
                return Optional.ofNullable(vibrationlistener_a.uuid());
            }), ExtraCodecs.UUID.optionalFieldOf("projectile_owner").forGetter((vibrationlistener_a) -> {
                return Optional.ofNullable(vibrationlistener_a.projectileOwnerUuid());
            })).apply(instance, (gameevent, ofloat, vec3d, optional, optional1) -> {
                return new VibrationListener.ReceivingEvent(gameevent, ofloat, vec3d, (UUID) optional.orElse(null), (UUID) optional1.orElse(null)); // CraftBukkit - decompile error
            });
        });

        public ReceivingEvent(GameEvent gameEvent, float distance, Vec3 pos, @Nullable UUID uuid, @Nullable UUID projectileOwnerUuid) {
            this(gameEvent, distance, pos, uuid, projectileOwnerUuid, (Entity) null);
        }

        public ReceivingEvent(GameEvent gameEvent, float distance, Vec3 pos, @Nullable Entity entity) {
            this(gameEvent, distance, pos, entity == null ? null : entity.getUUID(), getProjectileOwner(entity), entity);
        }

        @Nullable
        private static UUID getProjectileOwner(@Nullable Entity entity) {
            if (entity instanceof Projectile) {
                Projectile iprojectile = (Projectile) entity;

                if (iprojectile.getOwner() != null) {
                    return iprojectile.getOwner().getUUID();
                }
            }

            return null;
        }

        public Optional<Entity> getEntity(ServerLevel world) {
            return Optional.ofNullable(this.entity).or(() -> {
                Optional<UUID> optional = Optional.ofNullable(this.uuid); // CraftBukkit - decompile error

                Objects.requireNonNull(world);
                return optional.map(world::getEntity);
            });
        }

        public Optional<Entity> getProjectileOwner(ServerLevel world) {
            return this.getEntity(world).filter((entity) -> {
                return entity instanceof Projectile;
            }).map((entity) -> {
                return (Projectile) entity;
            }).map(Projectile::getOwner).or(() -> {
                Optional<UUID> optional = Optional.ofNullable(this.projectileOwnerUuid); // CraftBukkit - decompile error

                Objects.requireNonNull(world);
                return optional.map(world::getEntity);
            });
        }
    }
}
