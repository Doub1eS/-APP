# 工资单、累计个税与月度预算 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** 在深圳固定工资模板下记录实际到账、估算累计预扣个税，并用到账收入和现有消费计算月度结余与预算。

**Architecture:** Kotlin纯计算规则、Room工资/模板/年度期初/预算表、Compose财务页面。工资独立存表，不生成重复消费；现有支付采集进程不变。JSON备份升级到v5并兼容v1–4。

**Tech Stack:** 现有Kotlin、Compose Material3、Room；不引入联网工资服务。

**Spec:** 本文件的范围与接口约定为执行依据，依据用户本轮授权及已确认参数。

## Global Constraints

- 在新分支codex/payroll-monthly-budget开发；先将当前支付宝修复推送main。复用当前目录及已配置Android构建环境，不额外创建worktree。
- 默认税前17000元，各缴费基数13300元；个人养老8%、医疗0.5%、失业0.2%、公积金5%。这是用户的实际模板，不当作全深圳通用政策默认。
- 每月输入实际到账；每个发薪月一张工资单，重复保存为编辑，不重复增加收入。工资归属备注可与到账月不同。
- 个税按居民个人工资薪金累计预扣、七档税率、每个已记录受雇月5000元减除费用。跨年重置；支持年度起算月份、此前累计税基及已扣税额。
- 专项附加扣除默认0且未确认，年度期初默认未确认。缺历史月份或资料不完整必须明确显示估算；不得伪造准确性。支持实际已扣个税，用于后续累计校准。
- 工资条保存当时模板和扣款参数；修改默认模板不改变历史工资。编辑早月后后续月份实时重算估算，实际到账和实际扣税不变。
- 月度统计按到账日期和消费日期；仅现有已入账EXPENSE计支出；社保公积金个税不再从到账收入重复扣除，待确认不计支出。
- 总预算与九大分类预算独立比较、每月保存；空表示未设置，0元预算可表达禁止该类消费；不默认假定用户预算。
- 备份包含工资、模板、年度期初和预算；同主键保留本机，恢复事务一致。保留个人手机账目，不在个人手机跑自动测试。

## Review Focus

- 年中开始、缺月、税年切换、修改历史月份不产生虚假准确税额。
- 七档边界及逐月分角舍入，实际扣税校准和负应纳税额不自动退款。
- 收入仅计实际到账；订阅与饮食关联消费不重复计数。
- 重复月份编辑、删除后月度合计刷新，预算跨月隔离。
- Room6→7迁移与v5备份往返、老备份兼容，禁止真实薪资数据上Git。

## Tasks

- [x] 0. 核实并同步现有GitHub代码、新建功能分支；原41项JVM测试通过。
- [x] 1. FinanceData.kt、Records.kt：数据类型、校验、DAO、迁移及存储测试。
- [x] 2. FinanceRules.kt、FinanceRulesTest.kt：先写失败用例，完成累计个税与月度统计纯计算规则。
- [x] 3. FinanceScreen.kt：工资/默认模板/期初设置、预算、历史月切换和结果明细；可达入口及UI测试。
- [x] 4. Backup.kt、BackupPanel.kt：v5财务数据备份及恢复测试。
- [x] 5. 构建、JVM与模拟器回归、独立审查修复；更新README/验收记录，提交推送功能分支，交付APK。

验收进展：59项JVM与最终138项Android15模拟器测试通过；计算/迁移和备份交叉审查无待处理问题。版本0.9.0，APK保留releases/payroll-budget-v9/。支付宝NFC反馈已连接真机只读核对，缺通知原文与历史诊断，仍待定位。

## Verification commands

`build-local.ps1 -Test`；模拟器专用安装及instrumentation；`git diff --check`；推送后fetch并核对远端commit。

## Policy reference

累计预扣公式和七档税率： https://fgk.chinatax.gov.cn/zcfgk/c100012/c5194838/content.html （2026-10-21已核对）。仅普通居民工资薪金，年终奖单独计税及多雇主汇算不在本轮范围。
