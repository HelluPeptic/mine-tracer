package com.minetracer.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.minetracer.features.minetracer.NewOptimizedLogStorage;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
@Mixin(SignBlockEntity.class)
public class MixinSignBlockEntity {
    @Unique
    private String minetracer$beforeText = null;
    @Unique
    private boolean minetracer$editLogged = false;
    
    @Inject(method = "updateSignText", at = @At("HEAD"))
    private void minetracer$cacheBeforeText(Player player, boolean front, List messages, CallbackInfo ci) {
        SignBlockEntity sign = (SignBlockEntity) (Object) this;
        Component[] beforeLines = sign.getText(front).getMessages(false);
        StringBuilder beforeSb = new StringBuilder();
        for (int i = 0; i < beforeLines.length; i++) {
            Component msg = beforeLines[i];
            beforeSb.append(msg != null ? msg.getString() : "");
            if (i < beforeLines.length - 1)
                beforeSb.append("\n");
        }
        minetracer$beforeText = beforeSb.toString();
        minetracer$editLogged = false;
    }
    
    @Inject(method = "updateSignText", at = @At("TAIL"))
    private void minetracer$logSignEdit(Player player, boolean front, List messages, CallbackInfo ci) {
        if (minetracer$editLogged)
            return;
        minetracer$editLogged = true;
        
        SignBlockEntity sign = (SignBlockEntity) (Object) this;
        BlockPos pos = sign.getBlockPos();
        if (sign.getLevel() instanceof ServerLevel) {
            Component[] afterLines = sign.getText(front).getMessages(false);
            String[] afterArr = new String[afterLines.length];
            for (int i = 0; i < afterLines.length; i++) {
                afterArr[i] = afterLines[i] != null ? afterLines[i].getString() : "";
            }
            String[] beforeArr = minetracer$beforeText != null ? minetracer$beforeText.split("\\n", -1)
                    : new String[afterArr.length];
            if (beforeArr.length != afterArr.length) {
                String[] newBeforeArr = new String[afterArr.length];
                for (int i = 0; i < afterArr.length; i++) {
                    newBeforeArr[i] = (i < beforeArr.length) ? beforeArr[i] : "";
                }
                beforeArr = newBeforeArr;
            }
            com.google.gson.Gson gson = new com.google.gson.Gson();
            String nbt = String.format("{\"before\":%s,\"after\":%s}", gson.toJson(beforeArr), gson.toJson(afterArr));
            NewOptimizedLogStorage.logSignAction("edit", player, pos, gson.toJson(afterArr), nbt);
        }
        minetracer$beforeText = null;
    }
}
