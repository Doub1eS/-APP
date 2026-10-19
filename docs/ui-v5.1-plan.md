# 0.5.1 界面修订
用户已授权直接实现。
- 日期选择同步汇总、分类分布、明细；趋势保留完整周期；换日清除分类筛选。
- 饮食移除餐次分布，保留日期日历与餐食、照片、餐费明细。
- 睡眠显示时长、入睡和起床；跨午夜明确前一天入睡。
- 训练显示项目、时长与感受；缺失时长不填零。
- 分类颜色固定，餐饮餐具图标、交通车辆图标。
- 四页共用标题列（8dp间距）、20dp内容边距与卡片内边距，导航24dp图标。
- 验证：现有全量测试、日期筛选回归、三种明细、360dp/1.3字体、实际安装示例数据。

参考：
- https://support.myfitnesspal.com/hc/en-us/articles/360032274592-How-to-log-food-to-your-diary
- https://support.apple.com/en-hk/guide/iphone/iph72b370881/ios
- https://support.apple.com/guide/watch/end-and-view-a-summary-of-your-workout-apd95450de2a/watchos
只采用内容层级，不添加未采集的热量、心率或睡眠阶段。
