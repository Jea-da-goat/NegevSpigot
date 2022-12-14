package net.minecraft.world;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

// CraftBukkit start
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;

import org.bukkit.craftbukkit.entity.CraftHumanEntity;
import org.bukkit.entity.HumanEntity;
// CraftBukkit end

public class CompoundContainer implements Container {

    public final Container container1;
    public final Container container2;

    // CraftBukkit start - add fields and methods
    public List<HumanEntity> transaction = new java.util.ArrayList<HumanEntity>();

    public List<ItemStack> getContents() {
        List<ItemStack> result = new ArrayList<ItemStack>(this.getContainerSize());
        for (int i = 0; i < this.getContainerSize(); i++) {
            result.add(this.getItem(i));
        }
        return result;
    }

    public void onOpen(CraftHumanEntity who) {
        this.container1.onOpen(who);
        this.container2.onOpen(who);
        this.transaction.add(who);
    }

    public void onClose(CraftHumanEntity who) {
        this.container1.onClose(who);
        this.container2.onClose(who);
        this.transaction.remove(who);
    }

    public List<HumanEntity> getViewers() {
        return this.transaction;
    }

    public org.bukkit.inventory.InventoryHolder getOwner() {
        return null; // This method won't be called since CraftInventoryDoubleChest doesn't defer to here
    }

    public void setMaxStackSize(int size) {
        this.container1.setMaxStackSize(size);
        this.container2.setMaxStackSize(size);
    }

    @Override
    public Location getLocation() {
        return this.container1.getLocation(); // TODO: right?
    }
    // CraftBukkit end

    public CompoundContainer(Container first, Container second) {
        this.container1 = first;
        this.container2 = second;
    }

    @Override
    public int getContainerSize() {
        return this.container1.getContainerSize() + this.container2.getContainerSize();
    }

    @Override
    public boolean isEmpty() {
        return this.container1.isEmpty() && this.container2.isEmpty();
    }

    public boolean contains(Container inventory) {
        return this.container1 == inventory || this.container2 == inventory;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= this.container1.getContainerSize() ? this.container2.getItem(slot - this.container1.getContainerSize()) : this.container1.getItem(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return slot >= this.container1.getContainerSize() ? this.container2.removeItem(slot - this.container1.getContainerSize(), amount) : this.container1.removeItem(slot, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return slot >= this.container1.getContainerSize() ? this.container2.removeItemNoUpdate(slot - this.container1.getContainerSize()) : this.container1.removeItemNoUpdate(slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot >= this.container1.getContainerSize()) {
            this.container2.setItem(slot - this.container1.getContainerSize(), stack);
        } else {
            this.container1.setItem(slot, stack);
        }

    }

    @Override
    public int getMaxStackSize() {
        return Math.min(this.container1.getMaxStackSize(), this.container2.getMaxStackSize()); // CraftBukkit - check both sides
    }

    @Override
    public void setChanged() {
        this.container1.setChanged();
        this.container2.setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return this.container1.stillValid(player) && this.container2.stillValid(player);
    }

    @Override
    public void startOpen(Player player) {
        this.container1.startOpen(player);
        this.container2.startOpen(player);
    }

    @Override
    public void stopOpen(Player player) {
        this.container1.stopOpen(player);
        this.container2.stopOpen(player);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot >= this.container1.getContainerSize() ? this.container2.canPlaceItem(slot - this.container1.getContainerSize(), stack) : this.container1.canPlaceItem(slot, stack);
    }

    @Override
    public void clearContent() {
        this.container1.clearContent();
        this.container2.clearContent();
    }
}
