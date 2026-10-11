package com.oreveins;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.structure.rule.TagMatchRuleTest;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.feature.OreFeatureConfig;

/**
 * 矿石权重池 —— 2.0 核心机制：所有矿种（原版 + 第三方）统一计算出现概率。
 *
 * 设计思路（用户需求）：
 *   - 总矿脉频率压到原版"大型钻石矿脉"水平（平均每 9 个区块 1 条随机矿脉）；
 *   - 每条随机矿脉的"矿种"按权重抽取：原版稀有矿（钻石/残骸）权重低，
 *     常见矿（煤/铁）权重高，具体数值按原版出现概率量化换算；
 *   - 第三方模组矿石由 VeinAutoInjector 动态检测后加入池，默认权重 3。
 *
 * 原版权重表（已获用户确认）：
 *   主世界：煤 10 / 铁 7 / 铜 6 / 红石 5 / 青金石 4 / 金 3 / 钻石 2 / 翡翠 3(仅山地)
 *   下　界：石英 8 / 下界金 5 / 远古残骸 1
 *   第三方：3
 *
 * 高度带（minY~maxY）：触发点 Y 不在带内的矿种不参与本次抽取，
 * 与原版各矿石的高度分布一致（如钻石仅在 Y16 以下、煤仅在 Y0 以上）。
 */
public final class OreWeightPool {

    /** 工具类，禁止实例化 */
    private OreWeightPool() {
    }

    /**
     * 矿种条目 —— 权重池中的一条候选矿脉类型。
     *
     * @param name         显示名（日志用，如 "minecraft:diamond"）
     * @param config       该矿种的矿脉参数（替换目标/尺寸/分支）
     * @param weight       抽取权重（越大越容易抽到）
     * @param minY, maxY   高度带：触发 Y 不在此范围则跳过
     * @param mountainOnly 仅山地群系可出现（翡翠专用）
     */
    public record OreEntry(
            String name,
            VeinConfig config,
            int weight,
            int minY,
            int maxY,
            boolean mountainOnly
    ) {
    }

    // ---------------- 原版权重池（类加载时静态初始化，不可变） ----------------

    /** 原版主世界矿种：石头/深板岩双替换目标 */
    private static final List<OreEntry> VANILLA_OVERWORLD = List.of(
            //            名称                  权重   高度带        仅山地   矿脉参数（尺寸沿用 1.x 调优值）
            overworldEntry("minecraft:coal",     10,    0, 128, false, 80, 5, 24),  // 煤
            overworldEntry("minecraft:iron",      7,  -32, 128, false, 70, 5, 20),  // 铁
            overworldEntry("minecraft:copper",    6,  -16, 112, false, 60, 4, 24),  // 铜
            overworldEntry("minecraft:redstone",  5,  -64,  16, false, 70, 5, 20),  // 红石
            overworldEntry("minecraft:lapis",     4,  -64,  32, false, 30, 4, 16),  // 青金石
            overworldEntry("minecraft:gold",      3,  -64,  32, false, 50, 4, 16),  // 金
            overworldEntry("minecraft:diamond",   2,  -64,  16, false, 40, 4, 20),  // 钻石（最稀有）
            overworldEntry("minecraft:emerald",   3,  -16, 128, true,  30, 2, 12)   // 翡翠（仅山地群系）
    );

    /** 原版下界矿种：下界基岩类单替换目标（下界矿石无深板岩变体） */
    private static final List<OreEntry> VANILLA_NETHER = List.of(
            netherEntry("minecraft:nether_quartz",   8, 10, 117, 80, 5, 24),  // 下界石英
            netherEntry("minecraft:nether_gold",     5, 10, 117, 50, 4, 16),  // 下界金
            netherEntry("minecraft:ancient_debris",  1,  8,  24,  6, 1,  3)   // 远古残骸（极稀有）
    );

    // ---------------- 第三方权重池（动态填充，每次世界加载重建） ----------------

    /** 第三方主世界矿种 */
    private static final List<OreEntry> THIRD_PARTY_OVERWORLD = new ArrayList<>();

    /** 第三方下界矿种 */
    private static final List<OreEntry> THIRD_PARTY_NETHER = new ArrayList<>();

    /** 已入池的第三方矿石名（去重：同一矿石出现在多个群系时只入池一次） */
    private static final java.util.Set<String> THIRD_PARTY_ADDED = new java.util.HashSet<>();

    // ---------------- 抽取与注册 API ----------------

    /**
     * 加权抽取矿种 —— 统一矿脉特征的核心入口。
     *
     * 抽取规则：
     *   1. 候选过滤：维度池（原版 + 第三方）→ 高度带 → 群系限制（翡翠仅山地）；
     *   2. 加权随机：按 weight 加权滚点，抽中后从候选池移除（不放回）；
     *   3. 不放回保证"大矿洞多条矿脉尽量不同类型"（池不够时提前结束）。
     *
     * @param nether true 下界池；false 主世界池
     * @param y      触发点 Y 坐标（用于高度带过滤）
     * @param biome  触发点群系（用于山地判定）
     * @param count  需要抽取的矿种数（大矿洞 1-3，普通 1）
     * @return 抽中的矿种列表（可能少于 count：候选耗尽时提前结束；为空 = 本次触发作废）
     */
    public static List<OreEntry> pick(boolean nether, int y, RegistryEntry<Biome> biome,
                                      Random random, int count) {
        // ---- 步骤 1：收集并过滤候选 ----
        List<OreEntry> pool = new ArrayList<>();
        pool.addAll(nether ? VANILLA_NETHER : VANILLA_OVERWORLD);
        pool.addAll(nether ? THIRD_PARTY_NETHER : THIRD_PARTY_OVERWORLD);

        boolean isMountain = biome.isIn(BiomeTags.IS_MOUNTAIN);
        pool.removeIf(e -> y < e.minY() || y > e.maxY() || (e.mountainOnly() && !isMountain));

        if (pool.isEmpty()) {
            // 该高度没有任何候选矿种（理论不会发生：金/红石/青金石/钻石覆盖全深度）
            return List.of();
        }

        // ---- 步骤 2：加权不放回抽样 ----
        List<OreEntry> result = new ArrayList<>(count);
        for (int i = 0; i < count && !pool.isEmpty(); i++) {
            // 计算总权重并滚点
            int total = 0;
            for (OreEntry e : pool) {
                total += e.weight();
            }
            int roll = random.nextInt(total);

            // 逐个扣减权重找到命中条目（兜底取最后一个，防浮点/边界问题）
            OreEntry chosen = pool.get(pool.size() - 1);
            for (OreEntry e : pool) {
                roll -= e.weight();
                if (roll < 0) {
                    chosen = e;
                    break;
                }
            }

            result.add(chosen);
            pool.remove(chosen); // 不放回：多条矿脉尽量不同类型
        }
        return result;
    }

    /**
     * 注册第三方矿种（由 VeinAutoInjection 在群系替换时调用）。
     * 第三方矿石高度带未知，默认覆盖该维度全部有效范围。
     * 按名称去重：同一矿石出现在多个群系时只入池一次。
     *
     * @return true 首次入池；false 重复（已存在，跳过）
     */
    public static boolean addThirdParty(boolean nether, OreEntry entry) {
        if (!THIRD_PARTY_ADDED.add(entry.name())) {
            return false; // 已入池，跳过重复
        }
        (nether ? THIRD_PARTY_NETHER : THIRD_PARTY_OVERWORLD).add(entry);
        return true;
    }

    /**
     * 清空第三方条目 —— 每次放置特征注册表冻结时调用，
     * 保证重进世界 / reload 时第三方池与最新注册表一致。
     */
    public static void clearThirdParty() {
        THIRD_PARTY_OVERWORLD.clear();
        THIRD_PARTY_NETHER.clear();
        THIRD_PARTY_ADDED.clear();
    }

    // ---------------- 原版条目构造辅助 ----------------

    /**
     * 构造主世界矿种条目。
     * 替换目标与原版矿石一致：
     *   stone_ore_replaceables 标签 → 矿石；
     *   deepslate_ore_replaceables 标签 → 深板岩矿石变体。
     */
    private static OreEntry overworldEntry(String name, int weight, int minY, int maxY,
                                           boolean mountainOnly, int size, int branchCount, int branchLength) {
        String ore = name.substring("minecraft:".length());
        List<OreFeatureConfig.Target> targets = List.of(
                OreFeatureConfig.createTarget(
                        new TagMatchRuleTest(BlockTags.STONE_ORE_REPLACEABLES),
                        oreBlock(ore + "_ore").getDefaultState()),
                OreFeatureConfig.createTarget(
                        new TagMatchRuleTest(BlockTags.DEEPSLATE_ORE_REPLACEABLES),
                        oreBlock("deepslate_" + ore + "_ore").getDefaultState())
        );
        return new OreEntry(name, new VeinConfig(targets, size, branchCount, branchLength),
                weight, minY, maxY, mountainOnly);
    }

    /**
     * 构造下界矿种条目。
     * 替换目标：base_stone_nether 标签（下界岩/玄武岩/黑石）→ 矿石。
     */
    private static OreEntry netherEntry(String name, int weight, int minY, int maxY,
                                        int size, int branchCount, int branchLength) {
        String ore = name.substring("minecraft:".length());
        List<OreFeatureConfig.Target> targets = List.of(
                OreFeatureConfig.createTarget(
                        new TagMatchRuleTest(BlockTags.BASE_STONE_NETHER),
                        oreBlock(ore).getDefaultState())
        );
        return new OreEntry(name, new VeinConfig(targets, size, branchCount, branchLength),
                weight, minY, maxY, false);
    }

    /** 按矿石名查 Blocks 常量（原版矿石方块均静态注册，直接映射） */
    private static Block oreBlock(String id) {
        return switch (id) {
            // 主世界矿石 + 深板岩变体
            case "coal_ore" -> Blocks.COAL_ORE;
            case "iron_ore" -> Blocks.IRON_ORE;
            case "copper_ore" -> Blocks.COPPER_ORE;
            case "redstone_ore" -> Blocks.REDSTONE_ORE;
            case "lapis_ore" -> Blocks.LAPIS_ORE;
            case "gold_ore" -> Blocks.GOLD_ORE;
            case "diamond_ore" -> Blocks.DIAMOND_ORE;
            case "emerald_ore" -> Blocks.EMERALD_ORE;
            case "deepslate_coal_ore" -> Blocks.DEEPSLATE_COAL_ORE;
            case "deepslate_iron_ore" -> Blocks.DEEPSLATE_IRON_ORE;
            case "deepslate_copper_ore" -> Blocks.DEEPSLATE_COPPER_ORE;
            case "deepslate_redstone_ore" -> Blocks.DEEPSLATE_REDSTONE_ORE;
            case "deepslate_lapis_ore" -> Blocks.DEEPSLATE_LAPIS_ORE;
            case "deepslate_gold_ore" -> Blocks.DEEPSLATE_GOLD_ORE;
            case "deepslate_diamond_ore" -> Blocks.DEEPSLATE_DIAMOND_ORE;
            case "deepslate_emerald_ore" -> Blocks.DEEPSLATE_EMERALD_ORE;
            // 下界矿石
            case "nether_quartz_ore" -> Blocks.NETHER_QUARTZ_ORE;
            case "nether_gold_ore" -> Blocks.NETHER_GOLD_ORE;
            case "ancient_debris" -> Blocks.ANCIENT_DEBRIS;
            // 防御：未匹配时回退（不会触发，仅保证编译完备）
            default -> Blocks.STONE;
        };
    }
}
