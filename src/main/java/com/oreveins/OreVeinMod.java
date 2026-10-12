package com.oreveins;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// OreVeinMod - 矿脉式矿石生成模组的主入口类
// 实现 ModInitializer 接口，Fabric Loader 会在模组加载时调用 onInitialize 方法
public class OreVeinMod implements ModInitializer {

    // 模组 ID - 必须与 fabric.mod.json 中的 id 字段一致
    public static final String MOD_ID = "oreveins";

    // SLF4J Logger - Fabric 推荐的标准日志接口
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // 模组初始化方法 - Fabric Loader 在所有模组加载时调用
    @Override
    public void onInitialize() {
        // 第一步：移除原版所有矿石生成逻辑
        // 生物群系矿石特征通过 Fabric 生物群系修改 API 移除，
        // 原版大型铜/铁矿脉通过 noise_settings 数据覆盖禁用，
        // 两者均在世界创建阶段（注册表冻结时）生效，早于出生点初始区块的生成
        OreRemover.register();

        // 第二步：注册统一矿脉特征（主世界/下界各一个），
        // 触发频率锚定原版钻石大矿脉（约 9 区块 1 条），
        // 触发时从 OreWeightPool 加权抽取矿种（含第三方矿石）
        ModFeatures.register();
        VeinInjection.register();

        // 第三步（方案B）：自动把第三方模组的矿石特征收入统一权重池
        // 扫描/收集/替换任一环节失败时自动回退为原特征生成，
        // 不影响原版矿物（minecraft 命名空间全排除）与本模组自身特征
        VeinAutoInjection.register();

        LOGGER.info("OreVeins 模组初始化完成！版本: 2.0.1（统一权重矿脉）");
    }
}
