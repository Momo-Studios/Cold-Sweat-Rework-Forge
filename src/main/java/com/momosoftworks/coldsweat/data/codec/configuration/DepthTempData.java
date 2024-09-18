package com.momosoftworks.coldsweat.data.codec.configuration;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.momosoftworks.coldsweat.api.util.Temperature;
import com.momosoftworks.coldsweat.config.ConfigSettings;
import com.momosoftworks.coldsweat.util.math.CSMath;
import com.momosoftworks.coldsweat.util.serialization.ConfigHelper;
import com.momosoftworks.coldsweat.util.world.WorldHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

public record DepthTempData(List<TempRegion> temperatures, List<Either<TagKey<DimensionType>, DimensionType>> dimensions,
                            Optional<List<String>> requiredMods)
{
    public static final Codec<DepthTempData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            TempRegion.CODEC.listOf().fieldOf("regions").forGetter(DepthTempData::temperatures),
            ConfigHelper.tagOrVanillaRegistryCodec(Registries.DIMENSION_TYPE, DimensionType.CODEC).listOf().fieldOf("dimensions").forGetter(DepthTempData::dimensions),
            Codec.STRING.listOf().optionalFieldOf("required_mods").forGetter(DepthTempData::requiredMods)
    ).apply(instance, DepthTempData::new));

    public boolean withinBounds(Level level, BlockPos pos)
    {
        Holder<DimensionType> dim = level.dimensionTypeRegistration();
        if (!CSMath.anyMatch(dimension -> dimension.map(dim::is, type -> type.equals(dim.value())), this.dimensions))
        {   return false;
        }
        for (TempRegion region : temperatures)
        {
            if (region.withinBounds(level, pos))
            {   return true;
            }
        }
        return false;
    }

    @Nullable
    public Double getTemperature(double temperature, BlockPos pos, Level level)
    {
        for (TempRegion region : temperatures)
        {
            if (region.withinBounds(level, pos))
            {   return region.getTemperature(temperature, pos, level);
            }
        }
        return null;
    }

    public record TempRegion(RampType rampType, VerticalBound top, VerticalBound bottom)
    {
        public static final Codec<TempRegion> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                RampType.CODEC.optionalFieldOf("type", RampType.LINEAR).forGetter(TempRegion::rampType),
                VerticalBound.CODEC.fieldOf("top").forGetter(TempRegion::top),
                VerticalBound.CODEC.fieldOf("bottom").forGetter(TempRegion::bottom)
        ).apply(instance, (type, top, bottom) ->
        {
            if (type == RampType.CONSTANT && !top.temperature.equals(bottom.temperature))
            {   throw new IllegalArgumentException("Constant temperature ramp type must have a single temperature value; got " + top.temperature + " and " + bottom.temperature);
            }
            return new TempRegion(type, top, bottom);
        }));

        public boolean withinBounds(Level level, BlockPos pos)
        {
            return pos.getY() <= top.getHeight(pos, level)
                && pos.getY() >= bottom.getHeight(pos, level);
        }

        public double getTemperature(double temperature, BlockPos pos, Level level)
        {
            double topTemp = Temperature.convert(top.getTemperature(temperature), top.units, Temperature.Units.MC, true);
            double bottomTemp = Temperature.convert(bottom.getTemperature(temperature), bottom.units, Temperature.Units.MC, true);
            return switch (rampType)
            {
                case CONSTANT ->
                {   yield pos.getY() <= bottom.getHeight(pos, level) ? bottomTemp : topTemp;
                }
                case LINEAR ->
                {
                    yield CSMath.blend(bottomTemp,
                                       topTemp,
                                       pos.getY(),
                                       bottom.getHeight(pos, level),
                                       top.getHeight(pos, level));
                }
                case EXPONENTIAL ->
                {
                    yield CSMath.blendExp(bottomTemp,
                                          topTemp,
                                          pos.getY(),
                                          bottom.getHeight(pos, level),
                                          top.getHeight(pos, level));
                }
                case LOGARITHMIC ->
                {
                    yield CSMath.blendLog(bottomTemp,
                                          topTemp,
                                          pos.getY(),
                                          bottom.getHeight(pos, level),
                                          top.getHeight(pos, level));
                }
            };
        }
    }

    public record VerticalBound(VerticalAnchor anchor, Integer depth, Temperature.Units units, TempContainer temperature)
    {
        public static final TempContainer DEFAULT_PASSTHROUGH = new TempContainer(0, ContainerType.PASSTHROUGH, 1);
        public static final TempContainer DEFAULT_MIDPOINT = new TempContainer(0, ContainerType.MIDPOINT, 1);

        public static final Codec<VerticalBound> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                VerticalAnchor.CODEC.optionalFieldOf("anchor", VerticalAnchor.CONSTANT).forGetter(VerticalBound::anchor),
                Codec.INT.fieldOf("depth").forGetter(VerticalBound::depth),
                Temperature.Units.CODEC.optionalFieldOf("units", Temperature.Units.MC).forGetter(VerticalBound::units),
                TempContainer.CODEC.fieldOf("temperature").forGetter(VerticalBound::temperature)
        ).apply(instance, VerticalBound::new));

        public record TempContainer(double temperature, ContainerType type, double strength)
        {
            public static final Codec<TempContainer> CODEC = Codec.either(Codec.DOUBLE, Codec.STRING).flatXmap(
            either ->
            {
                if (either.left().isPresent())
                {   return DataResult.success(new TempContainer(either.left().get(), ContainerType.NORMAL, 1));
                }
                else
                {
                    String[] value = either.right().get().split(",");
                    double strength = value.length == 2 ? Double.parseDouble(value[1]) : 1;
                    if (value[0].equals("passthrough"))
                    {   return DataResult.success(new TempContainer(0, ContainerType.PASSTHROUGH, strength));
                    }
                    else if (value[0].equals("midpoint"))
                    {   return DataResult.success(new TempContainer(0, ContainerType.MIDPOINT, strength));
                    }
                    else
                    {   return DataResult.error(() -> "Unknown temperature value: " + value);
                    }
                }
            },
            value ->
            {
                String strength = value.type == ContainerType.NORMAL ? "" : "," + value.strength;
                if (value.type == ContainerType.PASSTHROUGH)
                {   return DataResult.success(Either.right("passthrough" + strength));
                }
                else if (value.type == ContainerType.MIDPOINT)
                {   return DataResult.success(Either.right("midpoint" + strength));
                }
                else return DataResult.success(Either.left(value.temperature));
            });

            @Override
            public boolean equals(Object obj)
            {
                return obj instanceof TempContainer container
                    && container.temperature == temperature
                    && container.type == type
                    && container.strength == strength;
            }
        }

        public int getHeight(BlockPos checkPos, Level level)
        {
            return switch (anchor)
            {
                case CONSTANT -> depth;
                case WORLD_TOP -> level.getMaxBuildHeight();
                case WORLD_BOTTOM -> level.getMinBuildHeight();
                case GROUND_LEVEL -> WorldHelper.getHeight(checkPos, level) + depth;
            };
        }

        public double getTemperature(double temperature)
        {
            return switch (this.temperature.type)
            {
                case NORMAL -> this.temperature.temperature;
                case PASSTHROUGH -> temperature;
                case MIDPOINT -> (ConfigSettings.MIN_TEMP.get() + ConfigSettings.MAX_TEMP.get()) / 2;
            };
        }

        public enum ContainerType implements StringRepresentable
        {
            NORMAL("normal"),
            PASSTHROUGH("passthrough"),
            MIDPOINT("midpoint");

            private final String name;

            ContainerType(String name)
            {   this.name = name;
            }

            public static final Codec<ContainerType> CODEC = Codec.STRING.xmap(ContainerType::byName, ContainerType::getSerializedName);

            @Override
            public String getSerializedName()
            {   return name;
            }

            public static ContainerType byName(String name)
            {   for (ContainerType type : values())
                {   if (type.name.equals(name))
                    {   return type;
                    }
                }
                throw new IllegalArgumentException("Unknown special temperature value: " + name);
            }
        }
    }

    public enum RampType implements StringRepresentable
    {
        CONSTANT("constant"),
        LINEAR("linear"),
        EXPONENTIAL("exponential"),
        LOGARITHMIC("logarithmic");

        private final String name;

        RampType(String name)
        {   this.name = name;
        }

        public static final Codec<RampType> CODEC = Codec.STRING.xmap(RampType::byName, RampType::getSerializedName);

        @Override
        public String getSerializedName()
        {   return name;
        }

        public static RampType byName(String name)
        {   for (RampType type : values())
            {   if (type.name.equals(name))
                {   return type;
                }
            }
            throw new IllegalArgumentException("Unknown ramp type: " + name);
        }
    }

    public enum VerticalAnchor implements StringRepresentable
    {
        CONSTANT("constant"),
        WORLD_TOP("world_top"),
        WORLD_BOTTOM("world_bottom"),
        GROUND_LEVEL("ground_level");

        private final String name;

        VerticalAnchor(String name)
        {   this.name = name;
        }

        public static final Codec<VerticalAnchor> CODEC = Codec.STRING.xmap(VerticalAnchor::byName, VerticalAnchor::getSerializedName);

        @Override
        public String getSerializedName()
        {   return name;
        }

        public static VerticalAnchor byName(String name)
        {   for (VerticalAnchor type : values())
            {   if (type.name.equals(name))
                {   return type;
                }
            }
            throw new IllegalArgumentException("Unknown vertical anchor: " + name);
        }
    }
}
