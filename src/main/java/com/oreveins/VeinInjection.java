package com.oreveins;

import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.feature.PlacedFeature;

/**
 * 矿脉注入器（2.0）—— 将"统一矿脉"放置特征注入到各生物群系。
 *
 * 与 1.x 的区别：不再为每种矿石单独注入 placed_feature（17 个），
 * 全维度只注入 2 个统一特征：
 *   - 主世界：oreveins:unified_vein_overworld（UNDERGROUND_ORES 步骤）
 *   - 下　界：oreveins:unified_vein_nether（UNDERGROUND_DECORATION 步骤，与原版下界矿石一致）
 *
 * 触发频率（rarity_filter chance=9）与矿种选择（OreWeightPool 权重池）
 * 全部在 placed_feature JSON + 特征代码中定义，本类只负责挂载到群系。
 */
public final class VeinInjection {

    /** 工具类，禁止实例化 */
    private VeinInjection() {
    }

    /**
     * 注册统一矿脉注入，需在模组初始化时调用。
     */
    public static void register() {
        // ---------- 主世界（所有主世界群系）----------
        BiomeModifications.addFeature(
                BiomeSelectors.foundInOverworld(),
                GenerationStep.Feature.UNDERGROUND_ORES,
                placedKey("unified_vein_overworld"));

        // ---------- 下界（所有下界群系，挂 UNDERGROUND_DECORATION 与原版一致）----------
        BiomeModifications.addFeature(
                BiomeSelectors.foundInTheNether(),
                GenerationStep.Feature.UNDERGROUND_DECORATION,
                placedKey("unified_vein_nether"));

        OreVeinMod.LOGGER.info("已注入统一矿脉：主世界 1 + 下界 1（每 9 区块期望 1 条随机矿脉）");
    }

    /**
     * 构建 PlacedFeature 的注册表键（oreveins 命名空间）。
     */
    private static RegistryKey<PlacedFeature> placedKey(String name) {
        return RegistryKey.of(RegistryKeys.PLACED_FEATURE,
                new Identifier(OreVeinMod.MOD_ID, name));
    }
}
