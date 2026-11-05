# Keep SRV（保持 SRV）

一个适用于 **Minecraft 26.1.2 (Fabric)** 的客户端模组，用于在进服发送握手包（`ClientIntentionPacket`）时保留 SRV 解析前的原始域名与端口（如 `25565`），从而绕过部分校园网 / 防火墙针对 Minecraft 握手包的 DPI 拦截规则。

---

## 工作原理

部分校园网 DPI 拦截规则在同时满足以下两个条件时会丢弃连接：
1. 首个 TCP 数据段是单独的 Minecraft 握手包（`ClientIntentionPacket`）；
2. 握手包内的 `port` 字段 **等于** TCP 连接实际发往的目标端口。

当服务器域名配置了 SRV 记录（例如 `_minecraft._tcp.example.com -> node.provider.cn:24333`）时：
- **服务器列表 Ping (`ServerStatusPinger`)**：原版客户端在握手包中填入**未解析 SRV 前的原始地址与默认端口 `25565`**，而 TCP 实际连接 `24333` 端口（`25565 != 24333`），因此可以正常刷出 MOTD。
- **点击进服 (`ConnectScreen$1`)**：原版客户端在握手包中填入 **SRV 解析后的真实地址与端口 `24333`**（`24333 == 24333`），从而触发 DPI 拦截导致无法进服。

**Keep SRV** 允许针对单个服务器开启「保持 SRV」选项：TCP 依然连接 SRV 解析出的真实地址与端口，但握手包内填入你输入的原始域名与端口（如 `25565`），使进服行为与列表 Ping 一致。

---

## 功能特性

- **按服务器独立开关**：在「添加服务器 / 编辑服务器」界面的服务器地址输入框右侧新增 `[ ] 保持SRV` 复选框。
- **持久化存储**：配置随 `servers.dat` 自动保存与读取（NBT 字段 `keepSrv`），支持编辑与复制同步。
- **高兼容性**：通过 `@ModifyArgs` 拦截 `Connection.initiateServerboundPlayConnection`，避免与 `ViaFabricPlus` 的 SRV 解析 `@Redirect` 发生冲突。

---

## 使用方法

1. 安装 **Fabric Loader `>=0.20.0`**（适用 Minecraft **`26.1.2`**）。
2. 将编译生成的 `keepsrv-1.0.0.jar` 放入客户端 `.minecraft/mods` 目录。
3. 启动游戏，进入「多人游戏」界面：
   - 选中需要通过 SRV 连接的服务器（服务器地址填写未解析的域名，不要手动附带真实端口），点击「编辑」；
   - 在「服务器地址」输入框右侧勾选 **`[x] 保持SRV`**；
   - 点击「完成」保存，直接进服即可。

---

## 构建说明

本项目基于 **Java 25** 与 **Fabric Loom 1.19-SNAPSHOT**（Minecraft 26.1.2 官方去混淆映射）构建：

```bash
./gradlew build
```

构建产物位于 `build/libs/keepsrv-1.0.0.jar`。
