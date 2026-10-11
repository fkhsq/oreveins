package com.oreveins;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.gen.feature.FeatureConfig;
import net.minecraft.world.gen.feature.OreFeatureConfig;

/**
 * 矿脉配置 —— 定义一条矿脉的生成参数。
 *
 * 通过 Codec 与数据包 JSON 互相转换，示例：
 * {
 *   "targets":  [ 可替换方块规则 -> 矿石方块（与原版矿石 JSON 格式一致） ],
 *   "size": 每条矿脉的目标方块总数（含分支）,
 *   "branch_count": 分支数量,
 *   "branch_length": 每条分支的最大步数
 * }
 */
public record VeinConfig(
        List<OreFeatureConfig.Target> targets,  // 可替换方块规则与对应矿石（复用原版 OreFeatureConfig.Target）
        int size,                               // 每条矿脉的目标方块总数（主脉 + 分支 + 加厚）
        int branchCount,                        // 分支数量
        int branchLength                        // 每条分支的最大步数
) implements FeatureConfig {

    // 序列化编解码器：负责在 JSON 与本对象之间转换
    public static final Codec<VeinConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            // 可替换目标列表（字段名与原版矿石 JSON 相同，方便对照原版数据包编写）
            OreFeatureConfig.Target.CODEC.listOf().fieldOf("targets").forGetter(VeinConfig::targets),
            // 每条矿脉的方块总数，允许范围 1~200
            Codec.intRange(1, 200).fieldOf("size").forGetter(VeinConfig::size),
            // 分支数量，允许范围 0~8
            Codec.intRange(0, 8).fieldOf("branch_count").forGetter(VeinConfig::branchCount),
            // 每条分支最大长度，允许范围 1~50
            Codec.intRange(1, 50).fieldOf("branch_length").forGetter(VeinConfig::branchLength)
    ).apply(instance, VeinConfig::new));
}
