package com.momosoftworks.coldsweat.api.temperature.modifier;

import com.mojang.datafixers.util.Pair;
import com.momosoftworks.coldsweat.api.util.Temperature;
import com.momosoftworks.coldsweat.config.ConfigSettings;
import com.momosoftworks.coldsweat.compat.CompatManager;
import com.momosoftworks.coldsweat.data.codec.configuration.DimensionTempData;
import com.momosoftworks.coldsweat.data.codec.configuration.StructureTempData;
import com.momosoftworks.coldsweat.util.math.CSMath;
import com.momosoftworks.coldsweat.util.world.WorldHelper;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.feature.structure.Structure;

import java.util.function.Function;

public class BiomeTempModifier extends TempModifier
{
    public BiomeTempModifier()
    {
        this(16);
    }

    public BiomeTempModifier(int samples)
    {   this.getNBT().putInt("Samples", samples);
    }

    @Override
    public Function<Double, Double> calculate(LivingEntity entity, Temperature.Trait trait)
    {
        try
        {
            World level = entity.level;
            double worldTemp = 0;
            BlockPos entPos = entity.blockPosition();

            // In the case that the dimension temperature is overridden by config, use that and skip everything else
            DimensionTempData dimTempOverride = ConfigSettings.DIMENSION_TEMPS.get(entity.level.registryAccess()).get(level.dimensionType());
            if (dimTempOverride != null)
            {   return temp -> temp + dimTempOverride.temperature;
            }

            // If there's a temperature structure here, ignore biome temp
            Pair<Double, Double> structureTemp = getStructureTemp(entity.level, entity.blockPosition());
            if (structureTemp.getFirst() != null)
            {   return temp -> structureTemp.getFirst();
            }

            int biomeCount = 0;
            for (BlockPos blockPos : WorldHelper.getPositionGrid(entPos, 64, 10))
            {
                // Check if this position is valid
                if (!World.isInWorldBounds(blockPos) || blockPos.distSqr(entPos) > 30*30) continue;
                // Get the holder for the biome
                Biome biome = level.getBiomeManager().getBiome(blockPos);

                // Tally number of biomes
                biomeCount++;

                // Get min/max temperature of the biome
                Pair<Double, Double> configTemp = WorldHelper.getBiomeTemperatureRange(level, biome);

                // Biome temp at midnight (bottom of the sine wave)
                double min = configTemp.getFirst();
                // Biome temp at noon (top of the sine wave)
                double max = configTemp.getSecond();

                DimensionType dimension = level.dimensionType();
                if (!dimension.hasCeiling())
                {
                    // Biome temp with time of day
                    double biomeTemp = WorldHelper.getBiomeTemperatureAt(level, biome, entity.blockPosition());
                    if (CompatManager.isPrimalWinterLoaded() && level.dimension().location().equals(DimensionType.OVERWORLD_LOCATION.location()))
                    {   biomeTemp = Math.min(biomeTemp, biomeTemp / 2) - Math.max(biomeTemp / 2, 0);
                    }
                    worldTemp += biomeTemp;
                }
                // If dimension has ceiling (don't use time or altitude)
                else worldTemp += CSMath.average(max, min);
            }

            worldTemp /= Math.max(1, biomeCount);

            // Add dimension offset, if present
            DimensionTempData dimTempOffsetConf = ConfigSettings.DIMENSION_OFFSETS.get(entity.level.registryAccess()).get(level.dimensionType());
            if (dimTempOffsetConf != null)
            {   worldTemp += dimTempOffsetConf.temperature;
            }

            // Add structure offset, if present
            worldTemp += structureTemp.getSecond();

            double finalWorldTemp = worldTemp;
            return temp -> temp + finalWorldTemp;
        }
        catch (Exception e)
        {   return temp -> temp;
        }
    }

    public static Pair<Double, Double> getStructureTemp(World level, BlockPos pos)
    {
        Structure<?> structure = WorldHelper.getStructureAt(level, pos);
        if (structure == null) return Pair.of(null, 0d);

        Double strucTemp = CSMath.getIfNotNull(ConfigSettings.STRUCTURE_TEMPS.get(level.registryAccess()).get(structure), data -> data.temperature, null);
        Double strucOffset = CSMath.getIfNotNull(ConfigSettings.STRUCTURE_OFFSETS.get(level.registryAccess()).get(structure), data -> data.temperature, 0d);

        return Pair.of(strucTemp, strucOffset);
    }
}