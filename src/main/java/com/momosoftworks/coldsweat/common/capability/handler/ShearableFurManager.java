package com.momosoftworks.coldsweat.common.capability.handler;

import com.momosoftworks.coldsweat.ColdSweat;
import com.momosoftworks.coldsweat.common.capability.SidedCapabilityCache;
import com.momosoftworks.coldsweat.common.capability.shearing.IShearableCap;
import com.momosoftworks.coldsweat.common.capability.ModCapabilities;
import com.momosoftworks.coldsweat.common.capability.shearing.ShearableFurCap;
import com.momosoftworks.coldsweat.core.event.TaskScheduler;
import com.momosoftworks.coldsweat.core.network.ColdSweatPacketHandler;
import com.momosoftworks.coldsweat.core.network.message.SyncShearableDataMessage;
import com.momosoftworks.coldsweat.config.ConfigSettings;
import com.momosoftworks.coldsweat.data.codec.configuration.EntityDropData;
import com.momosoftworks.coldsweat.data.loot.ModLootTables;
import com.momosoftworks.coldsweat.util.world.WorldHelper;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.Tags;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@Mod.EventBusSubscriber
public class ShearableFurManager
{
    public static SidedCapabilityCache<IShearableCap, Entity> CAP_CACHE = new SidedCapabilityCache<>(ModCapabilities.SHEARABLE_FUR);

    @SubscribeEvent
    public static void attachCapabilityToEntityHandler(AttachCapabilitiesEvent<Entity> event)
    {
        if (event.getObject() instanceof Goat)
        {
            // Make a new capability instance to attach to the entity
            IShearableCap cap = new ShearableFurCap();
            // Optional that holds the capability instance
            LazyOptional<IShearableCap> capOptional = LazyOptional.of(() -> cap);
            Capability<IShearableCap> capability = ModCapabilities.SHEARABLE_FUR;

            ICapabilityProvider provider = new ICapabilitySerializable<CompoundTag>()
            {
                @Nonnull
                @Override
                public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction direction)
                {
                    // If the requested cap is the temperature cap, return the temperature cap
                    if (cap == capability)
                    {   return capOptional.cast();
                    }
                    return LazyOptional.empty();
                }

                @Override
                public CompoundTag serializeNBT()
                {
                    return cap.serializeNBT();
                }

                @Override
                public void deserializeNBT(CompoundTag nbt)
                {
                    cap.deserializeNBT(nbt);
                }
            };

            event.addCapability(new ResourceLocation(ColdSweat.MOD_ID, "fur"), provider);
        }
    }

    public static LazyOptional<IShearableCap> getFurCap(Entity entity)
    {   return CAP_CACHE.get(entity);
    }

    @SubscribeEvent
    public static void onShearGoat(PlayerInteractEvent.EntityInteract event)
    {
        Entity entity = event.getTarget();
        Player player = event.getEntity();
        ItemStack stack = event.getItemStack();

        if (entity instanceof LivingEntity living && (!(living instanceof AgeableMob ageable) || !ageable.isBaby())
        && !entity.level().isClientSide && stack.is(Tags.Items.SHEARS))
        {
            getFurCap(living).ifPresent(cap ->
            {
                if (cap.isSheared())
                {   event.setResult(PlayerInteractEvent.Result.DENY);
                    return;
                }

                // Use shears
                player.swing(event.getHand(), true);
                stack.hurtAndBreak(1, event.getEntity(), (p) -> p.broadcastBreakEvent(event.getHand()));

                // Spawn item(s)
                shear(living, stack, player);

                // Random chance to ram the player when sheared
                if (living instanceof Goat goat && !player.getAbilities().instabuild && goat.level().getDifficulty() != Difficulty.PEACEFUL
                && !goat.level().isClientSide && goat.getRandom().nextDouble() < 0.4)
                {
                    // Set ram cooldown ticks
                    goat.getBrain().setMemory(MemoryModuleType.RAM_COOLDOWN_TICKS, 30);
                    // Stop active goals
                    for (WrappedGoal goal : goat.goalSelector.getAvailableGoals())
                    {
                        if (goal.isInterruptable())
                        {   goal.stop();
                        }
                    }

                    // Start lowering head
                    TaskScheduler.scheduleServer(() ->
                    {
                        ClientboundEntityEventPacket packet = new ClientboundEntityEventPacket(goat, (byte) 58);
                        ((ServerChunkCache) goat.level().getChunkSource()).broadcastAndSend(goat, packet);
                    }, 5);

                    // Look at player
                    BehaviorUtils.lookAtEntity(goat, player);
                    // Stop walking
                    goat.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

                    // Set ram target to player pos
                    goat.getBrain().setMemory(MemoryModuleType.RAM_TARGET, player.position());
                    TaskScheduler.scheduleServer(() ->
                    {
                        if (player.distanceTo(goat) <= 10)
                        {
                            goat.playSound(goat.isScreamingGoat() ? SoundEvents.GOAT_SCREAMING_PREPARE_RAM : SoundEvents.GOAT_PREPARE_RAM, 1.0F, 1.0F);
                            goat.getBrain().setMemory(MemoryModuleType.RAM_TARGET, player.position());
                        }
                    }, 30);

                    // Trigger ram
                    goat.getBrain().setActiveActivityIfPossible(Activity.RAM);
                }
                event.setResult(PlayerInteractEvent.Result.ALLOW);
            });
        }
    }

    // Regrow goat fur
    @SubscribeEvent
    public static void onGoatTick(LivingEvent.LivingTickEvent event)
    {
        LivingEntity entity = event.getEntity();
        getFurCap(entity).ifPresent(cap ->
        {
            EntityDropData furConfig = ConfigSettings.FUR_TIMINGS.get();
            // Tick fur growth cooldown
            if (cap.furGrowthCooldown() > 0)
            {   cap.setFurGrowthCooldown(Math.min(cap.furGrowthCooldown() - 1, furConfig.cooldown()));
            }
            cap.setAge(cap.age() + 1);

            // Entity is goat, current tick is a multiple of the regrow time, and random chance succeeds
            if (!entity.level().isClientSide
            && cap.isSheared()
            && cap.age() % Math.max(1, furConfig.interval()) == 0
            && cap.furGrowthCooldown() == 0
            && entity.getRandom().nextDouble() < furConfig.chance())
            {
                WorldHelper.playEntitySound(SoundEvents.WOOL_HIT, entity, entity.getSoundSource(), 0.5f, 0.6f);
                WorldHelper.playEntitySound(SoundEvents.LLAMA_SWAG, entity, entity.getSoundSource(), 0.5f, 0.8f);

                // Spawn particles
                WorldHelper.spawnParticleBatch(entity.level(), ParticleTypes.SPIT, entity.getX(), entity.getY() + entity.getBbHeight() / 2, entity.getZ(), 0.5f, 0.5f, 0.5f, 10, 0.05f);
                // Set not sheared
                cap.setSheared(false);
                syncData(entity, null);
            }
        });
    }

    @SubscribeEvent
    public static void onEntityLoaded(PlayerEvent.StartTracking event)
    {
        if (event.getEntity() instanceof ServerPlayer player && getFurCap(event.getTarget()).isPresent())
        {   syncData(((LivingEntity) event.getTarget()), player);
        }
    }

    public static void shear(LivingEntity entity, ItemStack shears, @Nullable Player player)
    {
        getFurCap(entity).ifPresent(cap ->
        {
            if (!cap.isSheared())
            {
                // Set sheared flag & cooldown
                cap.setSheared(true);
                cap.setFurGrowthCooldown(ConfigSettings.FUR_TIMINGS.get().cooldown());
                // Drop items
                for (ItemStack item : ModLootTables.getEntityDropsLootTable(entity, player, ModLootTables.GOAT_SHEARING))
                {   WorldHelper.entityDropItem(entity, item);
                }
                // Play sound
                entity.level().playSound(null, entity, SoundEvents.SHEEP_SHEAR, SoundSource.NEUTRAL, 1.0F, 1.0F);
                // Damage shears
                if (player == null || !player.getAbilities().instabuild)
                {
                    shears.hurtAndBreak(1, entity, (p) -> {
                        if (player != null) p.broadcastBreakEvent(player.getUsedItemHand());
                    });
                }
                // Sync shear data
                syncData(entity, null);
            }
        });
    }

    public static void syncData(LivingEntity entity, ServerPlayer player)
    {
        if (!entity.level().isClientSide)
        {   getFurCap(entity).ifPresent(cap ->
            {   ColdSweatPacketHandler.INSTANCE.send(player != null ? PacketDistributor.PLAYER.with(() -> player)
                                                                    : PacketDistributor.TRACKING_ENTITY.with(() -> entity),
                                                     new SyncShearableDataMessage(entity.getId(), cap.serializeNBT()));
            });
        }
    }
}
