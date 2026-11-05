# 「保持 SRV」Fabric 模组开发落地指南 (`guide.md`)

> 适用目标版本：**Minecraft 26.1.2 + Fabric Loader 0.20.1**（对应本地客户端 `D:/Desktop/.minecraft/versions/314/314.jar`）

---

## 1. 核心原理与目标

### 1.1 为什么「保持 SRV」能绕过校园网拦截？
根据网络排查报告（`mc-network-report.md` 第 10 节）与 `314.jar` 反编译结果：
- 校园网 DPI 拦截规则要求**同时满足两个条件**才丢包：
  1. 首个 TCP 数据段是单独的 Minecraft 握手包（`ClientIntentionPacket`）；
  2. 握手包内的 `port` 字段 **等于** TCP 连接实际发往的目标端口。
- 当服务器域名配置了 SRV 记录（例如 `_minecraft._tcp.server.marvinleee.com -> play.simpfun.cn:24333` 或 `_minecraft._tcp.roaming.xducraft.cn -> frp-tag.com:63176`）时：
  - **服务器列表 Ping (`ServerStatusPinger`)**：原版客户端把握手包填成**未解析前的 `ServerAddress`**（端口 `25565`），而 TCP 实际连 `24333`，因为 `25565 != 24333`，所以能正常刷出 MOTD。
  - **点击进服 (`ConnectScreen$1`)**：原版客户端把握手包填成了 **SRV 解析后的 `InetSocketAddress`**（端口 `24333`），导致 `24333 == 24333`，触发校园网拦截。

### 1.2 模组要做的 3 件事
1. **数据层 (`ServerData`)**：为每个服务器配置增加一个布尔属性 `keepSrv`（默认 `false`），随 `servers.dat` 的 NBT 读写持久化，并在克隆/编辑服务器对象时同步复制。
2. **界面层 (`ManageServerScreen`)**：在「编辑服务器 / 添加服务器」界面的 **服务器地址输入框（`ipEdit`）右侧**，增加一个原版复选框 `[ ] 保持SRV`。
3. **网络层 (`ConnectScreen$1`)**：进服发起握手包时，若当前 `ServerData` 勾选了「保持 SRV」，则将传入 `initiateServerboundPlayConnection` 的 `host` 和 `port` 替换为未解析 SRV 前的 `val$hostAndPort.getHost()` 和 `val$hostAndPort.getPort()`。

---

## 2. 关键前置提醒（基于 `314.jar` 与已装模组的反编译发现）

在动手写代码前，有 **3 个极易踩坑的细节**（均已从你的 `versions/314` 环境中核实）：

### 坑点 1：MC 26.1.2 已去混淆（Unobfuscated）+ Java 25
- `versions/314/314.jar` 的 Class 文件版本为 `major version: 69`（**Java 25**），且 **26.1.2 官方客户端已完全去除混淆**，运行时直接使用 Mojang 官方类名、方法名和字段名（无 `intermediary` 映射）。
- 在 Mixin 注解中写方法描述符时，目标签名就是原版类名（如 `Lnet/minecraft/network/Connection;`）。

### 坑点 2：必须避开与 `ViaFabricPlus` 的 `@Redirect` 冲突！
你当前的 `versions/314/mods/ViaFabricPlus-4.5.2.jar` 中自带了一个 Mixin：
`com.viaversion.viafabricplus.injection.mixin.features.networking.srv_resolving.MixinConnectScreen_1`
它**已经用 `@Redirect` 拦截了 `ConnectScreen$1.run()` 里的 `InetSocketAddress.getHostName()` 和 `InetSocketAddress.getPort()`**（用于在跨版本连 `<=1.17` 服务器时还原旧版 SRV 行为）。
- **后果**：如果你也在 `ConnectScreen$1.run()` 里对 `InetSocketAddress.getHostName()` / `getPort()` 用 `@Redirect`，游戏启动时会直接因 `Redirector conflict` 崩溃！
- **正确解法**：不要拦截 `InetSocketAddress` 的方法，而是用 **`@ModifyArgs`**（或 `@WrapOperation`）拦截下一行对 **`Connection.initiateServerboundPlayConnection(...)`** 的调用，直接修改它的第 0 个参数（`host`）和第 1 个参数（`port`）。`ViaFabricPlus` 完全没有触碰这个方法，100% 兼容。

### 坑点 3：`JoinMultiplayerScreen` 编辑服务器时的临时对象拷贝
反编译 `JoinMultiplayerScreen` 发现：
- 当你选中服务器点击「编辑」时，原版会先 `new ServerData(...)` 创建一个临时对象 `this.editingServer`，并调用 `this.editingServer.copyFrom(original)`，然后才打开 `ManageServerScreen`。
- 当你点击「完成」保存时，原版又会调用 `selectedServer.copyFrom(this.editingServer)`，然后再调用 `this.servers.save()` 写入 `servers.dat`。
- 而 `ServerData.copyFrom(ServerData)` 内部第一行调用了 `this.copyNameIconFrom(ServerData)`。
- **后果**：如果你只在 `ServerData.write()` / `read()` 里读写 NBT，而**忘了注入 `copyNameIconFrom`**，那么每次点开「编辑」复选框都会被重置，点「完成」也存不进列表！

---

## 3. 涉及的原版类反编译详情 (`314.jar`)

### 3.1 `net.minecraft.client.multiplayer.ServerData`
```java
public class ServerData {
    // 将配置序列化为 NBT 保存到 servers.dat
    public CompoundTag write();
    // 从 servers.dat 的 NBT 反序列化
    public static ServerData read(CompoundTag tag);
    // 复制服务器信息（copyFrom 内部也会调用此方法）
    public void copyNameIconFrom(ServerData other);
    public void copyFrom(ServerData other);
}
```
> **注意 26.1.2 的 NBT API 变化**：`CompoundTag` 读取布尔值在 26.1.2 中使用的是 `tag.getBooleanOr(String key, boolean defaultValue)`，写入为 `tag.putBoolean(String key, boolean value)`。

### 3.2 `net.minecraft.client.gui.screens.ManageServerScreen`
反编译 `init()` 中的控件坐标布局：
- **服务器名称输入框 (`nameEdit`)**：`x = this.width / 2 - 100`, `y = 66`, `w = 200`, `h = 20`
- **服务器地址输入框 (`ipEdit`)**：`x = this.width / 2 - 100`, `y = 106`, `w = 200`, `h = 20`
  - 右边缘坐标为 `this.width / 2 + 100`。
- **资源包按钮 (`CycleButton`)**：`x = this.width / 2 - 100`, `y = this.height / 4 + 72`, `w = 200`, `h = 20`
- **原版复选框构建器 (`Checkbox.builder`)**：
  - `Checkbox.getBoxSize(font)` 固定返回 `17`（即 `17x17` 像素）。
  - 将复选框放在 **`x = this.width / 2 + 108, y = 108`**，正好位于 `ipEdit` 右侧 8px 处，且与 20px 高的输入框垂直居中对齐（`106 + (20 - 17) / 2 = 107.5 ≈ 108`）。

### 3.3 `net.minecraft.client.gui.screens.ConnectScreen$1`
反编译字段与 `run()` 关键字节码（偏移 `247~336`）：
```java
class ConnectScreen$1 extends Thread {
    final ServerAddress val$hostAndPort; // 用户输入的原始地址（如 server.marvinleee.com:25565）
    final Minecraft val$minecraft;
    final ServerData val$server;         // 当前连接的服务器配置对象（可能为 null，需判空）
    final TransferState val$transferState;
    final ConnectScreen this$0;

    public void run() {
        ...
        // 偏移 247~336：
        this.this$0.connection.initiateServerboundPlayConnection(
            inetSocketAddress.getHostName(), // arg 0: String
            inetSocketAddress.getPort(),     // arg 1: int
            LoginProtocols.SERVERBOUND,      // arg 2: ProtocolInfo
            LoginProtocols.CLIENTBOUND,      // arg 3: ProtocolInfo
            new ClientHandshakePacketListenerImpl(...), // arg 4: ClientboundPacketListener
            this.val$transferState != null   // arg 5: boolean
        );
        ...
    }
}
```
目标方法完整字节码描述符：
```text
Lnet/minecraft/network/Connection;initiateServerboundPlayConnection(Ljava/lang/String;ILnet/minecraft/network/ProtocolInfo;Lnet/minecraft/network/ProtocolInfo;Lnet/minecraft/network/ClientboundPacketListener;Z)V
```

---

## 4. 完整代码实现

### 4.1 目录结构
```text
keep-srv-mod/
├── build.gradle
├── gradle.properties
├── settings.gradle
└── src/main/
    ├── java/com/example/keepsrv/
    │   ├── access/
    │   │   └── KeepSrvServerData.java
    │   └── mixin/
    │       ├── ServerDataMixin.java
    │       ├── ManageServerScreenMixin.java
    │       └── ConnectScreenThreadMixin.java
    └── resources/
        ├── fabric.mod.json
        └── keepsrv.mixins.json
```

---

### 4.2 构建配置 (`gradle.properties` & `build.gradle`)

由于 26.1.2 已经去混淆，使用 **Fabric Loom 1.13+（无重映射模式 `net.fabricmc.fabric-loom`）** 最简单；甚至可以直接引用你本地 `.minecraft` 里的库。标准 Loom 配置如下：

**`gradle.properties`**:
```properties
org.gradle.jvmargs=-Xmx2G
minecraft_version=26.1.2
loader_version=0.20.1
mod_version=1.0.0
maven_group=com.example.keepsrv
archives_base_name=keepsrv
```

**`settings.gradle`**:
```groovy
pluginManagement {
    repositories {
        maven {
            name = 'Fabric'
            url = 'https://maven.fabricmc.net/'
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
```

**`build.gradle`**:
```groovy
plugins {
    id 'net.fabricmc.fabric-loom' version '1.13-SNAPSHOT'
}

version = project.mod_version
group = project.maven_group

base {
    archivesName = project.archives_base_name
}

dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    implementation "net.fabricmc:fabric-loader:${project.loader_version}"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

tasks.withType(JavaCompile).configureEach {
    it.options.release = 25
    it.options.encoding = "UTF-8"
}
```

---

### 4.3 Duck 接口：`KeepSrvServerData.java`
用于在 `ManageServerScreenMixin` 和 `ConnectScreenThreadMixin` 中安全访问注入到 `ServerData` 的字段：

```java
package com.example.keepsrv.access;

public interface KeepSrvServerData {
    boolean keepsrv$isKeepSrv();
    void keepsrv$setKeepSrv(boolean keepSrv);
}
```

---

### 4.4 Mixin 1：持久化与复制 `ServerDataMixin.java`
```java
package com.example.keepsrv.mixin;

import com.example.keepsrv.access.KeepSrvServerData;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerData.class)
public abstract class ServerDataMixin implements KeepSrvServerData {

    @Unique
    private static final String KEEP_SRV_NBT_KEY = "keepSrv";

    @Unique
    private boolean keepsrv$keepSrv = false;

    @Override
    public boolean keepsrv$isKeepSrv() {
        return this.keepsrv$keepSrv;
    }

    @Override
    public void keepsrv$setKeepSrv(boolean keepSrv) {
        this.keepsrv$keepSrv = keepSrv;
    }

    /**
     * 写入 servers.dat 时保存 keepSrv 字段
     */
    @Inject(method = "write", at = @At("RETURN"))
    private void onWrite(CallbackInfoReturnable<CompoundTag> cir) {
        CompoundTag tag = cir.getReturnValue();
        if (this.keepsrv$keepSrv) {
            tag.putBoolean(KEEP_SRV_NBT_KEY, true);
        }
    }

    /**
     * 从 servers.dat 读取时恢复 keepSrv 字段
     */
    @Inject(method = "read", at = @At("RETURN"))
    private static void onRead(CompoundTag tag, CallbackInfoReturnable<ServerData> cir) {
        ServerData data = cir.getReturnValue();
        boolean keepSrv = tag.getBooleanOr(KEEP_SRV_NBT_KEY, false);
        ((KeepSrvServerData) data).keepsrv$setKeepSrv(keepSrv);
    }

    /**
     * 关键：JoinMultiplayerScreen 在打开编辑界面前后会调用 copyFrom -> copyNameIconFrom
     * 注入此处可同时覆盖 copyFrom 和 copyNameIconFrom
     */
    @Inject(method = "copyNameIconFrom", at = @At("RETURN"))
    private void onCopyNameIconFrom(ServerData other, CallbackInfo ci) {
        this.keepsrv$keepSrv = ((KeepSrvServerData) other).keepsrv$isKeepSrv();
    }
}
```

---

### 4.5 Mixin 2：在服务器地址框旁添加复选框 `ManageServerScreenMixin.java`
```java
package com.example.keepsrv.mixin;

import com.example.keepsrv.access.KeepSrvServerData;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ManageServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ManageServerScreen.class)
public abstract class ManageServerScreenMixin extends Screen {

    @Shadow
    @Final
    private ServerData serverData;

    protected ManageServerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void addKeepSrvCheckbox(CallbackInfo ci) {
        KeepSrvServerData ext = (KeepSrvServerData) this.serverData;

        // ipEdit 位于 (this.width / 2 - 100, 106)，宽 200，高 20
        // 复选框放在其右侧 8px 处：(this.width / 2 + 108, 108)
        Checkbox checkbox = Checkbox.builder(Component.literal("保持SRV"), this.font)
            .pos(this.width / 2 + 108, 108)
            .selected(ext.keepsrv$isKeepSrv())
            .onValueChange((box, selected) -> ext.keepsrv$setKeepSrv(selected))
            .tooltip(Tooltip.create(Component.literal("进服握手时保持使用输入的原始域名和端口（如 25565），绕过校园网对 SRV 真实端口的拦截")))
            .build();

        this.addRenderableWidget(checkbox);
    }
}
```
> **为什么不需要额外注入 `resize()` 或 `onAdd()`？**
> 因为 `onValueChange` 回调在点击复选框的瞬间就已经把布尔值写进了 `this.serverData`：
> - 当窗口缩放触发 `resize()` -> 重新调用 `init()` 时，`ext.keepsrv$isKeepSrv()` 读到的就是最新勾选状态；
> - 当点击 ViaFabricPlus 的版本切换按钮再返回 `ManageServerScreen` 时，状态同样保留；
> - 当点击「完成」触发 `onAdd()` 时，`this.serverData` 随后在 `JoinMultiplayerScreen.editServerCallback` 中通过 `copyFrom` 同步回服务器列表并调用 `servers.save()` 落盘。

---

### 4.6 Mixin 3：进服握手替换地址与端口 `ConnectScreenThreadMixin.java`
```java
package com.example.keepsrv.mixin;

import com.example.keepsrv.access.KeepSrvServerData;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(targets = "net.minecraft.client.gui.screens.ConnectScreen$1")
public abstract class ConnectScreenThreadMixin {

    @Shadow
    @Final
    ServerAddress val$hostAndPort;

    @Shadow
    @Final
    ServerData val$server;

    @ModifyArgs(
        method = "run()V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/Connection;initiateServerboundPlayConnection(Ljava/lang/String;ILnet/minecraft/network/ProtocolInfo;Lnet/minecraft/network/ProtocolInfo;Lnet/minecraft/network/ClientboundPacketListener;Z)V"
        )
    )
    private void keepsrv$useOriginalHostAndPort(Args args) {
        if (this.val$server != null && ((KeepSrvServerData) this.val$server).keepsrv$isKeepSrv()) {
            // arg 0: hostName (String) -> 用户输入的原始域名（如 server.marvinleee.com）
            args.set(0, this.val$hostAndPort.getHost());
            // arg 1: port (int) -> 用户输入的原始端口（未显式写端口时为默认 25565）
            args.set(1, this.val$hostAndPort.getPort());
        }
    }
}
```

---

### 4.7 资源配置文件 (`fabric.mod.json` & `keepsrv.mixins.json`)

**`src/main/resources/fabric.mod.json`**:
```json
{
  "schemaVersion": 1,
  "id": "keepsrv",
  "version": "${version}",
  "name": "Keep SRV",
  "description": "在握手包中保持使用 SRV 解析前的原始域名与端口，以绕过校园网 DPI 拦截。",
  "authors": ["lhy"],
  "environment": "client",
  "mixins": [
    "keepsrv.mixins.json"
  ],
  "depends": {
    "fabricloader": ">=0.20.0",
    "minecraft": "~26.1"
  }
}
```

**`src/main/resources/keepsrv.mixins.json`**:
```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "com.example.keepsrv.mixin",
  "compatibilityLevel": "JAVA_25",
  "client": [
    "ConnectScreenThreadMixin",
    "ManageServerScreenMixin",
    "ServerDataMixin"
  ],
  "injectors": {
    "defaultRequire": 1
  }
}
```
*(注：如果所用的 Mixin 版本尚未枚举 `JAVA_25`，可将 `compatibilityLevel` 写为 `"JAVA_21"` 或 `"JAVA_22"`，参考同目录 `serverpingerfixer` 使用的是 `"JAVA_21"`，在 Java 25 下完全兼容。)*

---

## 5. 验证与测试方法

1. 执行 `./gradlew build`，将生成的 `build/libs/keepsrv-1.0.0.jar` 放入 `D:/Desktop/.minecraft/versions/314/mods/`。
2. 启动游戏，进入多人游戏列表：
   - 选中 `server.marvinleee.com` 或 `roaming.xducraft.cn`（注意地址栏只填域名，不要带 `:24333` 端口，让它走 SRV 解析）；
   - 点击「编辑」，在「服务器地址」输入框右侧勾选 **`[x] 保持SRV`**，点击「完成」。
3. 重新点开「编辑」，确认 **`[x] 保持SRV`** 依然处于勾选状态（验证 `copyNameIconFrom` 和 `servers.dat` 持久化生效）。
4. 在不启动 `Relay.java` 的情况下，直接双击进入 `server.marvinleee.com` 和 `roaming.xducraft.cn`，验证能否直连进服。
