# fund_valuation_android

`fund_valuation_site` 的 Android 原生版本。

这个项目没有把 Python 服务塞进手机里运行，而是把网页的核心使用流程改写成 Android App：

- 本地维护基金跟踪列表
- 公共页面和手机号个人页本地隔离
- 手动刷新估值
- 展示官方估算值、官方估算涨跌、昨日增长、近一月增长
- 默认按官方估算涨跌从大到小排序
- 缓存最近一次刷新结果
- 个人页面支持复制公共页面或其他个人页面的跟踪列表
- 个人页面支持按基金设置涨跌提醒，并在“重要提醒”中查看触发状态
- 本机记录最近进入的个人页面，下次可从首页快速进入

当前版本：`0.3.1`

版本记录：

- `0.3.1`: 压缩顶部工具栏，重要提醒改为铃铛，本机记录最近个人页面，关于页改为分板块查看。
- `0.3.0`: 首页增加“关于”，个人页增加复制跟踪列表、长按基金操作、涨跌提醒和重要提醒记录。
- `0.2.0`: 优化移动端列表，固定基金名称列，加入紧凑顶部工具栏。
- `0.1.0`: 创建 Android 原生项目，支持基金跟踪、刷新和缓存。

## 项目结构

- `app/src/main/java/com/hxpig/fundvaluation/MainActivity.java`: 原生页面和交互
- `app/src/main/java/com/hxpig/fundvaluation/FundAlert.java`: 个人页涨跌提醒规则
- `app/src/main/java/com/hxpig/fundvaluation/FundService.java`: 天天基金、东方财富接口请求
- `app/src/main/java/com/hxpig/fundvaluation/FundStorage.java`: 本地基金列表、缓存、个人页隔离和提醒状态
- `app/src/main/res/raw/default_tracked_funds.txt`: 从原站点带过来的默认公共跟踪列表

## 数据源

Android 端直接请求公网 HTTPS 接口：

- 官方估算：`https://fundgz.1234567.com.cn/js/{基金代码}.js`
- 历史净值：`https://api.fund.eastmoney.com/f10/lsjz`

原站点里的自算估值依赖 `AkShare + Tushare + Python` 的持仓和股票行情逻辑，当前 Android 原生版先保留“自算估值 / 自算涨跌”扩展列入口，但不在手机端计算。后续如果需要完全一致，可以把原 Python 服务改成 API 后端，让 Android App 调后端。

## 构建

当前机器已经安装好构建环境：

- JDK 17
- Gradle 8.10.2
- Android SDK command-line tools
- Android SDK Platform 35
- Android SDK Build-Tools 34.0.0 / 35.0.0
- Android Platform-Tools

命令行构建：

```bash
./gradlew assembleDebug
```

Debug APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

用 Android Studio 打开时：

1. 用 Android Studio 打开 `/root/fund_valuation_android`
2. 等待 Gradle Sync
3. 运行 `app`

## 和原站点的差异

- 原站点适合长期部署在服务器上，自动刷新并可公网访问。
- Android 版以本机使用为主，刷新由 App 内手动触发。
- 手机号仍只作为本地页面标识，不做短信验证。
- Android 版不读取原站点的 `.env`、`valuation_cache.json`、`user_data/`。
