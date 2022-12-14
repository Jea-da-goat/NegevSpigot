package net.minecraft.world.entity.ai.behavior;

import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;

// CraftBukkit start
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.entity.EntityTargetEvent;
// CraftBukkit end

public class StopAttackingIfTargetInvalid<E extends Mob> extends Behavior<E> {

    private static final int TIMEOUT_TO_GET_WITHIN_ATTACK_RANGE = 200;
    private final Predicate<LivingEntity> stopAttackingWhen;
    private final BiConsumer<E, LivingEntity> onTargetErased;
    private final boolean canGrowTiredOfTryingToReachTarget;

    public StopAttackingIfTargetInvalid(Predicate<LivingEntity> alternativePredicate, BiConsumer<E, LivingEntity> forgetCallback, boolean shouldForgetIfTargetUnreachable) {
        super(ImmutableMap.of(MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT, MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE, MemoryStatus.REGISTERED));
        this.stopAttackingWhen = alternativePredicate;
        this.onTargetErased = forgetCallback;
        this.canGrowTiredOfTryingToReachTarget = shouldForgetIfTargetUnreachable;
    }

    public StopAttackingIfTargetInvalid(Predicate<LivingEntity> alternativePredicate, BiConsumer<E, LivingEntity> forgetCallback) {
        this(alternativePredicate, forgetCallback, true);
    }

    public StopAttackingIfTargetInvalid(Predicate<LivingEntity> alternativePredicate) {
        this(alternativePredicate, (entityinsentient, entityliving) -> {
        });
    }

    public StopAttackingIfTargetInvalid(BiConsumer<E, LivingEntity> forgetCallback) {
        this((entityliving) -> {
            return false;
        }, forgetCallback);
    }

    public StopAttackingIfTargetInvalid() {
        this((entityliving) -> {
            return false;
        }, (entityinsentient, entityliving) -> {
        });
    }

    protected void start(ServerLevel world, E entity, long time) {
        LivingEntity entityliving = this.getAttackTarget(entity);

        if (!entity.canAttack(entityliving)) {
            this.clearAttackTarget(entity, org.bukkit.event.entity.EntityTargetEvent.TargetReason.TARGET_INVALID); // Paper
        } else if (this.canGrowTiredOfTryingToReachTarget && StopAttackingIfTargetInvalid.isTiredOfTryingToReachTarget(entity)) {
            this.clearAttackTarget(entity, org.bukkit.event.entity.EntityTargetEvent.TargetReason.FORGOT_TARGET); // Paper
        } else if (this.isCurrentTargetDeadOrRemoved(entity)) {
            this.clearAttackTarget(entity, org.bukkit.event.entity.EntityTargetEvent.TargetReason.TARGET_DIED); // Paper
        } else if (this.isCurrentTargetInDifferentLevel(entity)) {
            this.clearAttackTarget(entity, org.bukkit.event.entity.EntityTargetEvent.TargetReason.TARGET_OTHER_LEVEL); // Paper
        } else if (this.stopAttackingWhen.test(this.getAttackTarget(entity))) {
            this.clearAttackTarget(entity, org.bukkit.event.entity.EntityTargetEvent.TargetReason.TARGET_INVALID); // Paper
        }
    }

    private boolean isCurrentTargetInDifferentLevel(E entity) {
        return this.getAttackTarget(entity).level != entity.level;
    }

    private LivingEntity getAttackTarget(E entity) {
        return (LivingEntity) entity.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).get();
    }

    private static <E extends LivingEntity> boolean isTiredOfTryingToReachTarget(E entity) {
        Optional<Long> optional = entity.getBrain().getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);

        return optional.isPresent() && entity.level.getGameTime() - (Long) optional.get() > 200L;
    }

    private boolean isCurrentTargetDeadOrRemoved(E entity) {
        Optional<LivingEntity> optional = entity.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET);

        return optional.isPresent() && !((LivingEntity) optional.get()).isAlive();
    }

    protected void clearAttackTarget(E entity, EntityTargetEvent.TargetReason reason) {
        // CraftBukkit start
        // Paper start - fix this event
        // LivingEntity old = entity.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        EntityTargetEvent event = CraftEventFactory.callEntityTargetLivingEvent(entity, null, reason);
        if (event.isCancelled()) {
            return;
        }
        // comment out, bad logic - bad
        /*if (event.getTarget() != null) {
            entity.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, ((CraftLivingEntity) event.getTarget()).getHandle());
            return;
        }*/
        // Paper end
        // CraftBukkit end
        this.onTargetErased.accept(entity, this.getAttackTarget(entity));
        entity.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
    }
}
