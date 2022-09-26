package net.minecraft.world.entity.ai.behavior;

import com.google.common.collect.ImmutableMap;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.item.ItemEntity;

public class GoToWantedItem<E extends LivingEntity> extends Behavior<E> {

    private final Predicate<E> predicate;
    private final int maxDistToWalk;
    private final float speedModifier;

    public GoToWantedItem(float speed, boolean requiresWalkTarget, int radius) {
        this((entityliving) -> {
            return true;
        }, speed, requiresWalkTarget, radius);
    }

    public GoToWantedItem(Predicate<E> startCondition, float speed, boolean requiresWalkTarget, int radius) {
        super(ImmutableMap.of(MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED, MemoryModuleType.WALK_TARGET, requiresWalkTarget ? MemoryStatus.REGISTERED : MemoryStatus.VALUE_ABSENT, MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM, MemoryStatus.VALUE_PRESENT));
        this.predicate = startCondition;
        this.maxDistToWalk = radius;
        this.speedModifier = speed;
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel world, E entity) {
        return !this.isOnPickupCooldown(entity) && this.predicate.test(entity) && this.getClosestLovedItem(entity).closerThan(entity, (double) this.maxDistToWalk);
    }

    @Override
    protected void start(ServerLevel world, E entity, long time) {
        // CraftBukkit start
        if (entity instanceof net.minecraft.world.entity.animal.allay.Allay) {
            Entity target = this.getClosestLovedItem(entity);
            org.bukkit.event.entity.EntityTargetEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callEntityTargetEvent(entity, target, org.bukkit.event.entity.EntityTargetEvent.TargetReason.CLOSEST_ENTITY);

            if (event.isCancelled()) {
                return;
            }

            target = (event.getTarget() == null) ? null : ((org.bukkit.craftbukkit.entity.CraftEntity) event.getTarget()).getHandle();
            if (target instanceof ItemEntity item) {
                entity.getBrain().setMemory(MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM, java.util.Optional.of(item));
                BehaviorUtils.setWalkAndLookTargetMemories(entity, target, this.speedModifier, 0);
            } else {
                entity.getBrain().eraseMemory(MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM);
            }
            return;
        }
        // CraftBukkit end
        BehaviorUtils.setWalkAndLookTargetMemories(entity, (Entity) this.getClosestLovedItem(entity), this.speedModifier, 0);
    }

    private boolean isOnPickupCooldown(E entity) {
        return entity.getBrain().checkMemory(MemoryModuleType.ITEM_PICKUP_COOLDOWN_TICKS, MemoryStatus.VALUE_PRESENT);
    }

    private ItemEntity getClosestLovedItem(E entity) {
        return (ItemEntity) entity.getBrain().getMemory(MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM).get();
    }
}
