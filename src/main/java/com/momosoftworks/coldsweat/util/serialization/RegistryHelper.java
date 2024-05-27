package com.momosoftworks.coldsweat.util.serialization;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.momosoftworks.coldsweat.util.world.WorldHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.play.ClientPlayNetHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.ITag;
import net.minecraft.tags.TagRegistry;
import net.minecraft.tags.TagRegistryManager;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.registry.DefaultedRegistry;
import net.minecraft.util.registry.DynamicRegistries;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.DimensionType;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.feature.StructureFeature;
import net.minecraft.world.gen.feature.structure.Structure;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.IForgeRegistryEntry;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

public class RegistryHelper
{
    public static <T> Registry<T> getRegistry(RegistryKey<Registry<T>> registry)
    {
        return getRegistryAccess().registryOrThrow(registry);
    }

    @Nullable
    public static DynamicRegistries getRegistryAccess()
    {
        DynamicRegistries access = null;

        MinecraftServer server = WorldHelper.getServer();

        if (server != null)
        {
            World level = server.getLevel(World.OVERWORLD);
            if (level != null)
            {   access = level.registryAccess();
            }
            else access = server.registryAccess();
        }

        if (access == null && FMLEnvironment.dist == Dist.CLIENT)
        {
            if (Minecraft.getInstance().level != null)
            {   access = Minecraft.getInstance().level.registryAccess();
            }
            else
            {
                ClientPlayNetHandler connection = Minecraft.getInstance().getConnection();
                if (connection != null)
                {   access = connection.registryAccess();
                }
            }
        }
        return access;
    }

    public static <T> List<T> mapTaggableList(List<Either<ITag<T>, T>> eitherList)
    {
        List<T> list = new ArrayList<>();
        for (Either<ITag<T>, T> either : eitherList)
        {
            either.ifLeft(tagKey ->
            {   list.addAll(tagKey.getValues());
            });
            either.ifRight(list::add);
        }
        return list;
    }

    public static <T extends IForgeRegistryEntry<T>> Codec<Either<ITag<T>, T>> createForgeTagCodec(IForgeRegistry<T> forgeRegistry, DefaultedRegistry<T> registry)
    {
        TagRegistry<T> tagRegistry = (TagRegistry<T>) TagRegistryManager.get(registry.key().location());
        return Codec.STRING.xmap(
               id ->
               {
                   if (id.startsWith("#"))
                   {   return Either.left(tagRegistry.getAllTags().getTag(new ResourceLocation(id.substring(1))));
                   }
                   else
                   {   return Either.right(forgeRegistry.getValue(new ResourceLocation(id)));
                   }
               },
               either ->
               {
                   return either.left().isPresent()
                          ? "#" + tagRegistry.getAllTags().getId(either.left().get()).toString()
                          : forgeRegistry.getKey(either.right().get()).toString();
               });
    }

    public static <T> Codec<Either<ITag<T>, T>> createVanillaTagCodec(DefaultedRegistry<T> registry)
    {
        TagRegistry<T> tagRegistry = (TagRegistry<T>) TagRegistryManager.get(registry.key().location());
        return Codec.STRING.xmap(
                id ->
                {
                    if (id.startsWith("#"))
                    {   return Either.left(tagRegistry.getAllTags().getTag(new ResourceLocation(id.substring(1))));
                    }
                    else
                    {   return Either.right(registry.get(new ResourceLocation(id)));
                    }
                },
                either ->
                {
                    return either.left().isPresent()
                           ? "#" + tagRegistry.getAllTags().getId(either.left().get()).toString()
                           : registry.getKey(either.right().get()).toString();
                });
    }

    public static <T> Optional<T> getVanillaRegistryValue(RegistryKey<Registry<T>> registry, ResourceLocation id)
    {
        try
        {   return Optional.ofNullable(getRegistry(registry).get(id));
        }
        catch (Exception e)
        {   return Optional.empty();
        }
    }

    public static <T> Optional<ResourceLocation> getVanillaRegistryKey(RegistryKey<Registry<T>> registry, T value)
    {
        try
        {   return Optional.ofNullable(getRegistry(registry).getKey(value));
        }
        catch (Exception e)
        {   return Optional.empty();
        }
    }

    @Nullable
    public static Biome getBiome(ResourceLocation biomeId)
    {   return getVanillaRegistryValue(Registry.BIOME_REGISTRY, biomeId).orElse(null);
    }

    @Nullable
    public static ResourceLocation getBiomeId(Biome biome)
    {   return getVanillaRegistryKey(Registry.BIOME_REGISTRY, biome).orElse(null);
    }

    @Nullable
    public static DimensionType getDimension(ResourceLocation dimensionId)
    {   return getVanillaRegistryValue(Registry.DIMENSION_TYPE_REGISTRY, dimensionId).orElse(null);
    }

    @Nullable
    public static ResourceLocation getDimensionId(DimensionType dimension)
    {   return getVanillaRegistryKey(Registry.DIMENSION_TYPE_REGISTRY, dimension).orElse(null);
    }

    @Nullable
    public static Structure<?> getStructure(ResourceLocation structureId)
    {
        StructureFeature<?, ?> feature = getRegistry(Registry.CONFIGURED_STRUCTURE_FEATURE_REGISTRY).get(structureId);
        return feature != null ? feature.feature : null;
    }

    @Nullable
    public static ResourceLocation getStructureId(Structure<?> structure)
    {
        return getRegistry(Registry.CONFIGURED_STRUCTURE_FEATURE_REGISTRY).entrySet().stream()
                                                                   .map(entry -> new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue().feature))
                                                                   .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey)).get(structure).location();

    }
}
