# OreVeins

> Minecraft 1.20.1 Fabric 模组：统一权重矿脉生成
> A Minecraft 1.20.1 Fabric mod: unified weighted ore vein generation

## 简介 | Introduction

把原版分散的点状矿石改成稀疏但完整的长条枝杈状矿脉，增加采矿的探索感和资源集中度。

Replaces vanilla scattered ore dots with sparse but complete branching vein structures, making mining more exploratory and resource-dense.

## 特性 | Features

- **统一权重矿脉池** — 按权重抽取矿种，主世界/下界各 1 个 placed_feature，

  Unified weighted ore pool — weighted ore selection, one placed_feature per dimension

- **洞穴智能检测** — 空气密度扫描，大矿洞多条贴壁矿脉、小矿洞单条，

  Cave-aware generation — air-density scanning, multiple wall veins in large caves, one in small caves

- **第三方矿石自动矿脉化** — 自动收集模组矿石并注入矿脉池，

  Third-party ore auto-injection — auto-collects modded ores into the vein pool

## 安装 | Installation

前置依赖：Minecraft 1.20.1 + Fabric Loader >=0.15.11 + Fabric API >=0.92.1 + Java >=17

1. 下载最新 release 的 jar 文件
2. 放入 .minecraft/mods/ 目录，启动游戏

Prerequisites: Minecraft 1.20.1 + Fabric Loader >=0.15.11 + Fabric API >=0.92.1 + Java >=17

1. Download the latest release jar
2. Drop into .minecraft/mods/ and launch

## 兼容性 | Compatibility

与 **CaveControl** 同装时存在冲突（都覆盖 noise_settings），OreVeins 优先生效。

Conflicts with **CaveControl** (both override noise_settings); OreVeins takes priority.

## License

All Rights Reserved
