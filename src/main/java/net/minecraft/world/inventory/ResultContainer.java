package net.minecraft.world.inventory;

import java.util.Iterator;
import javax.annotation.Nullable;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
// CraftBukkit start
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftHumanEntity;
import org.bukkit.entity.HumanEntity;
// CraftBukkit end

public class ResultContainer implements Container, RecipeHolder {

    private final NonNullList<ItemStack> itemStacks;
    @Nullable
    private Recipe<?> recipeUsed;

    // CraftBukkit start
    private int maxStack = MAX_STACK;

    public java.util.List<ItemStack> getContents() {
        return this.itemStacks;
    }

    public org.bukkit.inventory.InventoryHolder getOwner() {
        return null; // Result slots don't get an owner
    }

    // Don't need a transaction; the InventoryCrafting keeps track of it for us
    public void onOpen(CraftHumanEntity who) {}
    public void onClose(CraftHumanEntity who) {}
    public java.util.List<HumanEntity> getViewers() {
        return new java.util.ArrayList<HumanEntity>();
    }

    @Override
    public int getMaxStackSize() {
        return this.maxStack;
    }

    public void setMaxStackSize(int size) {
        this.maxStack = size;
    }

    @Override
    public Location getLocation() {
        return null;
    }
    // CraftBukkit end

    public ResultContainer() {
        this.itemStacks = NonNullList.withSize(1, ItemStack.EMPTY);
    }

    @Override
    public int getContainerSize() {
        return 1;
    }

    @Override
    public boolean isEmpty() {
        Iterator iterator = this.itemStacks.iterator();

        ItemStack itemstack;

        do {
            if (!iterator.hasNext()) {
                return true;
            }

            itemstack = (ItemStack) iterator.next();
        } while (itemstack.isEmpty());

        return false;
    }

    @Override
    public ItemStack getItem(int slot) {
        return (ItemStack) this.itemStacks.get(0);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return ContainerHelper.takeItem(this.itemStacks, 0);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(this.itemStacks, 0);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        this.itemStacks.set(0, stack);
    }

    @Override
    public void setChanged() {}

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        this.itemStacks.clear();
    }

    @Override
    public void setRecipeUsed(@Nullable Recipe<?> recipe) {
        this.recipeUsed = recipe;
    }

    @Nullable
    @Override
    public Recipe<?> getRecipeUsed() {
        return this.recipeUsed;
    }
}
