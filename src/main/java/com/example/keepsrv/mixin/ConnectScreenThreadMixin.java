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
