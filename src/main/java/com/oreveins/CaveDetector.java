package com.oreveins;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;

/**
 * 矿洞检测器 —— 2.0 新增：在统一矿脉触发点识别矿洞规模。
 *
 * 1.18+ 的矿洞由雕刻器（Carver）系统生成：
 *   - cave / cave_extra_underground（probability 0.15）→ 大型奶酪洞/洞穴
 *   - canyon（probability 0.01）→ 细长裂隙
 *
 * 特征生成阶段无法直接查询雕刻器配置，因此用"空气密度扫描"间接识别：
 *   - 在触发点周围采样一个立方体区域，统计空气占比与绝对数量；
 *   - 空气占比高 = 大矿洞（奶酪洞），少量空气 = 小矿洞（隧道/裂隙），
 *     几乎无空气 = 实心岩层（普通矿脉，随机游走生成）。
 *
 * 判定阈值（已获用户确认）：
 *   大矿洞：空气占比 ≥ 22% 且采样空气数 ≥ 30 → 一次生成 1-3 条贴壁矿脉
 *   小矿洞：空气占比 ≥ 4%  且采样空气数 ≥ 6  → 生成 1 条贴壁矿脉
 *   无矿洞：其余情况                        → 生成 1 条普通矿脉
 */
public final class CaveDetector {

    /** 工具类，禁止实例化 */
    private CaveDetector() {
    }

    // ---- 扫描参数 ----
    /** 水平扫描半径（格）：±10 格保持在 3x3 区块安全读取范围内 */
    private static final int RADIUS_H = 10;
    /** 垂直扫描半径（格） */
    private static final int RADIUS_V = 8;
    /** 采样步长（格）：每 2 格采样一次，兼顾精度与性能 */
    private static final int STEP = 2;

    // ---- 判定阈值 ----
    /** 大矿洞：空气占比阈值 */
    private static final float BIG_RATIO = 0.22F;
    /** 大矿洞：最小空气采样数（防止稀疏噪声误判） */
    private static final int BIG_MIN_AIR = 30;
    /** 小矿洞：空气占比阈值 */
    private static final float SMALL_RATIO = 0.04F;
    /** 小矿洞：最小空气采样数 */
    private static final int SMALL_MIN_AIR = 6;

    /** 多条贴壁矿脉起点之间的最小水平间隔（格）：避免矿脉挤在同一处 */
    private static final int START_SPACING = 8;

    /**
     * 矿洞扫描结果。
     *
     * @param bigCave   是否大矿洞（1-3 条矿脉）
     * @param smallCave 是否小矿洞（1 条贴壁矿脉）
     * @param airCells  扫描到的空气格采样点（贴壁起点候选）
     */
    public record ScanResult(boolean bigCave, boolean smallCave, List<BlockPos> airCells) {
        /** 大或小矿洞（贴壁生成模式） */
        public boolean hasCave() {
            return bigCave || smallCave;
        }
    }

    /**
     * 扫描触发点附近的空气分布，判定矿洞规模。
     *
     * 采样区域：21×17×21 格，步长 2 → 11×9×11 = 1089 个采样点。
     * 统一特征触发频率低（每 9 区块 1 次），扫描开销可接受。
     */
    public static ScanResult scan(StructureWorldAccess world, BlockPos origin) {
        List<BlockPos> airCells = new ArrayList<>();
        int total = 0;

        for (int dx = -RADIUS_H; dx <= RADIUS_H; dx += STEP) {
            for (int dz = -RADIUS_H; dz <= RADIUS_H; dz += STEP) {
                for (int dy = -RADIUS_V; dy <= RADIUS_V; dy += STEP) {
                    BlockPos pos = origin.add(dx, dy, dz);
                    total++;
                    // 超出世界高度范围的采样点不计空气
                    if (!world.isOutOfHeightLimit(pos) && world.getBlockState(pos).isAir()) {
                        airCells.add(pos);
                    }
                }
            }
        }

        float ratio = total == 0 ? 0F : (float) airCells.size() / total;
        boolean big = ratio >= BIG_RATIO && airCells.size() >= BIG_MIN_AIR;
        boolean small = !big && ratio >= SMALL_RATIO && airCells.size() >= SMALL_MIN_AIR;
        return new ScanResult(big, small, airCells);
    }

    /**
     * 从空气采样点中挑选"贴壁起点"——矿脉将从此处的岩壁/洞底伸入岩层。
     *
     * 挑选规则：
     *   1. 随机打乱空气点，逐个尝试；
     *   2. 与已选起点保持至少 8 格水平间隔（多条矿脉分散在矿洞不同位置）；
     *   3. 在该空气格的 6 个正交邻居中找可替换基岩（优先洞底，再四壁，最后洞顶）。
     *
     * @return 起点列表，每个元素为 {固体起点, 相邻空气格}；
     *         不足 count 个时返回实际找到的数量（可能为空）
     */
    public static List<BlockPos[]> pickWallStarts(StructureWorldAccess world, Random random,
                                                  List<BlockPos> airCells, int count) {
        List<BlockPos[]> starts = new ArrayList<>();
        if (airCells.isEmpty() || count <= 0) {
            return starts;
        }

        // Fisher-Yates 洗牌（net.minecraft Random 无 shuffle，手动实现）
        List<BlockPos> shuffled = new ArrayList<>(airCells);
        for (int i = shuffled.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            BlockPos tmp = shuffled.get(i);
            shuffled.set(i, shuffled.get(j));
            shuffled.set(j, tmp);
        }

        for (BlockPos air : shuffled) {
            if (starts.size() >= count) {
                break;
            }
            // 距离约束：与已选起点保持水平间隔，让矿脉分散在矿洞各处
            boolean farEnough = true;
            for (BlockPos[] s : starts) {
                if (Math.abs(s[1].getX() - air.getX()) + Math.abs(s[1].getZ() - air.getZ()) < START_SPACING) {
                    farEnough = false;
                    break;
                }
            }
            if (!farEnough) {
                continue;
            }

            // 找该空气格相邻的可替换基岩
            BlockPos solid = findAdjacentSolid(world, air);
            if (solid != null) {
                starts.add(new BlockPos[]{solid, air});
            }
        }
        return starts;
    }

    /**
     * 在空气格的 6 个正交邻居中寻找可替换基岩（矿洞壁面）。
     * 检查顺序：下方（洞底）→ 四侧（洞壁）→ 上方（洞顶），
     * 优先把矿脉放在洞底/洞壁——最符合"矿洞里裸露矿石"的视觉。
     *
     * @return 可替换基岩位置；null 表示该空气格四周没有可替换方块
     */
    private static BlockPos findAdjacentSolid(StructureWorldAccess world, BlockPos air) {
        BlockPos[] candidates = {
                air.down(), air.north(), air.south(), air.east(), air.west(), air.up()
        };
        for (BlockPos pos : candidates) {
            if (world.isOutOfHeightLimit(pos)) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (isReplaceableRock(state)) {
                return pos;
            }
        }
        return null;
    }

    /**
     * 判断方块是否为可放置矿石的基岩。
     * 与原版 overworld_carver_replaceables / nether_carver_replaceables 标签对齐：
     * 矿洞壁面由雕刻器从这些方块中挖出，因此贴壁起点只需匹配这些常见基岩。
     * （真正的放置仍由 VeinFeature.tryPlace 的 RuleTest 严格校验，此处仅做起点预筛）
     */
    private static boolean isReplaceableRock(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.STONE || block == Blocks.GRANITE
                || block == Blocks.DIORITE || block == Blocks.ANDESITE
                || block == Blocks.DEEPSLATE || block == Blocks.TUFF
                || block == Blocks.NETHERRACK || block == Blocks.BASALT
                || block == Blocks.BLACKSTONE;
    }
}
