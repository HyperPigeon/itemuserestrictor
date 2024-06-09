package dev.falseresync.itemuserestrictor;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockBox;

public record Restriction(
        BlockBox box,
        Item item
) {
    public static final Codec<Restriction> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockBox.CODEC.fieldOf("box").forGetter(Restriction::box),
            Registries.ITEM.getCodec().fieldOf("item").forGetter(Restriction::item)
    ).apply(instance, Restriction::new));
}
