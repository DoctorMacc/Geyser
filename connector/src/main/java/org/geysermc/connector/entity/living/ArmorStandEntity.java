/*
 * Copyright (c) 2019-2021 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.connector.entity.living;

import com.github.steveice10.mc.protocol.data.game.entity.metadata.EntityMetadata;
import com.github.steveice10.mc.protocol.data.game.entity.metadata.MetadataType;
import com.github.steveice10.mc.protocol.data.game.entity.metadata.Rotation;
import com.nukkitx.math.vector.Vector3f;
import com.nukkitx.protocol.bedrock.data.entity.EntityData;
import com.nukkitx.protocol.bedrock.data.entity.EntityFlag;
import com.nukkitx.protocol.bedrock.data.inventory.ItemData;
import com.nukkitx.protocol.bedrock.packet.MoveEntityAbsolutePacket;
import lombok.Getter;
import org.geysermc.connector.entity.ArmorStandPose;
import org.geysermc.connector.entity.BedrockArmorStandPose;
import org.geysermc.connector.entity.LivingEntity;
import org.geysermc.connector.entity.type.EntityType;
import org.geysermc.connector.network.session.GeyserSession;

import java.util.function.Consumer;

public class ArmorStandEntity extends LivingEntity {

    // These are used to store the state of the armour stand for use when handling invisibility
    @Getter
    private boolean isMarker = false;
    private boolean isInvisible = false;
    private boolean isSmall = false;

    /**
     * On Java Edition, armor stands always show their name. Invisibility hides the name on Bedrock.
     * By having a second entity, we can allow an invisible entity with the name tag.
     * (This lets armor on armor stands still show)
     */
    private ArmorStandEntity secondEntity = null;
    /**
     * Whether this is the primary armor stand that holds the armor and not the name tag.
     */
    private boolean primaryEntity = true;
    /**
     * Whether the last position update included the offset.
     */
    private boolean lastPositionIncludedOffset = false;

    /**
     * The current Java pose of this armor stand.
     */
    private final ArmorStandPose pose = new ArmorStandPose();
    /**
     * If hands are being shown in Java. If so, we exclude them from pose checks
     */
    private boolean showHands = false;
    private GeyserSession session;

    public ArmorStandEntity(long entityId, long geyserId, EntityType entityType, Vector3f position, Vector3f motion, Vector3f rotation) {
        super(entityId, geyserId, entityType, position, motion, rotation);
    }

    @Override
    public void spawnEntity(GeyserSession session) {
        this.session = session;
        this.rotation = Vector3f.from(rotation.getX(), rotation.getX(), rotation.getX());
        super.spawnEntity(session);
    }

    @Override
    public boolean despawnEntity(GeyserSession session) {
        if (secondEntity != null) {
            secondEntity.despawnEntity(session);
        }
        return super.despawnEntity(session);
    }

    @Override
    public void moveRelative(GeyserSession session, double relX, double relY, double relZ, Vector3f rotation, boolean isOnGround) {
        if (secondEntity != null) {
            secondEntity.moveRelative(session, relX, relY, relZ, rotation, isOnGround);
        }
        super.moveRelative(session, relX, relY, relZ, rotation, isOnGround);
    }

    @Override
    public void moveAbsolute(GeyserSession session, Vector3f position, Vector3f rotation, boolean isOnGround, boolean teleported) {
        // Fake the height to be above where it is so the nametag appears in the right location for invisible non-marker armour stands
        lastPositionIncludedOffset = false;
        if (secondEntity != null) {
            secondEntity.moveAbsolute(session, position.add(0d, entityType.getHeight() * (isSmall ? 0.55d : 1d), 0d), rotation, isOnGround, teleported);
        } else if (!isMarker && isInvisible && passengers.isEmpty() && !metadata.getFlags().getFlag(EntityFlag.INVISIBLE)) { // Means it's not visible
            position = position.add(0d, entityType.getHeight() * (isSmall ? 0.55d : 1d), 0d);
            lastPositionIncludedOffset = true;
        }

        super.moveAbsolute(session, position, Vector3f.from(rotation.getX(), rotation.getX(), rotation.getX()), isOnGround, teleported);
    }

    @Override
    public void updateBedrockMetadata(EntityMetadata entityMetadata, GeyserSession session) {
        super.updateBedrockMetadata(entityMetadata, session);
        if (entityMetadata.getId() == 0 && entityMetadata.getType() == MetadataType.BYTE) {
            byte xd = (byte) entityMetadata.getValue();

            // Check if the armour stand is invisible and store accordingly
            if (primaryEntity) {
                isInvisible = (xd & 0x20) == 0x20;
                updateSecondEntityStatus();
            }
        } else if (entityMetadata.getId() == 2) {
            updateSecondEntityStatus();
        } else if (entityMetadata.getId() == 14 && entityMetadata.getType() == MetadataType.BYTE) {
            byte xd = (byte) entityMetadata.getValue();

            // While we cannot hide the hands on Bedrock, we can at least take into consideration if we need to check for a pose with hands
            boolean oldShowHands = this.showHands;
            this.showHands = (xd & 0x04) == 0x04;
            if (oldShowHands != this.showHands) {
                checkForRotationUpdate(entityMetadata);
            }

            // isSmall
            if ((xd & 0x01) == 0x01) {
                isSmall = true;

                if (metadata.getFloat(EntityData.SCALE) != 0.55f && metadata.getFloat(EntityData.SCALE) != 0.0f) {
                    metadata.put(EntityData.SCALE, 0.55f);
                }

                if (metadata.get(EntityData.BOUNDING_BOX_WIDTH) != null && metadata.get(EntityData.BOUNDING_BOX_WIDTH).equals(0.5f)) {
                    metadata.put(EntityData.BOUNDING_BOX_WIDTH, 0.25f);
                    metadata.put(EntityData.BOUNDING_BOX_HEIGHT, 0.9875f);
                }
            } else if (metadata.get(EntityData.BOUNDING_BOX_WIDTH) != null && metadata.get(EntityData.BOUNDING_BOX_WIDTH).equals(0.25f)) {
                metadata.put(EntityData.BOUNDING_BOX_WIDTH, entityType.getWidth());
                metadata.put(EntityData.BOUNDING_BOX_HEIGHT, entityType.getHeight());
            }

            // setMarker
            if ((xd & 0x10) == 0x10 && (metadata.get(EntityData.BOUNDING_BOX_WIDTH) == null || !metadata.get(EntityData.BOUNDING_BOX_WIDTH).equals(0.0f))) {
                metadata.put(EntityData.BOUNDING_BOX_WIDTH, 0.0f);
                metadata.put(EntityData.BOUNDING_BOX_HEIGHT, 0.0f);
                isMarker = true;
            }
        } else {
            checkForRotationUpdate(entityMetadata);
        }
        if (secondEntity != null) {
            secondEntity.updateBedrockMetadata(entityMetadata, session);
        }
    }

    /**
     * If the provided entity metadata is a rotation value, update our
     *
     * @param entityMetadata
     */
    private void checkForRotationUpdate(EntityMetadata entityMetadata) {
        Consumer<Vector3f> setter;
        switch (entityMetadata.getId()) {
            // Check to see if we are expecting an incoming rotation value first
            case 15:
                setter = pose::setHead;
                break;
            case 16:
                setter = pose::setBody;
                break;
            case 17:
                setter = pose::setLeftArm;
                break;
            case 18:
                setter = pose::setRightArm;
                break;
            case 19:
                setter = pose::setLeftLeg;
                break;
            case 20:
                setter = pose::setRightLeg;
                break;
            default:
                return;
        }
        Rotation partRotation = (Rotation) entityMetadata.getValue();
        setter.accept(Vector3f.from(partRotation.getPitch(), partRotation.getYaw(), partRotation.getRoll()));

        recalculateClosestPose();
    }

    /**
     * From the given rotation values, determine what the closest Bedrock armor stand position is
     */
    private void recalculateClosestPose() {
        if (!primaryEntity || isMarker || (isInvisible && !metadata.getFlags().getFlag(EntityFlag.INVISIBLE))) {
            return;
        }
        BedrockArmorStandPose matchedPose = BedrockArmorStandPose.DEFAULT;
        float matchedDistance = Float.MAX_VALUE;
        for (BedrockArmorStandPose potentialPose : BedrockArmorStandPose.values()) {
            float bodyDifference = potentialPose.getBody().distanceSquared(this.pose.getBody());
            float headDifference = potentialPose.getHead().distanceSquared(this.pose.getHead());
            float leftLegDifference = potentialPose.getLeftLeg().distanceSquared(this.pose.getLeftLeg());
            float rightLegDifference = potentialPose.getRightLeg().distanceSquared(this.pose.getRightLeg());

            float averageDistance;
            if (this.showHands) {
                float leftArmDifference = potentialPose.getLeftArm().distanceSquared(this.pose.getLeftArm());
                float rightArmDifference = potentialPose.getRightArm().distanceSquared(this.pose.getRightArm());
                averageDistance = (bodyDifference + headDifference + leftArmDifference + rightArmDifference + leftLegDifference + rightLegDifference) / 6f;
            } else {
                averageDistance = (bodyDifference + headDifference + leftLegDifference + rightLegDifference) / 4f;
            }

            if (averageDistance < matchedDistance) {
                matchedPose = potentialPose;
                matchedDistance = averageDistance;
            }
            if (averageDistance == 0) {
                break; // This is an exact pose
            }
        }
        metadata.put(EntityData.ARMOR_STAND_POSE_INDEX, matchedPose.ordinal());
    }

    @Override
    public void updateBedrockMetadata(GeyserSession session) {
        if (secondEntity != null) {
            secondEntity.updateBedrockMetadata(session);
        }
        super.updateBedrockMetadata(session);
    }

    @Override
    public void setHelmet(ItemData helmet) {
        super.setHelmet(helmet);
        updateSecondEntityStatus();
    }

    @Override
    public void setChestplate(ItemData chestplate) {
        super.setChestplate(chestplate);
        updateSecondEntityStatus();
    }

    @Override
    public void setLeggings(ItemData leggings) {
        super.setLeggings(leggings);
        updateSecondEntityStatus();
    }

    @Override
    public void setBoots(ItemData boots) {
        super.setBoots(boots);
        updateSecondEntityStatus();
    }

    @Override
    public void setHand(ItemData hand) {
        super.setHand(hand);
        updateSecondEntityStatus();
    }

    @Override
    public void setOffHand(ItemData offHand) {
        super.setOffHand(offHand);
        updateSecondEntityStatus();
    }

    /**
     * Determine if we need to load or unload the second entity.
     */
    private void updateSecondEntityStatus() {
        if (!primaryEntity) return;
        if (!isInvisible || isMarker) {
            // It is either impossible to show armor, or the armor stand isn't invisible. We good.
            if (secondEntity != null) {
                secondEntity.despawnEntity(session);
                secondEntity = null;
                // Update the position of this armor stand
                updatePosition();
            }
            return;
        }
        boolean isNametagEmpty = metadata.getString(EntityData.NAMETAG).isEmpty();
        if ((!helmet.equals(ItemData.AIR) || !chestplate.equals(ItemData.AIR) || !leggings.equals(ItemData.AIR) || !boots.equals(ItemData.AIR)
                || !hand.equals(ItemData.AIR) || !offHand.equals(ItemData.AIR)) && !isNametagEmpty) {
            if (secondEntity != null) return; // No need to recreate
            // Create the second entity. It doesn't need to worry about the items, but it does need to worry about
            // the metadata as it will hold the name tag.
            secondEntity = new ArmorStandEntity(0, session.getEntityCache().getNextEntityId().incrementAndGet(),
                    EntityType.ARMOR_STAND, position, motion, rotation);
            secondEntity.primaryEntity = false;
            // Copy metadata
            secondEntity.isSmall = isSmall;
            secondEntity.lastPositionIncludedOffset = this.lastPositionIncludedOffset;
            secondEntity.getMetadata().putAll(metadata);
            // Copy the flags so they aren't the same object in memory
            secondEntity.getMetadata().putFlags(metadata.getFlags().copy());
            // Guarantee this copy is NOT invisible
            secondEntity.getMetadata().getFlags().setFlag(EntityFlag.INVISIBLE, false);
            // Scale to 0 to show nametag
            secondEntity.getMetadata().put(EntityData.SCALE, 0.0f);
            // No bounding box as we don't want to interact with this entity
            secondEntity.getMetadata().put(EntityData.BOUNDING_BOX_WIDTH, 0.0f);
            secondEntity.getMetadata().put(EntityData.BOUNDING_BOX_HEIGHT, 0.0f);
            secondEntity.spawnEntity(session);
            // Reset scale of the proper armor stand
            metadata.put(EntityData.SCALE, isSmall ? 0.55f : 1f);
            // Set the proper armor stand to invisible to show armor
            metadata.getFlags().setFlag(EntityFlag.INVISIBLE, true);

            // Update the position of the armor stands
            updatePosition();
            secondEntity.updatePositionWithOffset();
        } else if (isNametagEmpty) {
            // We can just make an invisible entity
            // Reset scale of the proper armor stand
            metadata.put(EntityData.SCALE, isSmall ? 0.55f : 1f);
            // Set the proper armor stand to invisible to show armor
            metadata.getFlags().setFlag(EntityFlag.INVISIBLE, true);
            if (secondEntity != null) {
                secondEntity.despawnEntity(session);
                secondEntity = null;
                // Update the position of this armor stand
                updatePosition();
            }
        } else {
            // We don't need to make a new entity
            updatePositionWithOffset();
            metadata.getFlags().setFlag(EntityFlag.INVISIBLE, false);
            metadata.put(EntityData.SCALE, 0.0f);
            if (secondEntity != null) {
                secondEntity.despawnEntity(session);
                secondEntity = null;
            }
        }
        this.updateBedrockMetadata(session);
    }

    /**
     * Updates position without calling movement code.
     */
    private void updatePosition() {
        if (lastPositionIncludedOffset) {
            this.position = position.sub(0d, entityType.getHeight() * (isSmall ? 0.55d : 1d), 0d);
            lastPositionIncludedOffset = false;
        }
        MoveEntityAbsolutePacket moveEntityPacket = new MoveEntityAbsolutePacket();
        moveEntityPacket.setRuntimeEntityId(geyserId);
        moveEntityPacket.setPosition(position);
        moveEntityPacket.setRotation(Vector3f.from(rotation.getX(), rotation.getX(), rotation.getX()));
        moveEntityPacket.setOnGround(onGround);
        moveEntityPacket.setTeleported(false);
        session.sendUpstreamPacket(moveEntityPacket);
    }

    /**
     * Updates position without calling movement code and includes the offset.
     */
    private void updatePositionWithOffset() {
        if (!lastPositionIncludedOffset) {
            this.position = this.position.add(0d, entityType.getHeight() * (isSmall ? 0.55d : 1d), 0d);
            lastPositionIncludedOffset = true;
        }
        MoveEntityAbsolutePacket moveEntityPacket = new MoveEntityAbsolutePacket();
        moveEntityPacket.setRuntimeEntityId(geyserId);
        moveEntityPacket.setPosition(this.position);
        moveEntityPacket.setRotation(Vector3f.from(rotation.getX(), rotation.getX(), rotation.getX()));
        moveEntityPacket.setOnGround(onGround);
        moveEntityPacket.setTeleported(false);
        session.sendUpstreamPacket(moveEntityPacket);
    }
}
