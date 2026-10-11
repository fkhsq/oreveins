package com.oreveins.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.oreveins.OreVeinMod;
import com.oreveins.VeinAutoInjector;

import net.minecraft.registry.Registry;
import net.minecraft.registry.SimpleRegistry;

/**
 * 注册表冻结 Mixin —— 方案B的核心时机钩子。
 *
 * 1.20.1 中动态注册表（含 PLACED_FEATURE 放置特征）在数据包加载完成后、
 * 由 RegistryLoader.load 统一调用 freeze() 冻结。Fabric API 没有提供
 * 冻结前注册动态特征的入口，因此只能用 Mixin 在 freeze() 开头插入：
 *
 *   - 此时所有数据包（含第三方模组矿石 JSON）已加载完毕；
 *   - 注册表尚未冻结，仍可安全地注册新特征；
 *   - Fabric 的群系修改（MinecraftServer 构造阶段）在其后执行，
 *     能引用到我们新注册的矿脉特征。
 *
 * 安全性：freeze() 内抛出的异常会被 RegistryLoader 记为注册表加载失败，
 * 直接导致进世界崩溃。因此注入逻辑自带 try/catch，任何失败只记日志，
 * 第三方矿石按原有方式生成（回退机制）。
 */
@Mixin(SimpleRegistry.class)
public abstract class SimpleRegistryMixin<T> {

    /**
     * 在 freeze() 执行前尝试注入第三方矿石的矿脉版特征。
     * 对除 PLACED_FEATURE 以外的注册表无副作用（inject 内部按注册表键过滤）。
     */
    @Inject(method = "freeze", at = @At("HEAD"))
    private void oreveins$beforeFreeze(CallbackInfoReturnable<Registry<T>> cir) {
        try {
            // 此刻：数据包已加载、注册表未冻结 —— 动态注册的最后安全时机
            VeinAutoInjector.inject((SimpleRegistry<?>) (Object) this);
        } catch (Throwable t) {
            // 绝不让异常逃逸到 freeze()：否则整个注册表加载失败，无法进入世界
            OreVeinMod.LOGGER.error("[OreVeins] 自动矿脉化注入时发生意外错误，已整体跳过（所有矿石按原方式生成）", t);
        }
    }
}
