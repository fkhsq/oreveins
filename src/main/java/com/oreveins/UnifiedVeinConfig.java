package com.oreveins;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.gen.feature.FeatureConfig;

/**
 * 统一矿脉配置 —— 2.0 核心机制：整个世界共用一条"随机矿脉"管线。
 *
 * 与 1.x 的区别：不再为每种矿石单独定义 placed_feature（各自 count），
 * 而是由一个统一特征接管全部矿石生成：
 *   - 触发频率：原版 ore_diamond_large 的 rarity chance=9（平均每 9 区块 1 条）
 *   - 矿种选择：触发时从权重池加权抽取（稀有矿权重低，常见矿权重高）
 *   - 矿洞感知：触发点附近有大矿洞时一次生成 1-3 条贴壁矿脉
 *
 * 配置只携带一个字段：维度标识（overworld / nether），
 * 用于区分主世界与下界的权重池（矿种完全不同）。
 */
public record UnifiedVeinConfig(
        String dimension   // 维度标识："overworld" 或 "nether"
) implements FeatureConfig {

    // 序列化编解码器：JSON 中的 "dimension" 字段
    public static final Codec<UnifiedVeinConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("dimension").forGetter(UnifiedVeinConfig::dimension)
    ).apply(instance, UnifiedVeinConfig::new));

    /** 是否为下界权重池 */
    public boolean isNether() {
        return "nether".equals(dimension);
    }
}
