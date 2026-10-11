package com.oreveins;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.biome.v1.BiomeModificationContext;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectionContext;
import net.fabricmc.fabric.api.biome.v1.ModificationPhase;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.feature.PlacedFeature;

/**
 * 自动矿脉化 —— 群系替换器（2.0 第二步）。
 *
 * 在 Fabric 群系修改的最后阶段（POST_PROCESSING）执行：
 * 遍历每个群系的特征列表，找到 VeinAutoInjector 已收集的第三方矿石特征，
 * 把它们从群系中移除，并按群系维度加入 OreWeightPool 权重池 ——
 * 此后第三方矿石与原版矿石一起参与统一权重抽取。
 *
 * 维度分类（决定加入哪个权重池）：
 *   - IS_NETHER 标签 → 下界池
 *   - IS_END 标签    → 跳过（本模组未接管末地矿石，保留原样生成）
 *   - 其余           → 主世界池
 *
 * 末地保护：统一矿脉特征只注入主世界/下界群系，若把末地群系的
 * 第三方矿石也移除，会导致它们永远不生成 —— 因此末地群系完全不动。
 *
 * 执行顺序（与 1.x 相同的回退保证）：
 * 先入池、后移除 —— 入池只是内存列表操作不会失败；若中途异常，
 * 该群系的原特征不会被移除，矿石按原有方式生成。
 */
public final class VeinAutoInjection {

    /** 工具类，禁止实例化 */
    private VeinAutoInjection() {
    }

    /**
     * 注册群系替换逻辑，需在模组初始化（onInitialize）时调用。
     * 注意：此处只是注册"修改器"，实际执行发生在世界加载完成、
     * 群系注册表就绪之后（此时 VeinAutoInjector.PENDING 已填充完毕）。
     */
    public static void register() {
        BiomeModifications.create(new Identifier(OreVeinMod.MOD_ID, "auto_veinize"))
                .add(ModificationPhase.POST_PROCESSING,
                        selectionContext -> true,          // 对所有群系生效（含模组群系）
                        VeinAutoInjection::apply);
        OreVeinMod.LOGGER.info("[OreVeins] 已注册第三方矿石权重池注入器（POST_PROCESSING 阶段）");
    }

    /**
     * 单个群系的替换逻辑：移除第三方矿石原特征 + 按维度加入权重池。
     *
     * @param selection 提供 getBiome() / hasTag() —— 可读取群系当前状态
     * @param context   提供特征增删接口
     */
    private static void apply(BiomeSelectionContext selection, BiomeModificationContext context) {
        // 没有任何可处理的第三方矿石（或扫描阶段全部失败）时直接返回
        if (VeinAutoInjector.PENDING.isEmpty()) {
            return;
        }

        try {
            // ---- 维度分类 ----
            // 末地群系：本模组未接管末地矿石，完全跳过（不移除、不入池）
            if (selection.hasTag(BiomeTags.IS_END)) {
                return;
            }
            boolean nether = selection.hasTag(BiomeTags.IS_NETHER);

            // ---- 特征列表：外层索引 = 生成步骤，内层 = 该步骤的放置特征 ----
            // 此时读到的是包括其他模组先前修改在内的最新状态
            List<RegistryEntryList<PlacedFeature>> features =
                    selection.getBiome().getGenerationSettings().getFeatures();
            GenerationStep.Feature[] steps = GenerationStep.Feature.values();

            int swapCount = 0;
            for (int step = 0; step < features.size() && step < steps.length; step++) {
                // 先收集本步骤命中的原特征键，再统一处理（避免边遍历边修改）
                List<RegistryKey<PlacedFeature>> hits = null;
                for (RegistryEntry<PlacedFeature> entry : features.get(step)) {
                    if (entry.getKey().map(VeinAutoInjector.PENDING::containsKey).orElse(false)) {
                        if (hits == null) {
                            hits = new ArrayList<>();
                        }
                        entry.getKey().ifPresent(hits::add);
                    }
                }
                if (hits == null) {
                    continue;
                }

                for (RegistryKey<PlacedFeature> original : hits) {
                    VeinAutoInjector.PendingOre ore = VeinAutoInjector.PENDING.get(original);

                    // 先入池（内存操作不会失败；重复入池由 OreWeightPool 自动去重）
                    boolean firstTime = OreWeightPool.addThirdParty(nether,
                            new OreWeightPool.OreEntry(
                                    ore.name(),
                                    new VeinConfig(ore.targets(), ore.veinSize(),
                                            ore.branchCount(), ore.branchLength()),
                                    VeinAutoInjector.THIRD_PARTY_WEIGHT,
                                    nether ? 10 : -64,   // 高度带：第三方矿石默认覆盖全维度
                                    nether ? 117 : 128,
                                    false));

                    // 首次入池后标记：此后注册表的重复 freeze 不再清空权重池
                    // （防止入池之后触发的 freeze 把已入池的第三方矿石清掉）
                    if (firstTime) {
                        VeinAutoInjector.poolSeeded = true;
                    }

                    // 再移除原版 —— 若上一步抛异常，这里不会执行，原版保留
                    context.getGenerationSettings().removeFeature(steps[step], original);
                    swapCount++;
                    if (firstTime) {
                        OreVeinMod.LOGGER.info("[OreVeins] 第三方矿石 {} 已加入{}权重池（权重 {}）",
                                ore.name(), nether ? "下界" : "主世界", VeinAutoInjector.THIRD_PARTY_WEIGHT);
                    }
                }
            }

            if (swapCount > 0) {
                OreVeinMod.LOGGER.info("[OreVeins] 群系 {}：已移除 {} 个第三方矿石特征（由统一权重池接管）",
                        selection.getBiomeKey().getValue(), swapCount);
            }
        } catch (Throwable t) {
            // 单个群系失败不影响其他群系；该群系未被移除的原特征仍按原方式生成
            OreVeinMod.LOGGER.error("[OreVeins] 群系 {} 的权重池注入失败，该群系按原方式生成",
                    selection.getBiomeKey().getValue(), t);
        }
    }
}
