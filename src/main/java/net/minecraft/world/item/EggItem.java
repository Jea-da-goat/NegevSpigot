package net.minecraft.world.item;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownEgg;
import net.minecraft.world.level.Level;

public class EggItem extends Item {

    public EggItem(Item.Properties settings) {
        super(settings);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level world, Player user, InteractionHand hand) {
        ItemStack itemstack = user.getItemInHand(hand);

        // world.playSound((EntityHuman) null, entityhuman.getX(), entityhuman.getY(), entityhuman.getZ(), SoundEffects.EGG_THROW, SoundCategory.PLAYERS, 0.5F, 0.4F / (world.getRandom().nextFloat() * 0.4F + 0.8F)); // CraftBukkit - moved down
        if (!world.isClientSide) {
            ThrownEgg entityegg = new ThrownEgg(world, user);

            entityegg.setItem(itemstack);
            entityegg.shootFromRotation(user, user.getXRot(), user.getYRot(), 0.0F, 1.5F, 1.0F);
            // Paper start
            com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent event = new com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent((org.bukkit.entity.Player) user.getBukkitEntity(), org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(itemstack), (org.bukkit.entity.Projectile) entityegg.getBukkitEntity());
            if (event.callEvent() && world.addFreshEntity(entityegg)) {
                if (event.shouldConsume() && !user.getAbilities().instabuild) {
                    itemstack.shrink(1);
                } else if (user instanceof net.minecraft.server.level.ServerPlayer) {
                    ((net.minecraft.server.level.ServerPlayer) user).getBukkitEntity().updateInventory();
                }

                world.playSound((Player) null, user.getX(), user.getY(), user.getZ(), net.minecraft.sounds.SoundEvents.EGG_THROW, net.minecraft.sounds.SoundSource.PLAYERS, 0.5F, 0.4F / (net.minecraft.world.entity.Entity.SHARED_RANDOM.nextFloat() * 0.4F + 0.8F));
                user.awardStat(Stats.ITEM_USED.get(this));
            } else {
                if (user instanceof net.minecraft.server.level.ServerPlayer) {
                    ((net.minecraft.server.level.ServerPlayer) user).getBukkitEntity().updateInventory();
                }
                return InteractionResultHolder.fail(itemstack);
            }
            // Paper end
        }
        world.playSound((Player) null, user.getX(), user.getY(), user.getZ(), SoundEvents.EGG_THROW, SoundSource.PLAYERS, 0.5F, 0.4F / (world.getRandom().nextFloat() * 0.4F + 0.8F));

        /* // Paper start - moved up
        user.awardStat(Stats.ITEM_USED.get(this));
        if (!user.getAbilities().instabuild) {
            itemstack.shrink(1);
        }
        */ // Paper end

        return InteractionResultHolder.sidedSuccess(itemstack, world.isClientSide());
    }
}
