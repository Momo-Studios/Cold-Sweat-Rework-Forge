package com.momosoftworks.coldsweat.common.blockentity;

import com.momosoftworks.coldsweat.ColdSweat;
import com.momosoftworks.coldsweat.api.temperature.modifier.BlockInsulationTempModifier;
import com.momosoftworks.coldsweat.api.temperature.modifier.TempModifier;
import com.momosoftworks.coldsweat.api.util.Temperature;
import com.momosoftworks.coldsweat.common.block.BoilerBlock;
import com.momosoftworks.coldsweat.common.container.BoilerContainer;
import com.momosoftworks.coldsweat.common.capability.handler.EntityTempManager;
import com.momosoftworks.coldsweat.common.item.FilledWaterskinItem;
import com.momosoftworks.coldsweat.config.ConfigSettings;
import com.momosoftworks.coldsweat.core.event.TaskScheduler;
import com.momosoftworks.coldsweat.core.network.ColdSweatPacketHandler;
import com.momosoftworks.coldsweat.core.network.message.BlockDataUpdateMessage;
import com.momosoftworks.coldsweat.data.tag.ModItemTags;
import com.momosoftworks.coldsweat.util.math.CSMath;
import com.momosoftworks.coldsweat.util.registries.ModEffects;
import com.momosoftworks.coldsweat.util.registries.ModItems;
import com.momosoftworks.coldsweat.util.serialization.NBTHelper;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.inventory.container.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SUpdateTileEntityPacket;
import net.minecraft.potion.EffectInstance;
import net.minecraft.tileentity.ITickableTileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.network.PacketDistributor;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.SidedInvWrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Optional;

public class BoilerBlockEntity extends HearthBlockEntity implements ITickableTileEntity, ISidedInventory
{
    public static int[] WATERSKIN_SLOTS = {1, 2, 3, 4, 5, 6, 7, 8, 9};
    public static int[] FUEL_SLOT = {0};

    LazyOptional<? extends IItemHandler>[] slotHandlers =
            SidedInvWrapper.create(this, Direction.UP, Direction.DOWN, Direction.NORTH);

    List<ServerPlayerEntity> usingPlayers = new ArrayList<>();

    public BoilerBlockEntity()
    {   super();
        TaskScheduler.schedule(this::checkForSmokestack, 5);
    }

    @Override
    public void onDataPacket(NetworkManager net, SUpdateTileEntityPacket pkt)
    {   handleUpdateTag(null, pkt.getTag());
    }

    @Override
    public SUpdateTileEntityPacket getUpdatePacket()
    {   return new SUpdateTileEntityPacket(this.getBlockPos(), 0, this.getUpdateTag());
    }

    private void sendUpdatePacket()
    {
        // Remove the players that aren't interacting with this block anymore
        usingPlayers.removeIf(player -> !(player.containerMenu instanceof BoilerContainer && ((BoilerContainer) player.containerMenu).te == this));

        // Send data to all players with this block's menu open
        ColdSweatPacketHandler.INSTANCE.send(PacketDistributor.NMLIST.with(()-> usingPlayers.stream().map(player -> player.connection.connection).collect(Collectors.toList())),
                                             new BlockDataUpdateMessage(this));
    }

    @Override
    protected ITextComponent getDefaultName() {
        return new TranslationTextComponent("container." + ColdSweat.MOD_ID + ".boiler");
    }

    @Override
    public ITextComponent getDisplayName() {
        return this.getCustomName() != null ? this.getCustomName() : this.getDefaultName();
    }

    @Override
    public void tick()
    {
        super.tick();

        BlockPos pos = this.getBlockPos();
        BlockState state = this.getBlockState();

        if (getFuel() > 0)
        {
            // Set state to lit
            if (!state.getValue(BoilerBlock.LIT))
            {   level.setBlock(pos, state.setValue(BoilerBlock.LIT, true), 3);
            }

            // Warm up waterskins
            if (ticksExisted % (20 / ConfigSettings.TEMP_RATE.get()) == 0)
            {
                boolean hasItemStacks = false;

                for (int i = 1; i < 10; i++)
                {
                    ItemStack stack = getItem(i);
                    CompoundNBT tag = NBTHelper.getTagOrEmpty(stack);
                    double itemTemp = tag.getDouble(FilledWaterskinItem.NBT_TEMPERATURE);

                    if (ModItemTags.BOILER_VALID.contains(stack.getItem()))
                    {
                        // If item is a filled waterskin not at max temp yet
                        if (itemTemp < 50 && stack.getItem() == ModItems.FILLED_WATERSKIN)
                        {   hasItemStacks = true;
                            tag.putDouble(FilledWaterskinItem.NBT_TEMPERATURE, Math.min(50, itemTemp + 1));
                        }
                    }
                }
                if (hasItemStacks) setFuel(getFuel() - 1);
            }
        }
        // if no fuel, set state to unlit
        else if (state.getValue(BoilerBlock.LIT))
        {   level.setBlock(pos, state.setValue(BoilerBlock.LIT, false), 3);
        }
    }

    @Override
    public int getMaxPaths()
    {   return 1500;
    }

    @Override
    public int getSpreadRange()
    {   return 16;
    }

    @Override
    public int getMaxInsulationLevel()
    {   return 5;
    }

    @Override
    public boolean hasSmokeStack()
    {   return this.hasSmokestack;
    }

    @Override
    protected void trySpreading(int pathCount, int firstIndex, int lastIndex)
    {
        if (this.hasSmokestack)
        {   super.trySpreading(pathCount, firstIndex, lastIndex);
        }
    }

    @Override
    void insulatePlayer(PlayerEntity player)
    {
        // Apply the insulation effect
        if (!shouldUseHotFuel)
        EntityTempManager.getTemperatureCap(player).ifPresent(cap ->
        {   double temp = cap.getTrait(Temperature.Trait.WORLD);
            double min = cap.getTrait(Temperature.Trait.FREEZING_POINT);
            double max = cap.getTrait(Temperature.Trait.BURNING_POINT);

            // If the player is habitable, check the input temperature reported by their HearthTempModifier (if they have one)
            if (CSMath.betweenInclusive(temp, min, max))
            {
                // Find the player's HearthTempModifier
                Optional<? extends TempModifier> modifier = Temperature.getModifier(player, Temperature.Trait.WORLD, BlockInsulationTempModifier.class);
                // If they have one, refresh it
                if (modifier.isPresent())
                {
                    if (modifier.get().getExpireTime() - modifier.get().getTicksExisted() > 20)
                    {   return;
                    }
                    temp = modifier.get().getLastInput();
                }
                // This means the player is not insulated, and they are habitable without it
                else return;
            }

            // Tell the hearth to use hot fuel
            shouldUseHotFuel |= this.getHotFuel() > 0 && temp < min;
        });
        if (shouldUseHotFuel)
        {   int maxEffect = this.getMaxInsulationLevel() - 1;
            int effectLevel = (int) Math.min(maxEffect, (insulationLevel / (double) this.getInsulationTime()) * maxEffect);
            player.addEffect(new EffectInstance(ModEffects.INSULATION, 120, effectLevel, false, false, true));
            player.displayClientMessage(new StringTextComponent(insulationLevel+""), true);
        }
    }

    @Override
    public int getItemFuel(ItemStack item)
    {   return CSMath.getIfNotNull(ConfigSettings.BOILER_FUEL.get().get(item.getItem()),
                                   fuel -> fuel.test(item) ? fuel.value : 0,
                                   0).intValue();
    }

    @Override
    protected void storeFuel(ItemStack stack, int amount)
    {
        if (this.getFuel() < this.getMaxFuel() - Math.abs(amount) * 0.75)
        {
            if (!stack.hasContainerItem() || stack.getCount() > 1)
            {   int consumeCount = Math.min((int) Math.floor((this.getMaxFuel() - this.getFuel()) / (double) Math.abs(amount)), stack.getCount());
                stack.shrink(consumeCount);
                addFuel(amount * consumeCount);
            }
            else
            {   this.setItem(0, stack.getContainerItem());
                addFuel(amount);
            }
        }
    }

    public int getFuel()
    {   return this.getHotFuel();
    }

    public void setFuel(int amount)
    {   this.setHotFuel(amount, true);
    }

    @Override
    public void setHotFuel(int amount, boolean update)
    {   super.setHotFuel(amount, update);
        this.sendUpdatePacket();
    }

    @Override
    public void addFuel(int amount)
    {   this.setHotFuelAndUpdate(this.getHotFuel() + amount);
    }

    @Override
    protected boolean isFuelChanged()
    {   return this.ticksExisted % 10 == 0;
    }

    @Override
    protected Container createMenu(int id, PlayerInventory playerInv)
    {
        // Track the players using this block
        if (playerInv.player instanceof ServerPlayerEntity)
        {   usingPlayers.add((ServerPlayerEntity) playerInv.player);
        }
        return new BoilerContainer(id, playerInv, this);
    }

    @Override
    protected void tickParticles()
    {
        if (this.hasSmokestack)
        {   super.tickParticles();
        }
    }

    @Override
    public int getContainerSize()
    {   return 10;
    }

    @Override
    public int[] getSlotsForFace(Direction dir)
    {   return dir.getAxis() == Direction.Axis.Y ? WATERSKIN_SLOTS : FUEL_SLOT;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction direction)
    {
        if (slot == 0)
            return this.getItemFuel(stack) != 0;
        else return stack.getItem() == ModItems.WATERSKIN || stack.getItem() == ModItems.FILLED_WATERSKIN;
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction)
    {   return true;
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction facing) {
        if (!this.remove && facing != null && capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            if (facing == Direction.UP)
                return slotHandlers[0].cast();
            else if (facing == Direction.DOWN)
                return slotHandlers[1].cast();
            else
                return slotHandlers[2].cast();
        }
        return super.getCapability(capability, facing);
    }
}
