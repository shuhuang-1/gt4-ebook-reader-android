# GT4 电子书阅读器 · 安卓端

在手机上挑一本本地 txt，解析成章节后**分片推送到 HUAWEI WATCH GT4**，在手表上离线阅读。

这是我前面发的那个阅读器需要配合使用的安卓端app，因为如果把书编译进hap包的话，就会因为体积太大而安装不上，应用调试助手报错 安装失败：10.内部错误，所以只能使用手机把它推送到手表里面，不过这个依旧是半成品，华为的wear engine审核还没通过，手机没办法往手表里面推送文件，大概再过几天审核通过了，就可以用了。

> 配套的手表端（Lite Wearable / HarmonyOS）：[gt4-ebook-reader](https://github.com/shuhuang-1/gt4-ebook-reader)

---

## 它解决什么问题

GT4 是 Lite Wearable 系统，**没有 WiFi、没有 eSIM**，唯一的射频是蓝牙。手表本身上不了网，也拿不到手机文件。

所以架构是：

```
手机（本项目）
  ① 选一个本地 txt
  ② 识别编码（UTF-8 / GBK）
  ③ 按章节切分
  ④ 按 4KB 分片
        ↓  Wear Engine P2P
手表（配套端）
  ⑤ 收片 → 拼装 → 落盘
  ⑥ 本地阅读，进度自动记忆
```

手机端负责所有重活（解析、编码识别、分片），手表端只做接收和阅读。**蓝牙断、片丢失、重复片、跳片**都有对应处理。

---

## 当前状态

| 模块 | 状态 |
|---|---|
| txt 选择与解析 | ✅ 完成 |
| 编码识别（UTF-8 / GBK） | ✅ 完成 |
| 章节切分与勾选 | ✅ 完成 |
| 分片与拼片协议 | ✅ 已模拟验证（重开比对逐字一致） |
| 手表端 UI / 阅读页 | ✅ 完成 |
| **Wear Engine 实际传输** | ⏳ **权限审批中**，暂用 `MockTransport` 打 logcat |

`Transport` 是接口，目前挂的是 `MockTransport`（只在 logcat 打报文）。等 Wear Engine 权限批下来，换成 `WearEngineTransport` 即可接通真机。

---

## 文件结构

```
app/src/main/java/com/me/gt4reader/phone/
├── MainActivity.kt              主界面：选书、章节勾选、推送
├── BookParser.kt                txt 解析：编码识别 + 章节切分
├── Protocol.kt                  分片 / 拼片协议
├── Transport.kt                 传输接口 + MockTransport
└── WearEngineTransport.kt.template   权限批下来后改名启用
```

协议细节见 [`PROTOCOL.md`](PROTOCOL.md)。

---

## 构建

**环境**：JDK 17 + Gradle 8.0+（AGP 8.1.4 / Kotlin 1.9.20）

```bash
git clone https://github.com/shuhuang-1/GT4-ebook-reader-android.git
cd GT4-ebook-reader-android

# 生成 wrapper（首次）
gradle wrapper --gradle-version 8.0

# 编译
./gradlew clean assembleDebug
```

APK 产物：

```
app/build/outputs/apk/debug/app-debug.apk
```

**包名**：`com.me.gt4reader.phone`

---

## 已知限制

- Wear Engine 权限审批中，传输通道尚未接通
- Lite Wearable 不支持 HTTPS 网络请求，手表端无法直连网络，一切数据走手机中转
- 手表端为 466×466 圆屏，CSS 不支持 `linear-gradient`，渐隐效果由多层不同透明度的条叠加实现

---

## 许可

MIT
