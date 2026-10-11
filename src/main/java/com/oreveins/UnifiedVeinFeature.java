package com.oreveins;

import java.util.List;

import com.mojang.serialization.Codec;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.util.FeatureContext;

/**
 * 统一矿脉特征 —— 2.0 核心：全维度共用一条"随机矿脉"管线。
 *
 * 与 1.x（每种矿石独立 placed_feature + count）不同，本特征每次触发时：
 *
 *   1. 矿洞检测（CaveDetector）：扫描触发点附近空气密度，判定大/小矿洞；
 *   2. 决定矿脉数量：大矿洞 1-3 条（1条50% / 2条30% / 3条20%），
 *      小矿洞或无矿洞 1 条 —— 矿洞矿脉不是额外增量，而是同一触发点的
 *      分布方式变化（总频率仍为每 9 区块 1 条，与原版大型钻石矿脉一致）；
 *   3. 权重池选矿种（OreWeightPool）：按原版稀有度加权抽取，
 *      多条时"不放回"保证尽量不同类型；
 *   4. 生成：
 *      - 矿洞内 → 贴壁起点（洞底/洞壁基岩）+ 穿岩方向 → VeinFeature 游走，
 *        首格即替换壁面方块，矿石裸露在矿洞表面；
 *      - 无矿洞 → 触发点直接随机游走（1.x 普通矿脉逻辑）。
 *
 * 触发频率由 placed_feature JSON 的 rarity_filter chance=9 控制。
 */
public class UnifiedVeinFeature extends Feature<UnifiedVeinConfig> {

    public UnifiedVeinFeature(Codec<UnifiedVeinConfig> configCodec) {
        super(configCodec);
    }

    // 世界生成入口：Minecraft 放置本特征时调用
    @Override
    public boolean generate(FeatureContext<UnifiedVeinConfig> context) {
        StructureWorldAccess world = context.getWorld();
        BlockPos origin = context.getOrigin();
        Random random = context.getRandom();
        boolean nether = context.getConfig().isNether();

        // ---- 第 1 步：矿洞检测 ----
        CaveDetector.ScanResult scan = CaveDetector.scan(world, origin);

        // ---- 第 2 步：决定矿脉数量 ----
        // 大矿洞 1-3 条（偏向少条数），小矿洞/无矿洞固定 1 条
        int veinCount = 1;
        if (scan.bigCave()) {
            float roll = random.nextFloat();
            veinCount = roll < 0.50F ? 1 : (roll < 0.80F ? 2 : 3);
        }

        // ---- 第 3 步：权重池选矿种（高度带过滤 + 群系过滤 + 加权不放回） ----
        List<OreWeightPool.OreEntry> picked =
                OreWeightPool.pick(nether, origin.getY(), world.getBiome(origin), random, veinCount);
        if (picked.isEmpty()) {
            // 该触发点没有任何候选矿种（理论不会发生），本次触发作废
            return false;
        }

        // ---- 第 4 步：生成矿脉 ----
        boolean anyPlaced = false;
        if (scan.hasCave()) {
            // 矿洞模式：挑选贴壁起点（洞底/洞壁），矿脉从壁面伸入岩层
            List<BlockPos[]> wallStarts = CaveDetector.pickWallStarts(
                    world, random, scan.airCells(), picked.size());

            for (int i = 0; i < picked.size(); i++) {
                OreWeightPool.OreEntry entry = picked.get(i);
                BlockPos start = origin;
                double[] dir = null;

                if (i < wallStarts.size()) {
                    // 贴壁生成：起点 = 壁面基岩，方向 = 空气格指向基岩（穿入岩层）
                    start = wallStarts.get(i)[0];
                    BlockPos air = wallStarts.get(i)[1];
                    double[] intoRock = {
                            start.getX() - air.getX(),
                            start.getY() - air.getY(),
                            start.getZ() - air.getZ()
                    };
                    // 洞底/洞顶起点（纯垂直方向）改为随机水平方向：
                    // 让矿脉沿洞底表面延伸而非垂直扎入，暴露面更大
                    if (intoRock[0] == 0 && intoRock[2] == 0) {
                        double yaw = random.nextDouble() * Math.PI * 2;
                        dir = new double[]{Math.cos(yaw), 0, Math.sin(yaw)};
                    } else {
                        dir = intoRock;
                    }
                }
                // 贴壁起点不足时（空气簇边缘太小）退回普通生成

                anyPlaced |= ModFeatures.VEIN.generateVein(
                        world, random, entry.config(), start, dir);
            }
        } else {
            // 普通模式：实心岩层内随机游走（1.x 逻辑）
            for (OreWeightPool.OreEntry entry : picked) {
                anyPlaced |= ModFeatures.VEIN.generateVein(
                        world, random, entry.config(), origin, null);
            }
        }
        return anyPlaced;
    }
}
