package com.momosoftworks.coldsweat.compat.kubejs.event.builder;

import com.momosoftworks.coldsweat.data.codec.configuration.DryingItemData;
import com.momosoftworks.coldsweat.data.codec.impl.ConfigData;
import com.momosoftworks.coldsweat.data.codec.requirement.EntityRequirement;
import com.momosoftworks.coldsweat.data.codec.requirement.ItemRequirement;
import com.momosoftworks.coldsweat.util.serialization.ConfigHelper;
import com.momosoftworks.coldsweat.util.serialization.RegistryHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

public class DryingItemBuilderJS
{
    public final Set<Item> items = new HashSet<>();
    public ItemStack result = ItemStack.EMPTY;
    public SoundEvent sound = SoundEvents.WET_GRASS_STEP;
    public Predicate<ItemStack> itemPredicate = null;
    public Predicate<Entity> entityPredicate = null;

    public DryingItemBuilderJS()
    {}

    public DryingItemBuilderJS items(String... items)
    {
        this.items.addAll(RegistryHelper.mapForgeRegistryTagList(ForgeRegistries.ITEMS, ConfigHelper.getItems(items)));
        return this;
    }

    public DryingItemBuilderJS result(ItemStack result)
    {
        this.result = result;
        return this;
    }

    public DryingItemBuilderJS sound(String soundId)
    {
        this.sound = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(soundId));
        return this;
    }

    public DryingItemBuilderJS itemPredicate(Predicate<ItemStack> itemPredicate)
    {
        this.itemPredicate = itemPredicate;
        return this;
    }

    public DryingItemBuilderJS entityPredicate(Predicate<Entity> entityPredicate)
    {
        this.entityPredicate = entityPredicate;
        return this;
    }

    public DryingItemData build()
    {
        DryingItemData data = new DryingItemData(new ItemRequirement(this.items, this.itemPredicate), this.result, new EntityRequirement(this.entityPredicate), this.sound);
        data.setType(ConfigData.Type.KUBEJS);
        return data;
    }
}
