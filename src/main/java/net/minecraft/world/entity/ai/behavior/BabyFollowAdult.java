package net.minecraft.world.entity.ai.behavior;

import com.google.common.collect.ImmutableMap;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;

// CraftBukkit start
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
// CraftBukkit end

public class BabyFollowAdult<E extends AgeableMob> extends Behavior<E> {

    private final UniformInt followRange;
    private final Function<LivingEntity, Float> speedModifier;

    public BabyFollowAdult(UniformInt executionRange, float speed) {
        this(executionRange, (entityliving) -> {
            return speed;
        });
    }

    public BabyFollowAdult(UniformInt executionRange, Function<LivingEntity, Float> speed) {
        super(ImmutableMap.of(MemoryModuleType.NEAREST_VISIBLE_ADULT, MemoryStatus.VALUE_PRESENT, MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT));
        this.followRange = executionRange;
        this.speedModifier = speed;
    }

    protected boolean checkExtraStartConditions(ServerLevel world, E entity) {
        if (!entity.isBaby()) {
            return false;
        } else {
            AgeableMob entityageable = this.getNearestAdult(entity);

            return entity.closerThan(entityageable, (double) (this.followRange.getMaxValue() + 1)) && !entity.closerThan(entityageable, (double) this.followRange.getMinValue());
        }
    }

    protected void start(ServerLevel world, E entity, long time) {
        // CraftBukkit start
        EntityTargetLivingEntityEvent event = CraftEventFactory.callEntityTargetLivingEvent(entity, this.getNearestAdult(entity), EntityTargetEvent.TargetReason.FOLLOW_LEADER);
        if (event.isCancelled()) {
            return;
        }
        if (event.getTarget() != null) {
            BehaviorUtils.setWalkAndLookTargetMemories(entity, ((CraftLivingEntity) event.getTarget()).getHandle(), this.speedModifier.apply(entity), this.followRange.getMinValue() - 1);
        } else {
            entity.getBrain().eraseMemory(MemoryModuleType.NEAREST_VISIBLE_ADULT);
        }
        // CraftBukkit end
    }

    private AgeableMob getNearestAdult(E entity) {
        return (AgeableMob) entity.getBrain().getMemory(MemoryModuleType.NEAREST_VISIBLE_ADULT).get();
    }
}
