/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.launch.mixins;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.RotationMoveEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * While swimming, the vertical part of your motion is pulled towards your look angle in
 * {@code Player.travel}, which reads the real entity rotation — outside of {@code moveRelative}, so the
 * moveRelative rotation spoof can't cover it. Same deal as elytra flight in {@link MixinLivingEntity}:
 * set the real rotation for the duration of the getLookAngle call so pitch steering works with free look,
 * then restore. The user's camera never sees any of this.
 */
@Mixin(PlayerEntity.class)
public abstract class MixinPlayer extends Entity {

    /**
     * Event called to override the movement direction while swimming
     */
    @Unique
    private RotationMoveEvent swimRotationEvent;

    private MixinPlayer(EntityType<?> entityType, World world) {
        super(entityType, world);
    }

    @Inject(
            method = "travel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/PlayerEntity;getRotationVector()Lnet/minecraft/util/math/Vec3d;"
            )
    )
    private void preSwimMove(Vec3d direction, CallbackInfo ci) {
        this.getBaritone().ifPresent(baritone -> {
            this.swimRotationEvent = new RotationMoveEvent(RotationMoveEvent.Type.MOTION_UPDATE, this.getYaw(), this.getPitch());
            baritone.getGameEventHandler().onPlayerRotationMove(this.swimRotationEvent);
            this.setYaw(this.swimRotationEvent.getYaw());
            this.setPitch(this.swimRotationEvent.getPitch());
        });
    }

    @Inject(
            method = "travel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/PlayerEntity;getRotationVector()Lnet/minecraft/util/math/Vec3d;",
                    shift = At.Shift.AFTER
            )
    )
    private void postSwimAngle(Vec3d direction, CallbackInfo ci) {
        if (this.swimRotationEvent != null) {
            this.setYaw(this.swimRotationEvent.getOriginal().getYaw());
            this.setPitch(this.swimRotationEvent.getOriginal().getPitch());
            this.swimRotationEvent = null;
        }
    }

    @Unique
    private Optional<IBaritone> getBaritone() {
        // noinspection ConstantConditions
        if (ClientPlayerEntity.class.isInstance(this)) {
            return Optional.ofNullable(BaritoneAPI.getProvider().getBaritoneForPlayer((ClientPlayerEntity) (Object) this));
        } else {
            return Optional.empty();
        }
    }
}
