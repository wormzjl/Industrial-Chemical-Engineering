# Column benchmark 数据集审查

## 结论

数据没有结构性损坏：8 个 JSONL 文件中的 502 条运行记录全部可解析，0 个失败行；`all`、`full`、`loose`、`pilot` 的主线和 fork 样本键完全配对，且所有记录均为 `SUCCESS`。其中 `all` 已包含 `full` 和 `loose`，去重后是两棵树各 122 条运行记录；数值字段没有 NaN、负计数或超过闭合阈值的记录。

严格路径的结果一致。主线与 fork 的最大差异约为 3.4e-13 K、1.5e-15 摩尔分率、6.6e-13 流量；`loose` 路径最大摩尔分率差约 9.8e-14。

## 发现的问题

1. fork 的 in-game 路径不是同精度结果。`binary-2` 出现约 305 K 温差、0.50 摩尔分率差和 3.23 MW 冷凝器负荷差；预设案例的报告值达到 2.1–4.0 K、最大 `dx=0.098`。这是固定 1e-3 闭合、跳过液压重算和粗略 continuation 的结果，不能作为“可接受误差”的默认依据。
2. 报告的 in-game “残差减少 24%”只计算了 `strictResiduals`：主线 875394，fork 669036。fork 另有 70425 个 `frozenResiduals`；若暂按一次评估等价，合计减少约 15.5%，而不是 24%。数据没有给出两类评估的成本权重，不能把计数直接当作总耗时。
3. warm-start 的 4 个 edit 案例从 10.97 s 降到 0.97 s，但 fork 的冷 `strict-edit` 总计 11.59 s，主线为 11.33 s。加速来自复用已接受状态，不能外推到冷启动。
4. 可复现性不足：`all-*.jsonl` 拼接了两个 header；`full-main.log`、`full-fork.log`、`loose-main.log`、`loose-fork.log` 都是空文件；记录没有输入 fixture/config 哈希，当前 checkout 也找不到报告引用的 `tools/column-solver-benchmark/README.md`。

## 建议

保留严格路径、workspace/base-residual reuse 和 warm-start；不要直接启用当前 aggressive in-game 路径。下一轮应记录输入哈希、Git commit、完整命令行、每类 residual 的耗时，并单独报告 `strictResiduals` 与 `frozenResiduals`。
