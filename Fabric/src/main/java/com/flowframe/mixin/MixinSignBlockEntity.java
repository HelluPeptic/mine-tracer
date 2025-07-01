package com.flowframe.mixin;

import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.flowframe.features.minetracer.LogStorage;
import java.util.List;

@Mixin(SignBlockEntity.class)
public class MixinSignBlockEntity {
    @Unique
    private String flowframe$beforeText = null;
    @Unique
    private boolean flowframe$editLogged = false;

    @Inject(
        method = "tryChangeText",
        at = @At("HEAD")
    )
    private void flowframe$cacheBeforeText(PlayerEntity player, boolean front, List messages, CallbackInfo ci) {
        SignBlockEntity sign = (SignBlockEntity)(Object)this;
        Text[] beforeLines = sign.getText(front).getMessages(false);
        StringBuilder beforeSb = new StringBuilder();
        for (int i = 0; i < beforeLines.length; i++) {
            Text msg = beforeLines[i];
            beforeSb.append(msg != null ? msg.getString() : "");
            if (i < beforeLines.length - 1) beforeSb.append("\n");
        }
        flowframe$beforeText = beforeSb.toString();
        flowframe$editLogged = false;
    }

    @Inject(
        method = "tryChangeText",
        at = @At("TAIL")
    )
    private void flowframe$logSignEdit(PlayerEntity player, boolean front, List messages, CallbackInfo ci) {
        if (flowframe$editLogged) return;
        flowframe$editLogged = true;
        SignBlockEntity sign = (SignBlockEntity)(Object)this;
        BlockPos pos = sign.getPos();
        if (sign.getWorld() instanceof ServerWorld) {
            // After text (get actual sign text after edit)
            Text[] afterLines = sign.getText(front).getMessages(false);
            String[] afterArr = new String[afterLines.length];
            for (int i = 0; i < afterLines.length; i++) {
                afterArr[i] = afterLines[i] != null ? afterLines[i].getString() : "";
            }
            String[] beforeArr = flowframe$beforeText != null ? flowframe$beforeText.split("\\n", -1) : new String[afterArr.length];
            if (beforeArr.length != afterArr.length) {
                // Pad beforeArr to match afterArr length
                String[] newBeforeArr = new String[afterArr.length];
                for (int i = 0; i < afterArr.length; i++) {
                    newBeforeArr[i] = (i < beforeArr.length) ? beforeArr[i] : "";
                }
                beforeArr = newBeforeArr;
            }
            com.google.gson.Gson gson = new com.google.gson.Gson();
            String nbt = String.format("{\"before\":%s,\"after\":%s}", gson.toJson(beforeArr), gson.toJson(afterArr));
            LogStorage.logSignAction("edit", player, pos, gson.toJson(afterArr), nbt);
        }
        flowframe$beforeText = null;
    }
}
