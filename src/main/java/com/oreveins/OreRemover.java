package com.oreveins;

import java.util.List;

import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeModificationContext;
import net.fabricmc.fabric.api.biome.v1.ModificationPhase;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.feature.PlacedFeature;

/**
 * 矿石移除器 —— 本模组的第一步：移除原版所有矿石的生成逻辑。
 *
 * 实现原理：
 * 原版矿石以 PlacedFeature（放置特征）的形式挂载在各生物群系的
 * 生成步骤列表中。主世界矿石位于 UNDERGROUND_ORES 步骤，
 * 下界矿石位于 UNDERGROUND_DECORATION 步骤（两者不同！）。
 *
 * 执行时机：
 * Fabric API 的生物群系修改在世界创建时应用（数据包加载、
 * 动态注册表冻结阶段），早于包括出生点区块在内的任何区块生成，
 * 因此初始区块同样不会生成原版矿石。
 *
 * 注意：原版的"大型矿脉"（铜/铁蛇形矿脉）不是放置特征，
 * 由 resources/data/minecraft/worldgen/noise_settings/ 下的
 * 三个 JSON 覆盖文件（ore_veins_enabled=false）单独禁用。
 */
public final class OreRemover {

    /** 工具类，禁止实例化 */
    private OreRemover() {
    }

    /**
     * 主世界矿石特征 ID 列表（已逐一对照 1.20.1 原版数据核实）。
     * 注意：1.20.1 的命名与 1.21+ 不同（如 ore_debris_small、ore_quartz_nether）。
     */
    private static final List<String> OVERWORLD_ORE_FEATURES = List.of(
            // 煤矿（上/下两个高度层）
            "minecraft:ore_coal_upper",
            "minecraft:ore_coal_lower",
            // 铁矿（上/中/小三个高度层，ore_iron_small 常见于深板岩层）
            "minecraft:ore_iron_upper",
            "minecraft:ore_iron_middle",
            "minecraft:ore_iron_small",
            // 铜矿（ore_copper_large 常见于溶洞类生物群系）
            "minecraft:ore_copper",
            "minecraft:ore_copper_large",
            // 金矿（ore_gold_extra 为恶地生物群系的额外金矿）
            "minecraft:ore_gold",
            "minecraft:ore_gold_lower",
            "minecraft:ore_gold_extra",
            // 红石矿
            "minecraft:ore_redstone",
            "minecraft:ore_redstone_lower",
            // 钻石矿（ore_diamond_buried 为深层掩埋变体）
            "minecraft:ore_diamond",
            "minecraft:ore_diamond_large",
            "minecraft:ore_diamond_buried",
            // 青金石矿（ore_lapis_buried 为深层掩埋变体）
            "minecraft:ore_lapis",
            "minecraft:ore_lapis_buried",
            // 绿宝石矿（山地生物群系）
            "minecraft:ore_emerald",
            // 被感染石头/蠹虫石（山地生物群系）
            "minecraft:ore_infested"
    );

    /**
     * 下界矿石特征 ID 列表。
     * 注意：下界矿石挂在 UNDERGROUND_DECORATION 步骤而非 UNDERGROUND_ORES。
     */
    private static final List<String> NETHER_ORE_FEATURES = List.of(
            // 下界石英矿（ore_quartz_deltas 为玄武岩三角洲变体）
            "minecraft:ore_quartz_nether",
            "minecraft:ore_quartz_deltas",
            // 下界金矿（ore_gold_deltas 为玄武岩三角洲变体）
            "minecraft:ore_gold_nether",
            "minecraft:ore_gold_deltas",
            // 远古残骸（大型/小型）
            "minecraft:ore_ancient_debris_large",
            "minecraft:ore_debris_small"
    );

    /**
     * 注册矿石移除逻辑，需在模组初始化（onInitialize）时调用。
     */
    public static void register() {
        BiomeModifications.create(new Identifier(OreVeinMod.MOD_ID, "remove_vanilla_ores"))
                // 修改阶段选择 REMOVALS：先移除原版特征，后续再通过 ADDITIONS 添加自定义矿脉
                .add(ModificationPhase.REMOVALS,
                        selectionContext -> true,   // 选择器：对所有生物群系生效
                        context -> {                 // 修改器：移除主世界与下界的全部矿石特征
                            removeFeatures(context, OVERWORLD_ORE_FEATURES);
                            removeFeatures(context, NETHER_ORE_FEATURES);
                        });

        OreVeinMod.LOGGER.info("已注册原版矿石移除逻辑：主世界 {} 项 + 下界 {} 项（大型矿脉已由 noise_settings 覆盖禁用）",
                OVERWORLD_ORE_FEATURES.size(), NETHER_ORE_FEATURES.size());
    }

    /**
     * 从生物群系中移除指定 ID 列表的全部放置特征。
     *
     * 遍历所有生成步骤调用移除，而不是只移除 UNDERGROUND_ORES 步骤，
     * 因为下界矿石实际挂在 UNDERGROUND_DECORATION 步骤；
     * 对不存在于该步骤的特征执行移除是无副作用的空操作。
     */
    private static void removeFeatures(BiomeModificationContext context, List<String> featureIds) {
        for (String featureId : featureIds) {
            // 构建 PlacedFeature 的注册表键（形如 minecraft:ore_coal_upper）
            RegistryKey<PlacedFeature> featureKey =
                    RegistryKey.of(RegistryKeys.PLACED_FEATURE, new Identifier(featureId));

            // 逐个生成步骤尝试移除，确保不漏
            for (GenerationStep.Feature step : GenerationStep.Feature.values()) {
                context.getGenerationSettings().removeFeature(step, featureKey);
            }
        }
    }
}
