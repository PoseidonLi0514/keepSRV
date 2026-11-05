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
