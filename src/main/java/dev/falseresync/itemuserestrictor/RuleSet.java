package dev.falseresync.itemuserestrictor;

import com.google.common.base.Predicates;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import org.apache.commons.lang3.RandomStringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

public record RuleSet(
        Map<String, Restriction> rules,
        Int2ObjectMap<Predicate<BlockPos>> predicates
) {
    public static final RuleSet EMPTY = new RuleSet(new HashMap<>());

    public static final Codec<RuleSet> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, Restriction.CODEC).fieldOf("rules").orElse(new HashMap<>()).forGetter(RuleSet::rules)
    ).apply(instance, RuleSet::new));

    private RuleSet(Map<String, Restriction> rules) {
        this(new HashMap<>(rules), bakePredicates(rules.values()));
    }

    private static Int2ObjectMap<Predicate<BlockPos>> bakePredicates(Collection<Restriction> restrictions) {
        var predicates = new Int2ObjectRBTreeMap<Predicate<BlockPos>>(Integer::compareTo);
        for (var restriction : restrictions) updateOrAddPredicate(predicates, restriction);
        return predicates;
    }

    private static void updateOrAddPredicate(Int2ObjectMap<Predicate<BlockPos>> predicates, Restriction restriction) {
        predicates.compute(
                Item.getRawId(restriction.item()),
                (key, oldPredicate) ->
                        (oldPredicate == null ? Predicates.<BlockPos>alwaysFalse() : oldPredicate)
                                .or(pos -> restriction.box().contains(pos)));
    }

    private static Predicate<BlockPos> composePredicate(BlockBox box) {
        return box::contains;
    }

    private void rebakePredicatesFor(Item item) {
        predicates.remove(Item.getRawId(item));
        predicates.put(Item.getRawId(item), rules.values().stream()
                .flatMap(restriction -> restriction.item().equals(item)
                        ? Stream.of(composePredicate(restriction.box()))
                        : Stream.empty())
                .reduce(Predicates.alwaysFalse(), Predicate::or));
    }

    public boolean isAllowed(Item item, BlockPos pos) {
        return predicates.getOrDefault(Item.getRawId(item), Predicates.alwaysTrue()).test(pos);
    }

    public void remove(String id) {
        var removed = rules.remove(id);
        if (removed == null) return;
        rebakePredicatesFor(removed.item());
    }

    public String add(BlockPos a, BlockPos b, Item item) {
        var id = RandomStringUtils.randomAlphanumeric(8);
        var restriction = new Restriction(BlockBox.create(a, b), item);
        rules.put(id, restriction);
        updateOrAddPredicate(predicates, restriction);
        ItemUseRestrictor.markDirty();
        return id;
    }
}
