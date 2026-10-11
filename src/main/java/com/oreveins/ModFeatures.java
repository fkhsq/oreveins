package com.oreveins;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * 模组特征注册 —— 将自定义特征注册到游戏的特征注册表。
 * 注册后即可在数据包 JSON 中通过 "type": "oreveins:xxx" 引用。
 */
public final class ModFeatures {

    /** 工具类，禁止实例化 */
    private ModFeatures() {
    }

    // 矿脉特征实例（全局唯一）：单矿种随机游走算法（2.0 起由统一特征内部调用）
    public static final VeinFeature VEIN = new VeinFeature(VeinConfig.CODEC);

    // 注册 ID：数据包 JSON 中的 "type": "oreveins:vein"
    public static final Identifier VEIN_ID = new Identifier(OreVeinMod.MOD_ID, "vein");

    // 统一矿脉特征实例（全局唯一）：2.0 核心 —— 权重池选矿种 + 矿洞感知
    public static final UnifiedVeinFeature UNIFIED_VEIN =
            new UnifiedVeinFeature(UnifiedVeinConfig.CODEC);

    // 注册 ID：数据包 JSON 中的 "type": "oreveins:unified_vein"
    public static final Identifier UNIFIED_VEIN_ID = new Identifier(OreVeinMod.MOD_ID, "unified_vein");

    /**
     * 注册特征，需在模组初始化（onInitialize）时调用。
     */
    public static void register() {
        Registry.register(Registries.FEATURE, VEIN_ID, VEIN);
        Registry.register(Registries.FEATURE, UNIFIED_VEIN_ID, UNIFIED_VEIN);
        OreVeinMod.LOGGER.info("已注册自定义特征: {}（单矿种矿脉）, {}（统一矿脉）", VEIN_ID, UNIFIED_VEIN_ID);
    }
}
