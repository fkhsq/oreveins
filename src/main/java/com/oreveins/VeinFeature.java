package com.oreveins;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.OreFeatureConfig;
import net.minecraft.world.gen.feature.util.FeatureContext;

/**
 * 矿脉特征 —— 用"随机游走 + 分支 + 矿洞感知"算法生成连续长条形矿脉。
 *
 * 与原版 OreFeature（向四周散射的团块状）不同，本特征沿一条带
 * 随机漂移的主方向连续行走放置矿石，途中从主脉随机节点派生
 * 侧向分支，形成类似真实矿脉的枝杈状长条形态。
 *
 * 矿洞感知（关键改进）：
 *   当矿脉穿越矿洞/空气区域时，不会被"砍成两半"——
 *   1. 矿脉会主动贴附矿洞壁面裸露矿石（视觉上矿洞壁有矿，符合真实洞穴）；
 *   2. 矿脉方向会向最近的岩壁偏转，绕过空洞而非径直穿过；
 *   3. 穿越空气时不消耗矿石预算（只在可替换方块上放置），
 *      保证矿洞两侧的矿石段仍有足够密度。
 */
public class VeinFeature extends Feature<VeinConfig> {

    // 6 个正交方向（用于矿洞壁面扫描和方向引力）
    private static final int[][] SIX_DIRS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    public VeinFeature(Codec<VeinConfig> configCodec) {
        super(configCodec);
    }

    // 世界生成入口：Minecraft 放置本特征时调用（随机方向游走）
    @Override
    public boolean generate(FeatureContext<VeinConfig> context) {
        return generateVein(context.getWorld(), context.getRandom(),
                context.getConfig(), context.getOrigin(), null);
    }

    /**
     * 核心生成逻辑 —— 供统一矿脉特征（UnifiedVeinFeature）直接调用。
     *
     * @param initialDir 初始方向向量（三分量，不必归一化）；
     *                   null = 随机水平方位角 + 轻微倾角（普通矿脉）；
     *                   非null = 矿洞贴壁矿脉：方向由空气格指向岩壁（穿入岩层）
     * @return 是否至少放置了一个矿石
     */
    public boolean generateVein(StructureWorldAccess world, Random random, VeinConfig config,
                                BlockPos origin, double[] initialDir) {
        // 主方向：外部指定（矿洞贴壁穿岩方向）或随机生成
        double dx, dy, dz;
        double yaw = 0; // 主方向方位角（分支生成时复用，向外偏转 60~120 度）
        if (initialDir != null) {
            dx = initialDir[0];
            dy = initialDir[1];
            dz = initialDir[2];
            // 归一化外部方向（可能只是 ±1 单位向量，统一长度便于后续漂移计算）
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len > 1e-6) {
                dx /= len;
                dy /= len;
                dz /= len;
            }
            // 贴壁方向也推导出等效方位角，供分支计算使用
            yaw = Math.atan2(dz, dx);
        } else {
            // 水平面上随机方位角 + 轻微倾角（矿脉大致水平延伸、略带倾斜）
            yaw = random.nextDouble() * Math.PI * 2;
            double pitch = (random.nextDouble() - 0.5) * 0.6;
            dx = Math.cos(yaw) * Math.cos(pitch);
            dy = Math.sin(pitch);
            dz = Math.sin(yaw) * Math.cos(pitch);
        }

        // 主脉步数：约占每脉额度的一半，剩余额度留给分支
        int mainLength = Math.max(3, (int) (config.size() * 0.55));

        // 可写边界：特性生成阶段只保证当前区块 ±1 区块（3x3）可安全写入
        // （越界写入会被 Minecraft 拒绝并刷 "far chunk" 错误日志，导致矿脉被截断）。
        // 各方向再内缩 1 格，给"加厚"的 ±1 邻格留出余量；矿脉触碰边界时会折返而非越界。
        int ocx = origin.getX() >> 4;
        int ocz = origin.getZ() >> 4;
        int[] bounds = {
                ((ocx - 1) << 4) + 1,                        // minX
                ((ocx + 2) << 4) - 2,                        // maxX
                world.getBottomY() + 1,                      // minY
                world.getBottomY() + world.getHeight() - 2,  // maxY
                ((ocz - 1) << 4) + 1,                        // minZ
                ((ocz + 2) << 4) - 2                         // maxZ
        };

        // 记录主脉途经的节点，分支将从这些节点出发
        List<BlockPos> mainPath = new ArrayList<>();

        // 已放置方块数（用数组包装，便于在辅助方法间共享可变计数）
        int[] placed = {0};

        // ---- 生成主脉 ----
        walk(world, random, config, origin.mutableCopy(),
                new double[]{dx, dy, dz}, mainLength, config.size(), placed, mainPath, bounds);

        // ---- 生成分支 ----
        for (int i = 0; i < config.branchCount() && !mainPath.isEmpty(); i++) {
            if (placed[0] >= config.size()) break; // 额度用尽，不再派生分支

            // 分支起点：主脉沿途的随机节点
            BlockPos.Mutable branchPos = mainPath.get(random.nextInt(mainPath.size())).mutableCopy();

            // 分支方向：将主方向绕 Y 轴偏转 60~120 度（随机向左或向右），倾角随机
            double branchYaw = yaw + (Math.PI / 3 + random.nextDouble() * Math.PI / 3)
                    * (random.nextBoolean() ? 1 : -1);
            double branchPitch = (random.nextDouble() - 0.5) * 0.8;
            double bx = Math.cos(branchYaw) * Math.cos(branchPitch);
            double by = Math.sin(branchPitch);
            double bz = Math.sin(branchYaw) * Math.cos(branchPitch);

            walk(world, random, config, branchPos, new double[]{bx, by, bz},
                    config.branchLength(), config.size(), placed, null, bounds);
        }

        // 至少放置了一个矿石才算生成成功
        return placed[0] > 0;
    }

    /**
     * 沿指定方向游走放置矿石（带矿洞感知）。
     *
     * 每步流程：
     *   1. 记录当前节点 → 2. 放置当前格 → 3. 矿洞壁面贴附（若当前在空气）
     *   → 4. 加厚邻近格 → 5. 方向漂移 + 空气引力偏转 → 6. 前进一格。
     *
     * 矿洞感知机制：
     *   - 当 cursor 处于空气（矿洞）时，扫描 6 正交邻居，在矿洞壁面放置矿石，
     *     让矿脉裸露在矿洞表面（独立预算 ≈ 15%，不消耗主脉额度）；
     *   - 当 cursor 连续处于空气超过 2 步时，方向向量向最近的岩壁偏转，
     *     让矿脉主动绕过空洞而非径直穿过（避免被砍成两半）；
     *   - 穿越空气时 placed 计数不变，主脉总长度和密度不受矿洞影响。
     *
     * @param pathRecorder 记录途经节点的列表（可为 null，仅主脉使用，供分支取起点）
     * @param bounds       可写边界 {minX, maxX, minY, maxY, minZ, maxZ}，越界时方向折返
     */
    private void walk(StructureWorldAccess world, Random random, VeinConfig config,
                      BlockPos.Mutable cursor, double[] dir, int steps, int budget,
                      int[] placed, List<BlockPos> pathRecorder, int[] bounds) {
        // 矿洞壁面贴附的独立预算（约 15%，用于在矿洞裸露面放矿）
        int wallAttachBudget = Math.max(1, (int) (budget * 0.15));
        int wallAttached = 0; // 已贴附到壁面的方块数
        // 连续处于空气的步数计数（激活方向引力）
        int airStreak = 0;

        for (int i = 0; i < steps && placed[0] < budget; i++) {
            // 记录当前节点（供分支作为出发点）
            if (pathRecorder != null) {
                pathRecorder.add(cursor.toImmutable());
            }

            // ---- 步骤 1：放置当前格 ----
            boolean placedHere = tryPlace(world, random, config, cursor, placed, bounds);

            // ---- 步骤 2：矿洞壁面贴附 ----
            // 当前格未能放置（大概率在矿洞空气里），尝试在 6 正交邻居放矿，
            // 让矿洞壁面裸露矿石（同时也能帮矿脉"过渡"过空洞）
            if (!placedHere && wallAttached < wallAttachBudget) {
                wallAttached += attachWalls(world, random, config, cursor, placed, bounds,
                        wallAttachBudget - wallAttached);
            }

            // ---- 步骤 3：加厚 ----
            if (placed[0] < budget) {
                if (placedHere) {
                    // cursor 在石头里：正常 3x3x3 加厚
                    if (random.nextFloat() < 0.5F) {
                        int ox = random.nextInt(3) - 1;
                        int oy = random.nextInt(3) - 1;
                        int oz = random.nextInt(3) - 1;
                        BlockPos neighbor = new BlockPos(cursor).add(ox, oy, oz);
                        tryPlace(world, random, config, neighbor, placed, bounds);
                    }
                } else {
                    // cursor 在空气里：加厚优先贴附矿洞壁（只扫 6 正交方向），
                    // 不再走 3x3x3 随机（概率太低，而且 air→air 没意义）
                    if (wallAttached < wallAttachBudget && random.nextFloat() < 0.3F) {
                        wallAttached += attachWalls(world, random, config, cursor, placed, bounds,
                                wallAttachBudget - wallAttached);
                    }
                }
            }

            // ---- 步骤 4：空气检测 + 方向引力 ----
            if (placedHere) {
                airStreak = 0; // 成功放置 → 不在空气里，重置计数
            } else {
                airStreak++;
                // 连续 3+ 步在空气里 → 激活方向引力，往最近岩壁偏转
                if (airStreak >= 3 && placed[0] < budget) {
                    int[] attract = findWallAttract(world, config, cursor, bounds);
                    if (attract != null) {
                        // 吸引权重 0.35：不会突然转向，但能明显让方向偏向岩壁
                        dir[0] += attract[0] * 0.35;
                        dir[1] += attract[1] * 0.35;
                        dir[2] += attract[2] * 0.35;
                    }
                }
            }

            // ---- 步骤 5：方向漂移 + 归一化 ----
            dir[0] += (random.nextDouble() - 0.5) * 0.35;
            dir[1] += (random.nextDouble() - 0.5) * 0.15;
            dir[2] += (random.nextDouble() - 0.5) * 0.35;

            // 归一化前加守卫：方向漂移恰好完全抵消时 len=0 会导致除零 NaN
            double len = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
            if (len < 1e-6) {
                // 给一个随机水平方向作为保底
                double a = random.nextDouble() * Math.PI * 2;
                dir[0] = Math.cos(a);
                dir[1] = 0;
                dir[2] = Math.sin(a);
            } else {
                dir[0] /= len;
                dir[1] /= len;
                dir[2] /= len;
            }

            // ---- 步骤 6：计算位移 ----
            // 归一化后方向向量三分量绝对值之和至少 >= 1，
            // 但 round 后可能恰好全为 0 —— 给一个小扰动避免原地踏步
            int mx = (int) Math.round(dir[0]);
            int my = (int) Math.round(dir[1]);
            int mz = (int) Math.round(dir[2]);
            if (mx == 0 && my == 0 && mz == 0) {
                // 方向分量太小，选绝对值最大的轴强制移动 1 格
                double ax = Math.abs(dir[0]), ay = Math.abs(dir[1]), az = Math.abs(dir[2]);
                if (ax >= ay && ax >= az) mx = dir[0] >= 0 ? 1 : -1;
                else if (ay >= az) my = dir[1] >= 0 ? 1 : -1;
                else mz = dir[2] >= 0 ? 1 : -1;
            }

            // 边界折返：某轴下一步越界时反转该轴方向分量（矿脉像碰壁一样反弹），
            // 保证所有写入都落在可安全写入的 3x3 区块范围内，矿脉不被截断
            if (cursor.getX() + mx < bounds[0] || cursor.getX() + mx > bounds[1]) {
                dir[0] = -dir[0];
                mx = -mx;
            }
            if (cursor.getY() + my < bounds[2] || cursor.getY() + my > bounds[3]) {
                dir[1] = -dir[1];
                my = -my;
            }
            if (cursor.getZ() + mz < bounds[4] || cursor.getZ() + mz > bounds[5]) {
                dir[2] = -dir[2];
                mz = -mz;
            }

            cursor.move(mx, my, mz);
        }
    }

    /**
     * 扫描 cursor 6 个正交邻居，在矿洞壁面放置矿石。
     * 仅在邻居可替换时放置（air → 石头/深板岩 不会被放矿，只有石头/深板岩 → 矿石）。
     *
     * @param cursor 当前位置（大概率在空气里）
     * @param cap    本次贴附最多放多少块（防止一次性吃完壁面预算）
     * @return 本次成功放置的方块数（用于累加到 wallAttached）
     */
    private int attachWalls(StructureWorldAccess world, Random random, VeinConfig config,
                            BlockPos.Mutable cursor, int[] placed, int[] bounds, int cap) {
        int attached = 0;
        for (int[] d : SIX_DIRS) {
            if (attached >= cap) break;
            BlockPos neighbor = new BlockPos(cursor).add(d[0], d[1], d[2]);
            if (tryPlace(world, random, config, neighbor, placed, bounds)) {
                attached++;
            }
        }
        return attached;
    }

    /**
     * 方向引力：扫描 cursor 附近（先 6 邻居，再 2 格半径）找可替换方块，
     * 返回一个整数向量表示哪个方向有可替换方块（用于偏转方向）。
     *
     * 返回格式 {ax, ay, az}：每个分量 = 该方向上发现的可替换方块数（上限 2）。
     * 例如 {2, 0, 1} 表示 +X 方向有 2 块、+Z 方向有 1 块，
     * 方向向量往 +X +Z 偏转。
     *
     * @return 吸引向量；null 表示附近完全没有可替换方块（矿脉可能在大空洞中心）
     */
    private int[] findWallAttract(StructureWorldAccess world, VeinConfig config,
                                  BlockPos.Mutable cursor, int[] bounds) {
        int ax = 0, ay = 0, az = 0;

        // 半径 1：6 邻居
        for (int[] d : SIX_DIRS) {
            if (isPlaceable(world, config, cursor, d[0], d[1], d[2], bounds)) {
                // 根据方向累加分量
                if (d[0] != 0) ax += d[0];
                else if (d[1] != 0) ay += d[1];
                else az += d[2];
            }
        }

        // 半径 2：如果半径 1 没找到（cursor 在大空洞中心），扫描更远
        if (ax == 0 && ay == 0 && az == 0) {
            // 简化：只扫 X±2 / Y±2 / Z±2 六个方向
            int[][] r2 = {{2,0,0},{-2,0,0},{0,2,0},{0,-2,0},{0,0,2},{0,0,-2}};
            for (int[] d : r2) {
                if (isPlaceable(world, config, cursor, d[0], d[1], d[2], bounds)) {
                    if (d[0] != 0) ax += d[0] > 0 ? 1 : -1;
                    else if (d[1] != 0) ay += d[1] > 0 ? 1 : -1;
                    else az += d[2] > 0 ? 1 : -1;
                }
            }
        }

        // 限制分量幅度（防止引力过强导致矿脉急转弯）
        if (ax > 2) ax = 2;
        if (ax < -2) ax = -2;
        if (ay > 2) ay = 2;
        if (ay < -2) ay = -2;
        if (az > 2) az = 2;
        if (az < -2) az = -2;

        // 全部为 0 → 附近没岩壁（大空洞），返回 null 让调用方跳过引力
        return (ax == 0 && ay == 0 && az == 0) ? null : new int[]{ax, ay, az};
    }

    /**
     * 判断 cursor 偏移 (dx,dy,dz) 后位置是否有可替换方块（石头/深板岩等）。
     * 注意：只检查方块类型，不实际放置。
     */
    private boolean isPlaceable(StructureWorldAccess world, VeinConfig config,
                                BlockPos.Mutable cursor, int dx, int dy, int dz,
                                int[] bounds) {
        int px = cursor.getX() + dx;
        int py = cursor.getY() + dy;
        int pz = cursor.getZ() + dz;

        // 边界检查（X/Z/Y 六轴越界直接跳过）
        if (px < bounds[0] || px > bounds[1] || py < bounds[2] || py > bounds[3]
                || pz < bounds[4] || pz > bounds[5]) {
            return false;
        }

        BlockPos checkPos = new BlockPos(px, py, pz);
        if (world.isOutOfHeightLimit(checkPos)) {
            return false;
        }

        BlockState existing = world.getBlockState(checkPos);
        for (OreFeatureConfig.Target target : config.targets()) {
            // 用确定性匹配：isPlaceable 只需要判断方块规则类型，
            // RuleTest 的 chance 规则在此场景下不会触发（我们不消耗 Random 种子）
            // 直接复用同一个 Test 即可（RuleTest.test 对非 chance 规则不依赖 Random）
            if (target.target.test(existing, net.minecraft.util.math.random.Random.create(0L))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 尝试在指定位置放置矿石。
     * 规则与原版 OreFeature 相同：现有方块匹配 targets 中任一替换规则时才替换。
     *
     * @return true 成功放置；false 位置不可替换或越界
     */
    private boolean tryPlace(StructureWorldAccess world, Random random, VeinConfig config,
                             BlockPos pos, int[] placed, int[] bounds) {
        // 超出世界高度范围则跳过
        if (world.isOutOfHeightLimit(pos)) {
            return false;
        }

        // 检查 X/Z 是否在 3x3 区块安全写入范围内 —— 加厚位置可能越过边界，
        // 越界写入会被 Minecraft 拒绝并刷 "far chunk" 错误日志
        int px = pos.getX(), py = pos.getY(), pz = pos.getZ();
        if (px < bounds[0] || px > bounds[1] || py < bounds[2] || py > bounds[3]
                || pz < bounds[4] || pz > bounds[5]) {
            return false;
        }

        BlockState existing = world.getBlockState(pos);
        for (OreFeatureConfig.Target target : config.targets()) {
            // RuleTest 判断现有方块是否可被替换（石头/深板岩/下界岩等）
            // 注意：Yarn 映射中 Target 是带 public final 字段的类，直接字段访问而非 record 访问器
            if (target.target.test(existing, random)) {
                // 标志位 2 与原版矿石一致：发送客户端更新，不触发邻居方块更新
                world.setBlockState(pos, target.state, 2);
                placed[0]++;
                return true;
            }
        }
        return false;
    }
}
