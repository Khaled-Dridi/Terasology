package org.terasology.logic.characters;

import com.bulletphysics.BulletGlobals;
import com.bulletphysics.collision.dispatch.CollisionObject;
import com.bulletphysics.collision.dispatch.CollisionWorld;
import com.bulletphysics.collision.dispatch.GhostObject;
import com.bulletphysics.collision.dispatch.PairCachingGhostObject;
import com.bulletphysics.collision.shapes.ConvexShape;
import com.bulletphysics.linearmath.QuaternionUtil;
import com.bulletphysics.linearmath.Transform;
import org.terasology.entitySystem.EntityRef;
import org.terasology.math.TeraMath;
import org.terasology.math.Vector3fUtil;
import org.terasology.world.WorldProvider;

import javax.vecmath.AxisAngle4f;
import javax.vecmath.Matrix4f;
import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;

/**
 * @author Immortius
 */
public class BulletCharacterMovementSystem implements CharacterMovementSystem {

    /**
     * The amount of extra distance added to vertical movement to allow for penetration.
     */
    private static final float VERTICAL_PENETRATION_LEEWAY = 0.05f;
    /**
     * The amount of vertical penetration to allow.
     */
    private static final float VERTICAL_PENETRATION = 0.04f;
    /**
     * The amount of extra distance added to horizontal movement to allow for penentration.
     */
    private static final float HORIZONTAL_PENETRATION_LEEWAY = 0.04f;
    /**
     * The amount of horizontal penetration to allow.
     */
    private static final float HORIZONTAL_PENETRATION = 0.03f;

    public static final float GRAVITY = 28.0f;
    public static final float TERMINAL_VELOCITY = 64.0f;

    public static final float UNDERWATER_GRAVITY = 0.25f;
    public static final float UNDERWATER_INERTIA = 2.0f;
    public static final float WATER_TERMINAL_VELOCITY = 4.0f;

    public static final float GHOST_INERTIA = 4f;

    private static final float CHECK_FORWARD_DIST = 0.05f;

    private WorldProvider worldProvider;

    // Processing state variables

    private float steppedUpDist = 0;
    private boolean stepped = false;

    public BulletCharacterMovementSystem(WorldProvider worldProvider) {
        this.worldProvider = worldProvider;
    }

    @Override
    public CharacterState step(CharacterState initial, CharacterMoveInputEvent input, EntityRef entity) {
        CharacterMovementComponent characterMovementComponent = entity.getComponent(CharacterMovementComponent.class);

        CharacterState result = updatePosition(characterMovementComponent, initial, input);
        result.setTime(input.getTime());
        if (result.getMode() != MovementMode.GHOSTING) {
            checkSwimming(characterMovementComponent, result);
        }
        updateRotation(characterMovementComponent, result, input);
        return result;
    }

    private void updateRotation(CharacterMovementComponent movementComp, CharacterState result, CharacterMoveInputEvent input) {
        if (movementComp.faceMovementDirection && result.getVelocity().lengthSquared() > 0.01f) {
            float yaw = (float) Math.atan2(result.getVelocity().x, result.getVelocity().z);
            AxisAngle4f axisAngle = new AxisAngle4f(0, 1, 0, yaw);
            result.getRotation().set(axisAngle);
        } else {
            QuaternionUtil.setEuler(result.getRotation(), TeraMath.DEG_TO_RAD * input.getYaw(), 0, 0);
        }
    }


    /**
     * Updates whether a character is underwater. A higher and lower point of the character is tested for being in water,
     * only if both points are in water does the character count as swimming.
     *
     * @param movementComp
     * @param state
     */
    private void checkSwimming(final CharacterMovementComponent movementComp, final CharacterState state) {
        Vector3f worldPos = state.getPosition();
        boolean topUnderwater = false;
        boolean bottomUnderwater = false;
        Vector3f top = new Vector3f(worldPos);
        Vector3f bottom = new Vector3f(worldPos);
        top.y += 0.25f * movementComp.height;
        bottom.y -= 0.25f * movementComp.height;

        topUnderwater = worldProvider.getBlock(top).isLiquid();
        bottomUnderwater = worldProvider.getBlock(bottom).isLiquid();
        boolean newSwimming = topUnderwater && bottomUnderwater;

        // Boost when leaving water
        if (!newSwimming && state.getMode() == MovementMode.SWIMMING && state.getVelocity().y > 0) {
            float len = state.getVelocity().length();
            state.getVelocity().scale((len + 8) / len);
        }
        state.setMode((newSwimming) ? MovementMode.SWIMMING : MovementMode.WALKING);
    }

    private CharacterState updatePosition(final CharacterMovementComponent movementComp, final CharacterState state, CharacterMoveInputEvent input) {
        switch (state.getMode()) {
            case GHOSTING:
                return ghost(movementComp, state, input);
            case SWIMMING:
                return swim(movementComp, state, input);
            case WALKING:
                return walk(movementComp, state, input);
            default:
                return walk(movementComp, state, input);
        }
    }

    private CharacterState swim(final CharacterMovementComponent movementComp, final CharacterState state, CharacterMoveInputEvent input) {
        CharacterState newState = new CharacterState(state);
        Vector3f desiredVelocity = new Vector3f(input.getMovementDirection());
        float lengthSquared = desiredVelocity.lengthSquared();
        if (lengthSquared > 1) {
            desiredVelocity.normalize();
        }
        float maxSpeed = movementComp.maxWaterSpeed;
        if (input.isRunning()) {
            maxSpeed *= movementComp.runFactor;
        }
        desiredVelocity.scale(maxSpeed);

        desiredVelocity.y -= UNDERWATER_GRAVITY;

        // Modify velocity towards desired, up to the maximum rate determined by friction
        Vector3f velocityDiff = new Vector3f(desiredVelocity);
        velocityDiff.sub(state.getVelocity());
        velocityDiff.scale(Math.min(UNDERWATER_INERTIA * input.getDeltaMS(), 1.0f));

        newState.getVelocity().x += velocityDiff.x;
        newState.getVelocity().y += velocityDiff.y;
        newState.getVelocity().z += velocityDiff.z;

        // Slow down due to friction
        float speed = newState.getVelocity().length();
        if (speed > movementComp.maxWaterSpeed) {
            newState.getVelocity().scale((speed - 4 * (speed - movementComp.maxWaterSpeed) * input.getDeltaMS()) / speed);
        }

        Vector3f moveDelta = new Vector3f(newState.getVelocity());
        moveDelta.scale(input.getDeltaMS());

        // Note: No stepping underwater, no issue with slopes
        MoveResult moveResult = move(state.getPosition(), moveDelta, 0, -1, movementComp.collider);
        Vector3f distanceMoved = new Vector3f(moveResult.finalPosition);
        distanceMoved.sub(state.getPosition());

        newState.getPosition().set(moveResult.finalPosition);
        //if (distanceMoved.length() > 0)
        //    entity.send(new MovedEvent(distanceMoved, moveResult.finalPosition));

        return newState;
    }

    private CharacterState ghost(final CharacterMovementComponent movementComp, final CharacterState state, CharacterMoveInputEvent input) {
        Vector3f desiredVelocity = new Vector3f(input.getMovementDirection());
        float lengthSquared = desiredVelocity.lengthSquared();
        if (lengthSquared > 1) {
            desiredVelocity.normalize();
        }

        float maxSpeed = movementComp.maxGhostSpeed;
        if (input.isRunning()) {
            maxSpeed *= movementComp.runFactor;
        }

        desiredVelocity.scale(maxSpeed);

        // Modify velocity towards desired, up to the maximum rate determined by friction
        Vector3f velocityDiff = new Vector3f(desiredVelocity);
        velocityDiff.sub(state.getVelocity());

        velocityDiff.scale(Math.min(GHOST_INERTIA * input.getDeltaMS(), 1.0f));

        CharacterState newState = new CharacterState(state);

        newState.getVelocity().add(velocityDiff);

        // No collision, so just do the move
        Vector3f deltaPos = new Vector3f(newState.getVelocity());
        deltaPos.scale(input.getDeltaMS());
        newState.getPosition().add(deltaPos);
        //if (deltaPos.length() > 0)
        //    entity.send(new MovedEvent(deltaPos, worldPos));

        //movementComp.collider.setWorldTransform(new Transform(new Matrix4f(new Quat4f(0, 0, 0, 1), worldPos, 1.0f)));

        return newState;
    }

    private CharacterState walk(final CharacterMovementComponent movementComp, final CharacterState state, CharacterMoveInputEvent input) {
        CharacterState newState = new CharacterState(state);
        Vector3f desiredVelocity = new Vector3f(input.getMovementDirection());
        float lengthSquared = desiredVelocity.lengthSquared();
        if (lengthSquared > 1) {
            desiredVelocity.normalize();
        }

        float maxSpeed = movementComp.maxGroundSpeed;
        if (input.isRunning()) {
            maxSpeed *= movementComp.runFactor;
        }

        // As we can't use it, remove the y component of desired movement while maintaining speed
        if (desiredVelocity.y != 0) {
            float speed = desiredVelocity.length();
            desiredVelocity.y = 0;
            if (desiredVelocity.x != 0 || desiredVelocity.z != 0) {
                desiredVelocity.normalize();
                desiredVelocity.scale(speed);
            }
        }
        desiredVelocity.scale(maxSpeed);

        // Modify velocity towards desired, up to the maximum rate determined by friction
        Vector3f velocityDiff = new Vector3f(desiredVelocity);
        velocityDiff.sub(state.getVelocity());

        velocityDiff.scale(Math.min(movementComp.groundFriction * input.getDeltaMS(), 1.0f));

        newState.getVelocity().x += velocityDiff.x;
        newState.getVelocity().z += velocityDiff.z;
        newState.getVelocity().y = Math.max(-TERMINAL_VELOCITY, (newState.getVelocity().y - GRAVITY * input.getDeltaMS()));

        Vector3f moveDelta = new Vector3f(newState.getVelocity());
        moveDelta.scale(input.getDeltaMS());

        MoveResult moveResult = move(state.getPosition(), moveDelta, (state.isGrounded()) ? movementComp.stepHeight : 0, movementComp.slopeFactor, movementComp.collider);
        Vector3f distanceMoved = new Vector3f(moveResult.finalPosition);
        distanceMoved.sub(newState.getPosition());

        newState.getPosition().set(moveResult.finalPosition);
        //if (distanceMoved.length() > 0)
        //    entity.send(new MovedEvent(distanceMoved, moveResult.finalPosition));

        movementComp.collider.setWorldTransform(new Transform(new Matrix4f(new Quat4f(0, 0, 0, 1), moveResult.finalPosition, 1.0f)));

        if (moveResult.hitBottom) {
            if (!newState.isGrounded()) {
                //entity.send(new VerticalCollisionEvent(movementComp.getVelocity(), moveResult.finalPosition));
                newState.setGrounded(true);
            }
            newState.getVelocity().y = 0;
            // Jumping is only possible, if the entity is standing on ground
            if (input.isJumpRequested()) {
                newState.setGrounded(false);
                newState.getVelocity().y += movementComp.jumpSpeed;
            }
        } else {
            if (moveResult.hitTop && newState.getVelocity().y > 0) {
                newState.getVelocity().y = -0.5f * newState.getVelocity().y;
            }
            newState.setGrounded(false);
        }

        if (moveResult.hitHoriz) {
            //entity.send(new HorizontalCollisionEvent(location.getWorldPosition(),movementComp.getVelocity()));
        }

        if (newState.isGrounded()) {
            /*movementComp.footstepDelta += distanceMoved.length();
            if (movementComp.footstepDelta > movementComp.distanceBetweenFootsteps) {
                movementComp.footstepDelta -= movementComp.distanceBetweenFootsteps;
                entity.send(new FootstepEvent());
            } */
        }

        return newState;
    }

    private static class MoveResult {
        public Vector3f finalPosition;
        public boolean hitHoriz = false;
        public boolean hitBottom = false;
        public boolean hitTop = false;
    }

    private MoveResult move(final Vector3f startPosition, final Vector3f moveDelta, final float stepHeight, final float slopeFactor, final PairCachingGhostObject collider) {
        steppedUpDist = 0;
        stepped = false;

        MoveResult result = new MoveResult();
        Vector3f position = new Vector3f(startPosition);
        result.finalPosition = position;

        // Actual upwards movement
        if (moveDelta.y > 0) {
            result.hitTop = moveDelta.y - moveUp(moveDelta.y, collider, position) > BulletGlobals.SIMD_EPSILON;
        }
        result.hitHoriz = moveHorizontal(new Vector3f(moveDelta.x, 0, moveDelta.z), collider, position, slopeFactor, stepHeight);
        if (moveDelta.y < 0 || steppedUpDist > 0) {
            float dist = (moveDelta.y < 0) ? moveDelta.y : 0;
            dist -= steppedUpDist;
            result.hitBottom = moveDown(dist, slopeFactor, collider, position);
        }
        if (!result.hitBottom && stepHeight > 0) {
            Vector3f tempPos = new Vector3f(position);
            result.hitBottom = moveDown(-stepHeight, slopeFactor, collider, tempPos);
            // Don't apply step down if nothing to step onto
            if (result.hitBottom) {
                position.set(tempPos);
            }
        }
        return result;
    }

    private boolean moveHorizontal(Vector3f horizMove, PairCachingGhostObject collider, Vector3f position, float slopeFactor, float stepHeight) {
        float remainingFraction = 1.0f;
        float dist = horizMove.length();
        if (dist < BulletGlobals.SIMD_EPSILON) {
            return false;
        }

        boolean horizontalHit = false;
        Vector3f normalizedDir = Vector3fUtil.safeNormalize(horizMove, new Vector3f());
        Vector3f targetPos = new Vector3f(normalizedDir);
        targetPos.scale(dist + HORIZONTAL_PENETRATION_LEEWAY);
        targetPos.add(position);
        int iteration = 0;
        Vector3f lastHitNormal = new Vector3f(0, 1, 0);
        while (remainingFraction >= 0.01f && iteration++ < 10) {
            SweepCallback callback = sweep(position, targetPos, collider, slopeFactor, HORIZONTAL_PENETRATION);

            /* Note: this isn't quite correct (after the first iteration the closestHitFraction is only for part of the moment)
               but probably close enough */
            float actualDist = Math.max(0, (dist + HORIZONTAL_PENETRATION_LEEWAY) * callback.closestHitFraction - HORIZONTAL_PENETRATION_LEEWAY);
            if (actualDist != 0) {
                remainingFraction -= actualDist / dist;
            }
            if (callback.hasHit()) {
                if (actualDist > BulletGlobals.SIMD_EPSILON) {
                    Vector3f actualMove = new Vector3f(normalizedDir);
                    actualMove.scale(actualDist);
                    position.add(actualMove);
                }

                dist -= actualDist;
                Vector3f newDir = new Vector3f(normalizedDir);
                newDir.scale(dist);

                float slope = callback.hitNormalWorld.dot(new Vector3f(0, 1, 0));
                // We step up if we're hitting a big slope, or if we're grazing the ground)
                if (slope < slopeFactor || 1 - slope < BulletGlobals.SIMD_EPSILON) {
                    boolean stepping = checkStep(collider, position, newDir, callback, slopeFactor, stepHeight);
                    if (!stepping) {
                        horizontalHit = true;

                        Vector3f newHorizDir = new Vector3f(newDir.x, 0, newDir.z);
                        Vector3f horizNormal = new Vector3f(callback.hitNormalWorld.x, 0, callback.hitNormalWorld.z);
                        if (horizNormal.lengthSquared() > BulletGlobals.SIMD_EPSILON) {
                            horizNormal.normalize();
                            if (lastHitNormal.dot(horizNormal) > BulletGlobals.SIMD_EPSILON) {
                                break;
                            }
                            lastHitNormal.set(horizNormal);
                            extractResidualMovement(horizNormal, newHorizDir);
                        }

                        newDir.set(newHorizDir);
                    }
                } else {
                    // Hitting a shallow slope, move up it
                    Vector3f newHorizDir = new Vector3f(newDir.x, 0, newDir.z);
                    extractResidualMovement(callback.hitNormalWorld, newDir);
                    Vector3f modHorizDir = new Vector3f(newDir);
                    modHorizDir.y = 0;
                    newDir.scale(newHorizDir.length() / modHorizDir.length());
                }

                float sqrDist = newDir.lengthSquared();
                if (sqrDist > BulletGlobals.SIMD_EPSILON) {
                    newDir.normalize();
                    if (newDir.dot(normalizedDir) <= 0.0f) {
                        break;
                    }
                } else {
                    break;
                }
                dist = (float) Math.sqrt(sqrDist);
                normalizedDir.set(newDir);
                targetPos.set(normalizedDir);
                targetPos.scale(dist + HORIZONTAL_PENETRATION_LEEWAY);
                targetPos.add(position);
            } else {
                normalizedDir.scale(dist);
                position.add(normalizedDir);
                break;
            }
        }
        return horizontalHit;
    }

    private boolean checkStep(PairCachingGhostObject collider, Vector3f position, Vector3f direction, SweepCallback callback, float slopeFactor, float stepHeight) {
        if (!stepped) {
            stepped = true;

            Vector3f lookAheadOffset = new Vector3f(direction);
            lookAheadOffset.y = 0;
            lookAheadOffset.normalize();
            lookAheadOffset.scale(CHECK_FORWARD_DIST);
            boolean hitStep = false;
            float stepSlope = 1f;

            Vector3f fromWorld = new Vector3f(callback.hitPointWorld);
            fromWorld.y += stepHeight + 0.05f;
            fromWorld.add(lookAheadOffset);
            Vector3f toWorld = new Vector3f(callback.hitPointWorld);
            toWorld.y -= 0.05f;
            toWorld.add(lookAheadOffset);
            CollisionWorld.ClosestRayResultCallback rayResult = new CollisionWorld.ClosestRayResultCallback(fromWorld, toWorld);
            CollisionWorld.rayTestSingle(new Transform(new Matrix4f(new Quat4f(0, 0, 0, 1), fromWorld, 1.0f)), new Transform(new Matrix4f(new Quat4f(0, 0, 0, 1), toWorld, 1.0f)), callback.hitCollisionObject, callback.hitCollisionObject.getCollisionShape(), callback.hitCollisionObject.getWorldTransform(new Transform()), rayResult);
            if (rayResult.hasHit()) {
                hitStep = true;
                stepSlope = rayResult.hitNormalWorld.dot(new Vector3f(0, 1, 0));
            }
            fromWorld.add(lookAheadOffset);
            toWorld.add(lookAheadOffset);
            rayResult = new CollisionWorld.ClosestRayResultCallback(fromWorld, toWorld);
            CollisionWorld.rayTestSingle(new Transform(new Matrix4f(new Quat4f(0, 0, 0, 1), fromWorld, 1.0f)), new Transform(new Matrix4f(new Quat4f(0, 0, 0, 1), toWorld, 1.0f)), callback.hitCollisionObject, callback.hitCollisionObject.getCollisionShape(), callback.hitCollisionObject.getWorldTransform(new Transform()), rayResult);
            if (rayResult.hasHit()) {
                hitStep = true;
                stepSlope = Math.min(stepSlope, rayResult.hitNormalWorld.dot(new Vector3f(0, 1, 0)));
            }

            if (hitStep && stepSlope >= slopeFactor) {
                steppedUpDist = moveUp(stepHeight, collider, position);
                return true;
            }
        }
        return false;
    }

    private boolean moveDown(float dist, float slopeFactor, PairCachingGhostObject collider, Vector3f position) {

        float remainingDist = -dist;

        Vector3f targetPos = new Vector3f(position);
        targetPos.y -= remainingDist + VERTICAL_PENETRATION_LEEWAY;
        Vector3f normalizedDir = new Vector3f(0, -1, 0);
        boolean hit = false;

        int iteration = 0;
        while (remainingDist > BulletGlobals.SIMD_EPSILON && iteration++ < 10) {
            SweepCallback callback = sweep(position, targetPos, collider, -1.0f, VERTICAL_PENETRATION);

            float actualDist = Math.max(0, (remainingDist + VERTICAL_PENETRATION_LEEWAY) * callback.closestHitFraction - VERTICAL_PENETRATION_LEEWAY);
            Vector3f expectedMove = new Vector3f(targetPos);
            expectedMove.sub(position);
            if (expectedMove.lengthSquared() > BulletGlobals.SIMD_EPSILON) {
                expectedMove.normalize();
                expectedMove.scale(actualDist);
                position.add(expectedMove);
            }

            remainingDist -= actualDist;
            if (remainingDist < BulletGlobals.SIMD_EPSILON) {
                break;
            }

            if (callback.hasHit()) {
                Vector3f contactPoint = callback.hitPointWorld;
                float originalSlope = callback.hitNormalWorld.dot(new Vector3f(0, 1, 0));
                if (originalSlope < slopeFactor) {
                    float slope = 1;
                    boolean foundSlope = false;

                    // We do two ray traces, and use the steepest, to avoid incongruities with the slopes
                    Vector3f fromWorld = new Vector3f(contactPoint);
                    fromWorld.y += 0.2f;
                    Vector3f toWorld = new Vector3f(contactPoint);
                    toWorld.y -= 0.2f;
                    CollisionWorld.ClosestRayResultCallback rayResult = new CollisionWorld.ClosestRayResultCallback(fromWorld, toWorld);
                    CollisionWorld.rayTestSingle(new Transform(new Matrix4f(new Quat4f(0, 0, 0, 1), fromWorld, 1.0f)), new Transform(new Matrix4f(new Quat4f(0, 0, 0, 1), toWorld, 1.0f)), callback.hitCollisionObject, callback.hitCollisionObject.getCollisionShape(), callback.hitCollisionObject.getWorldTransform(new Transform()), rayResult);

                    if (rayResult.hasHit()) {
                        foundSlope = true;
                        slope = Math.min(slope, (rayResult.hitNormalWorld.dot(new Vector3f(0, 1, 0))));
                    }

                    Vector3f secondTraceOffset = new Vector3f(callback.hitNormalWorld);
                    secondTraceOffset.y = 0;
                    secondTraceOffset.normalize();
                    secondTraceOffset.scale(CHECK_FORWARD_DIST);
                    fromWorld.add(secondTraceOffset);
                    toWorld.add(secondTraceOffset);

                    rayResult = new CollisionWorld.ClosestRayResultCallback(fromWorld, toWorld);
                    CollisionWorld.rayTestSingle(new Transform(new Matrix4f(new Quat4f(0, 0, 0, 1), fromWorld, 1.0f)), new Transform(new Matrix4f(new Quat4f(0, 0, 0, 1), toWorld, 1.0f)), callback.hitCollisionObject, callback.hitCollisionObject.getCollisionShape(), callback.hitCollisionObject.getWorldTransform(new Transform()), rayResult);

                    if (rayResult.hasHit()) {
                        foundSlope = true;
                        slope = Math.min(slope, (rayResult.hitNormalWorld.dot(new Vector3f(0, 1, 0))));
                    }

                    if (!foundSlope) {
                        slope = originalSlope;
                    }

                    if (slope < slopeFactor) {
                        remainingDist -= actualDist;
                        expectedMove.set(targetPos);
                        expectedMove.sub(position);

                        extractResidualMovement(callback.hitNormalWorld, expectedMove);
                        float sqrDist = expectedMove.lengthSquared();
                        if (sqrDist > BulletGlobals.SIMD_EPSILON) {
                            expectedMove.normalize();
                            if (expectedMove.dot(normalizedDir) <= 0.0f) {
                                hit = true;
                                break;
                            }
                        } else {
                            hit = true;
                            break;
                        }
                        if (expectedMove.y > -BulletGlobals.SIMD_EPSILON) {
                            hit = true;
                            break;
                        }
                        normalizedDir.set(expectedMove);

                        expectedMove.scale(-remainingDist / expectedMove.y + HORIZONTAL_PENETRATION_LEEWAY);
                        targetPos.add(position, expectedMove);
                    } else {
                        hit = true;
                        break;
                    }
                } else {
                    hit = true;
                    break;
                }
            } else {
                break;
            }
        }

        if (iteration >= 10) {
            hit = true;
        }

        return hit;
    }

    private float moveUp(float riseAmount, GhostObject collider, Vector3f position) {
        SweepCallback callback = sweep(position, new Vector3f(position.x, position.y + riseAmount + VERTICAL_PENETRATION_LEEWAY, position.z), collider, -1.0f, VERTICAL_PENETRATION_LEEWAY);

        if (callback.hasHit()) {
            float actualDist = Math.max(0, ((riseAmount + VERTICAL_PENETRATION_LEEWAY) * callback.closestHitFraction) - VERTICAL_PENETRATION_LEEWAY);
            position.y += actualDist;
            return actualDist;
        }
        position.y += riseAmount;
        return riseAmount;
    }

    private SweepCallback sweep(Vector3f from, Vector3f to, GhostObject collider, float slopeFactor, float allowedPenetration) {
        Transform startTransform = new Transform(new Matrix4f(new Quat4f(0, 0, 0, 1), from, 1.0f));
        Transform endTransform = new Transform(new Matrix4f(new Quat4f(0, 0, 0, 1), to, 1.0f));
        SweepCallback callback = new SweepCallback(collider, new Vector3f(0, 1, 0), slopeFactor);
        callback.collisionFilterGroup = collider.getBroadphaseHandle().collisionFilterGroup;
        callback.collisionFilterMask = collider.getBroadphaseHandle().collisionFilterMask;

        collider.convexSweepTest((ConvexShape) (collider.getCollisionShape()), startTransform, endTransform, callback, allowedPenetration);
        return callback;
    }

    private static class SweepCallback extends CollisionWorld.ClosestConvexResultCallback {
        protected CollisionObject me;
        protected final Vector3f up;
        protected float minSlopeDot;

        public SweepCallback(CollisionObject me, final Vector3f up, float minSlopeDot) {
            super(new Vector3f(), new Vector3f());
            this.me = me;
            this.up = up;
            this.minSlopeDot = minSlopeDot;
        }

        @Override
        public float addSingleResult(CollisionWorld.LocalConvexResult convexResult, boolean normalInWorldSpace) {
            if (convexResult.hitCollisionObject == me) {
                return 1.0f;
            }

            return super.addSingleResult(convexResult, normalInWorldSpace);
        }
    }

    private Vector3f extractResidualMovement(Vector3f hitNormal, Vector3f direction) {
        return extractResidualMovement(hitNormal, direction, 1f);
    }

    private Vector3f extractResidualMovement(Vector3f hitNormal, Vector3f direction, float normalMag) {
        float movementLength = direction.length();
        if (movementLength > BulletGlobals.SIMD_EPSILON) {
            direction.normalize();

            Vector3f reflectDir = Vector3fUtil.reflect(direction, hitNormal, new Vector3f());
            reflectDir.normalize();

            Vector3f perpindicularDir = Vector3fUtil.getPerpendicularComponent(reflectDir, hitNormal, new Vector3f());


            if (normalMag != 0.0f) {
                Vector3f perpComponent = new Vector3f();
                perpComponent.scale(normalMag * movementLength, perpindicularDir);
                direction.set(perpComponent);
            }
        }
        return direction;
    }


}
