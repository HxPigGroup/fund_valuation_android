# fund_valuation_android

`fund_valuation_site` 的 Android 原生版本。

这个项目没有把 Python 服务塞进手机里运行，而是把网页的核心使用流程改写成 Android App：

- 本地维护基金跟踪列表
- 公共页面和手机号个人页本地隔离
- 手动刷新估值
- 展示官方估算值、官方估算涨跌、昨日增长、近一月增长
- 默认按官方估算涨跌从大到小排序
- 缓存最近一次刷新结果

## 项目结构

- `app/src/main/java/com/hxpig/fundvaluation/MainActivity.java`: 原生页面和交互
- `app/src/main/java/com/hxpig/fundvaluation/FundService.java`: 天天基金、东方财富接口请求
- `app/src/main/java/com/hxpig/fundvaluation/FundStorage.java`: 本地基金列表、缓存、个人页隔离
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
