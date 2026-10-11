package com.oreveins;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.SimpleRegistry;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.OreFeatureConfig;
import net.minecraft.world.gen.feature.PlacedFeature;

/**
 * 自动矿脉化注入器（2.0）—— 在放置特征注册表冻结前，
 * 扫描所有"第三方模组矿石"，把它们收集为权重池候选条目。
 *
 * 与 1.x 的区别：不再为第三方矿石单独注册矿脉版 placed_feature，
 * 而是收集"待处理条目"（替换目标 + 矿脉尺寸），由 VeinAutoInjection
 * 在群系替换阶段按维度加入 OreWeightPool —— 第三方矿石与原版矿石
 * 一起参与统一权重抽取（默认权重 3，用户指定）。
 *
 * 识别规则（三条硬规则，与 1.x 一致）：
 *  1. 命名空间为 minecraft 的特征一律跳过 —— 原版矿物由 OreWeightPool
 *     静态条目接管，此流程完全不影响原版生成；
 *  2. 命名空间为 oreveins 的特征一律跳过 —— 防止把本模组自己的特征收进池；
 *  3. 仅处理特征类型为原版 ore / scattered_ore 且配置为
 *     OreFeatureConfig 的特征，其余类型（树、湖、结构等）不动。
 *
 * 尺寸换算（与 1.x 相同公式）：
 *  - 矿脉方块总数 vein_size = 原矿团 size × 0.7（夹 1~200）
 *  - 分支档位：≤8 → 1×6；≤16 → 2×8；≤32 → 2×10；更大 → 3×12
 */
public final class VeinAutoInjector {

    /** 工具类，禁止实例化 */
    private VeinAutoInjector() {
    }

    /**
     * 待处理条目 —— 一个第三方矿石的权重池注册信息。
     *
     * @param name          显示名（原特征 ID，如 "test_ores:test_ore"）
     * @param targets       替换目标（复用原特征的石头→矿石映射）
     * @param veinSize      矿脉方块总数
     * @param branchCount   分支数量
     * @param branchLength  分支长度
     */
    public record PendingOre(
            String name,
            List<OreFeatureConfig.Target> targets,
            int veinSize,
            int branchCount,
            int branchLength
    ) {
    }

    /**
     * 原特征注册表键 -> 待处理条目 的映射。
     * VeinAutoInjection 在群系替换阶段依据此表移除原特征并填充权重池。
     * 每次世界加载（注册表冻结前）都会重建。
     */
    public static final Map<RegistryKey<PlacedFeature>, PendingOre> PENDING = new HashMap<>();

    /**
     * 权重池是否已由群系替换阶段填充过第三方矿石。
     *
     * 时序保护（bug 修复）：放置特征注册表在服务器启动过程中会被多个
     * 维度加载流程重复触发 freeze（收集日志会刷多遍）。其中最后一次
     * freeze 发生在群系替换（入池）**之后** —— 若此时仍无条件执行
     * clearThirdParty()，会把刚入池的第三方矿石清掉，而群系替换不会
     * 重跑，导致第三方矿石被移除了原特征却不在池中，永不生成。
     *
     * 因此只在"从未入池过"时才清池；重进世界 / reload 时旧池条目
     * 由 OreWeightPool 的名称去重机制自动保持（addThirdParty 返回 false）。
     */
    static boolean poolSeeded = false;

    /** 第三方矿石统一权重（用户指定：与金/翡翠同级） */
    public static final int THIRD_PARTY_WEIGHT = 3;

    /**
     * 注入入口 —— 由 SimpleRegistryMixin 在 freeze() 开头调用。
     * 可能对所有动态注册表触发，内部先按注册表键过滤。
     *
     * @param registry 即将被冻结的注册表
     */
    @SuppressWarnings("unchecked")
    public static void inject(SimpleRegistry<?> registry) {
        // 只处理放置特征注册表，其余注册表直接返回
        if (!RegistryKeys.PLACED_FEATURE.equals(registry.getKey())) {
            return;
        }
        SimpleRegistry<PlacedFeature> placed = (SimpleRegistry<PlacedFeature>) registry;

        // 每次冻结都重建待处理表（幂等，无害）
        PENDING.clear();
        // 权重池只在从未入池过时才清空 —— 防止群系替换后的重复 freeze
        // 把已入池的第三方矿石清掉（见 poolSeeded 字段说明）
        if (!poolSeeded) {
            OreWeightPool.clearThirdParty();
        }

        // 快照后遍历：本方法只读注册表（不再写入），快照仅为防御性编程
        List<Map.Entry<RegistryKey<PlacedFeature>, PlacedFeature>> snapshot =
                new ArrayList<>(placed.getEntrySet());

        for (Map.Entry<RegistryKey<PlacedFeature>, PlacedFeature> entry : snapshot) {
            try {
                // 逐个特征独立尝试：单个失败不影响其余特征
                collect(entry.getKey(), entry.getValue());
            } catch (Throwable t) {
                OreVeinMod.LOGGER.error("[OreVeins] 收集第三方矿石 {} 失败，该矿石将按原方式生成",
                        entry.getKey().getValue(), t);
            }
        }

        if (PENDING.isEmpty()) {
            OreVeinMod.LOGGER.info("[OreVeins] 自动矿脉化：未发现可处理的第三方矿石特征（共扫描 {} 项）", snapshot.size());
        } else {
            OreVeinMod.LOGGER.info("[OreVeins] 自动矿脉化：已收集 {} 个第三方矿石特征，待群系替换时加入权重池", PENDING.size());
        }
    }

    /**
     * 尝试收集单个第三方放置特征为权重池候选。
     * 所有"识别不通过"的情况直接 return（原特征不受影响，群系替换阶段也不会处理它）。
     */
    private static void collect(RegistryKey<PlacedFeature> key, PlacedFeature original) {
        Identifier id = key.getValue();
        String namespace = id.getNamespace();

        // 硬规则1：原版特征跳过（原版矿物由 OreWeightPool 静态条目接管）
        // 硬规则2：本模组特征跳过（防止把自己的特征收进池）
        if (namespace.equals("minecraft") || namespace.equals(OreVeinMod.MOD_ID)) {
            return;
        }

        // 硬规则3：只处理原版矿石类型（ore / scattered_ore）的特征
        ConfiguredFeature<?, ?> configured = original.feature().value();
        if (configured.feature() != Feature.ORE
                && configured.feature() != Feature.SCATTERED_ORE) {
            return;
        }
        if (!(configured.config() instanceof OreFeatureConfig oreConfig)) {
            return;
        }

        // ---- 尺寸换算：与原版矿团产量守恒的同一套公式 ----
        // 矿脉目标方块数 = 原矿团 size × 0.7（夹在 1~200 的合法范围内）
        int veinSize = Math.max(1, Math.min(200, Math.round(oreConfig.size * 0.7F)));

        // 分支档位：按矿脉大小决定分支数量与长度
        int branchCount;
        int branchLength;
        if (veinSize <= 8) {
            branchCount = 1;
            branchLength = 6;
        } else if (veinSize <= 16) {
            branchCount = 2;
            branchLength = 8;
        } else if (veinSize <= 32) {
            branchCount = 2;
            branchLength = 10;
        } else {
            branchCount = 3;
            branchLength = 12;
        }

        // 收集为待处理条目（复用原特征的替换目标：石头/深板岩等 -> 矿石方块）
        PENDING.put(key, new PendingOre(id.toString(), oreConfig.targets,
                veinSize, branchCount, branchLength));

        OreVeinMod.LOGGER.info("[OreVeins] 已收集第三方矿石: {}（矿团尺寸 {} -> 矿脉 {}，分支 {}×{}，权重 {}）",
                id, oreConfig.size, veinSize, branchCount, branchLength, THIRD_PARTY_WEIGHT);
    }
}
