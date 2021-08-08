package net.minecraft.server.level;

import com.google.common.collect.Lists;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
// CraftBukkit start
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.util.Mth;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerVelocityEvent;
// CraftBukkit end

public class ServerEntity {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int TOLERANCE_LEVEL_ROTATION = 1;
    private final ServerLevel level;
    private final Entity entity;
    private final int updateInterval;
    private final boolean trackDelta;
    private final Consumer<Packet<?>> broadcast;
    private final VecDeltaCodec positionCodec = new VecDeltaCodec();
    private int yRotp;
    private int xRotp;
    private int yHeadRotp;
    private Vec3 ap;
    private int tickCount;
    private int teleportDelay;
    private List<Entity> lastPassengers;
    private boolean wasRiding;
    private boolean wasOnGround;
    // CraftBukkit start
    final Set<ServerPlayerConnection> trackedPlayers; // Paper - private -> package

    public ServerEntity(ServerLevel worldserver, Entity entity, int i, boolean flag, Consumer<Packet<?>> consumer, Set<ServerPlayerConnection> trackedPlayers) {
        this.trackedPlayers = trackedPlayers;
        // CraftBukkit end
        this.ap = Vec3.ZERO;
        this.lastPassengers = com.google.common.collect.ImmutableList.of(); // Paper - optimize passenger checks
        this.level = worldserver;
        this.broadcast = consumer;
        this.entity = entity;
        this.updateInterval = i;
        this.trackDelta = flag;
        this.positionCodec.setBase(entity.trackingPosition());
        this.yRotp = Mth.floor(entity.getYRot() * 256.0F / 360.0F);
        this.xRotp = Mth.floor(entity.getXRot() * 256.0F / 360.0F);
        this.yHeadRotp = Mth.floor(entity.getYHeadRot() * 256.0F / 360.0F);
        this.wasOnGround = entity.isOnGround();
    }

    public void sendChanges() {
        List<Entity> list = this.entity.getPassengers();

        if (!list.equals(this.lastPassengers)) {
            this.lastPassengers = list;
            this.broadcastAndSend(new ClientboundSetPassengersPacket(this.entity)); // CraftBukkit
        }

        Entity entity = this.entity;

        if (entity instanceof ItemFrame) {
            ItemFrame entityitemframe = (ItemFrame) entity;

            if (true || this.tickCount % 10 == 0) { // CraftBukkit - Moved below, should always enter this block
                ItemStack itemstack = entityitemframe.getItem();

                if (this.tickCount % 10 == 0 && itemstack.getItem() instanceof MapItem) { // CraftBukkit - Moved this.tickCounter % 10 logic here so item frames do not enter the other blocks
                    Integer integer = MapItem.getMapId(itemstack);
                    MapItemSavedData worldmap = MapItem.getSavedData(integer, this.level);

                    if (worldmap != null) {
                        Iterator<ServerPlayerConnection> iterator = this.trackedPlayers.iterator(); // CraftBukkit

                        while (iterator.hasNext()) {
                            ServerPlayer entityplayer = iterator.next().getPlayer(); // CraftBukkit

                            worldmap.tickCarriedBy(entityplayer, itemstack);
                            Packet<?> packet = worldmap.getUpdatePacket(integer, entityplayer);

                            if (packet != null) {
                                entityplayer.connection.send(packet);
                            }
                        }
                    }
                }

                this.sendDirtyEntityData();
            }
        }

        if (this.tickCount % this.updateInterval == 0 || this.entity.hasImpulse || this.entity.getEntityData().isDirty()) {
            int i;
            int j;

            if (this.entity.isPassenger()) {
                i = Mth.floor(this.entity.getYRot() * 256.0F / 360.0F);
                j = Mth.floor(this.entity.getXRot() * 256.0F / 360.0F);
                boolean flag = Math.abs(i - this.yRotp) >= 1 || Math.abs(j - this.xRotp) >= 1;

                if (flag) {
                    this.broadcast.accept(new ClientboundMoveEntityPacket.Rot(this.entity.getId(), (byte) i, (byte) j, this.entity.isOnGround()));
                    this.yRotp = i;
                    this.xRotp = j;
                }

                this.positionCodec.setBase(this.entity.trackingPosition());
                this.sendDirtyEntityData();
                this.wasRiding = true;
            } else {
                ++this.teleportDelay;
                i = Mth.floor(this.entity.getYRot() * 256.0F / 360.0F);
                j = Mth.floor(this.entity.getXRot() * 256.0F / 360.0F);
                Vec3 vec3d = this.entity.trackingPosition();
                // Paper start - reduce allocation of Vec3D here
                Vec3 base = this.positionCodec.base;
                double vec3d_dx = vec3d.x - base.x;
                double vec3d_dy = vec3d.y - base.y;
                double vec3d_dz = vec3d.z - base.z;
                boolean flag1 = (vec3d_dx * vec3d_dx + vec3d_dy * vec3d_dy + vec3d_dz * vec3d_dz) >= 7.62939453125E-6D;
                // Paper end - reduce allocation of Vec3D here
                Packet<?> packet1 = null;
                boolean flag2 = flag1 || this.tickCount % 60 == 0;
                boolean flag3 = Math.abs(i - this.yRotp) >= 1 || Math.abs(j - this.xRotp) >= 1;

                if (this.tickCount > 0 || this.entity instanceof AbstractArrow) {
                    long k = this.positionCodec.encodeX(vec3d);
                    long l = this.positionCodec.encodeY(vec3d);
                    long i1 = this.positionCodec.encodeZ(vec3d);
                    boolean flag4 = k < -32768L || k > 32767L || l < -32768L || l > 32767L || i1 < -32768L || i1 > 32767L;

                    if (!flag4 && this.teleportDelay <= 400 && !this.wasRiding && this.wasOnGround == this.entity.isOnGround()) {
                        if ((!flag2 || !flag3) && !(this.entity instanceof AbstractArrow)) {
                            if (flag2) {
                                packet1 = new ClientboundMoveEntityPacket.Pos(this.entity.getId(), (short) ((int) k), (short) ((int) l), (short) ((int) i1), this.entity.isOnGround());
                            } else if (flag3) {
                                packet1 = new ClientboundMoveEntityPacket.Rot(this.entity.getId(), (byte) i, (byte) j, this.entity.isOnGround());
                            }
                        } else {
                            packet1 = new ClientboundMoveEntityPacket.PosRot(this.entity.getId(), (short) ((int) k), (short) ((int) l), (short) ((int) i1), (byte) i, (byte) j, this.entity.isOnGround());
                        }
                    } else {
                        this.wasOnGround = this.entity.isOnGround();
                        this.teleportDelay = 0;
                        packet1 = new ClientboundTeleportEntityPacket(this.entity);
                    }
                }

                if ((this.trackDelta || this.entity.hasImpulse || this.entity instanceof LivingEntity && ((LivingEntity) this.entity).isFallFlying()) && this.tickCount > 0) {
                    Vec3 vec3d1 = this.entity.getDeltaMovement();
                    double d0 = vec3d1.distanceToSqr(this.ap);

                    if (d0 > 1.0E-7D || d0 > 0.0D && vec3d1.lengthSqr() == 0.0D) {
                        this.ap = vec3d1;
                        this.broadcast.accept(new ClientboundSetEntityMotionPacket(this.entity.getId(), this.ap));
                    }
                }

                if (packet1 != null) {
                    this.broadcast.accept(packet1);
                }

                this.sendDirtyEntityData();
                if (flag2) {
                    this.positionCodec.setBase(vec3d);
                }

                if (flag3) {
                    this.yRotp = i;
                    this.xRotp = j;
                }

                this.wasRiding = false;
            }

            i = Mth.floor(this.entity.getYHeadRot() * 256.0F / 360.0F);
            if (Math.abs(i - this.yHeadRotp) >= 1) {
                this.broadcast.accept(new ClientboundRotateHeadPacket(this.entity, (byte) i));
                this.yHeadRotp = i;
            }

            this.entity.hasImpulse = false;
        }

        ++this.tickCount;
        if (this.entity.hurtMarked) {
            // CraftBukkit start - Create PlayerVelocity event
            boolean cancelled = false;

            if (this.entity instanceof ServerPlayer) {
                Player player = (Player) this.entity.getBukkitEntity();
                org.bukkit.util.Vector velocity = player.getVelocity();

                PlayerVelocityEvent event = new PlayerVelocityEvent(player, velocity.clone());
                this.entity.level.getCraftServer().getPluginManager().callEvent(event);

                if (event.isCancelled()) {
                    cancelled = true;
                } else if (!velocity.equals(event.getVelocity())) {
                    player.setVelocity(event.getVelocity());
                }
            }

            if (!cancelled) {
                this.broadcastAndSend(new ClientboundSetEntityMotionPacket(this.entity));
            }
            // CraftBukkit end
            this.entity.hurtMarked = false;
        }

    }

    public void removePairing(ServerPlayer player) {
        this.entity.stopSeenByPlayer(player);
        player.connection.send(new ClientboundRemoveEntitiesPacket(new int[]{this.entity.getId()}));
    }

    public void addPairing(ServerPlayer player) {
        ServerGamePacketListenerImpl playerconnection = player.connection;

        Objects.requireNonNull(player.connection);
        this.sendPairingData(playerconnection::send, player); // CraftBukkit - add player
        this.entity.startSeenByPlayer(player);
    }

    public void sendPairingData(Consumer<Packet<?>> consumer, ServerPlayer entityplayer) { // CraftBukkit - add player
        if (this.entity.isRemoved()) {
            // CraftBukkit start - Remove useless error spam, just return
            // EntityTrackerEntry.LOGGER.warn("Fetching packet for removed entity {}", this.entity);
            return;
            // CraftBukkit end
        }

        Packet<?> packet = this.entity.getAddEntityPacket();

        this.yHeadRotp = Mth.floor(this.entity.getYHeadRot() * 256.0F / 360.0F);
        consumer.accept(packet);
        if (!this.entity.getEntityData().isEmpty()) {
            consumer.accept(new ClientboundSetEntityDataPacket(this.entity.getId(), this.entity.getEntityData(), true));
        }

        boolean flag = this.trackDelta;

        if (this.entity instanceof LivingEntity) {
            Collection<AttributeInstance> collection = ((LivingEntity) this.entity).getAttributes().getSyncableAttributes();

            // CraftBukkit start - If sending own attributes send scaled health instead of current maximum health
            if (this.entity.getId() == entityplayer.getId()) {
                ((ServerPlayer) this.entity).getBukkitEntity().injectScaledMaxHealth(collection, false);
            }
            // CraftBukkit end

            if (!collection.isEmpty()) {
                consumer.accept(new ClientboundUpdateAttributesPacket(this.entity.getId(), collection));
            }

            if (((LivingEntity) this.entity).isFallFlying()) {
                flag = true;
            }
        }

        this.ap = this.entity.getDeltaMovement();
        if (flag && !(this.entity instanceof LivingEntity)) {
            consumer.accept(new ClientboundSetEntityMotionPacket(this.entity.getId(), this.ap));
        }

        if (this.entity instanceof LivingEntity) {
            List<Pair<EquipmentSlot, ItemStack>> list = Lists.newArrayList();
            EquipmentSlot[] aenumitemslot = EquipmentSlot.values();
            int i = aenumitemslot.length;

            for (int j = 0; j < i; ++j) {
                EquipmentSlot enumitemslot = aenumitemslot[j];
                ItemStack itemstack = ((LivingEntity) this.entity).getItemBySlot(enumitemslot);

                if (!itemstack.isEmpty()) {
                    list.add(Pair.of(enumitemslot, itemstack.copy()));
                }
            }

            if (!list.isEmpty()) {
                consumer.accept(new ClientboundSetEquipmentPacket(this.entity.getId(), list));
            }
            ((LivingEntity) this.entity).detectEquipmentUpdates(); // CraftBukkit - SPIGOT-3789: sync again immediately after sending
        }

        // CraftBukkit start - Fix for nonsensical head yaw
        this.yHeadRotp = Mth.floor(this.entity.getYHeadRot() * 256.0F / 360.0F);
        consumer.accept(new ClientboundRotateHeadPacket(this.entity, (byte) this.yHeadRotp));
        // CraftBukkit end

        if (this.entity instanceof LivingEntity) {
            LivingEntity entityliving = (LivingEntity) this.entity;
            Iterator iterator = entityliving.getActiveEffects().iterator();

            while (iterator.hasNext()) {
                MobEffectInstance mobeffect = (MobEffectInstance) iterator.next();

                consumer.accept(new ClientboundUpdateMobEffectPacket(this.entity.getId(), mobeffect));
            }
        }

        if (!this.entity.getPassengers().isEmpty()) {
            consumer.accept(new ClientboundSetPassengersPacket(this.entity));
        }

        if (this.entity.isPassenger()) {
            consumer.accept(new ClientboundSetPassengersPacket(this.entity.getVehicle()));
        }

        if (this.entity instanceof Mob) {
            Mob entityinsentient = (Mob) this.entity;

            if (entityinsentient.isLeashed()) {
                consumer.accept(new ClientboundSetEntityLinkPacket(entityinsentient, entityinsentient.getLeashHolder()));
            }
        }

    }

    private void sendDirtyEntityData() {
        SynchedEntityData datawatcher = this.entity.getEntityData();

        if (datawatcher.isDirty()) {
            this.broadcastAndSend(new ClientboundSetEntityDataPacket(this.entity.getId(), datawatcher, false));
        }

        if (this.entity instanceof LivingEntity) {
            Set<AttributeInstance> set = ((LivingEntity) this.entity).getAttributes().getDirtyAttributes();

            if (!set.isEmpty()) {
                // CraftBukkit start - Send scaled max health
                if (this.entity instanceof ServerPlayer) {
                    ((ServerPlayer) this.entity).getBukkitEntity().injectScaledMaxHealth(set, false);
                }
                // CraftBukkit end
                this.broadcastAndSend(new ClientboundUpdateAttributesPacket(this.entity.getId(), set));
            }

            set.clear();
        }

    }

    // Paper start - Add broadcast method
    void broadcast(Packet<?> packet) {
        this.broadcast.accept(packet);
    }
    // Paper end

    private void broadcastAndSend(Packet<?> packet) {
        this.broadcast.accept(packet);
        if (this.entity instanceof ServerPlayer) {
            ((ServerPlayer) this.entity).connection.send(packet);
        }

    }
}
