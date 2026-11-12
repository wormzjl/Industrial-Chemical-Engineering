package com.wormzjl.icheme.science.tutorial.scene;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * The one orthographic projection of a film's frame to the screen, used by the render tool and by the game's view: a
 * scene point to screen x and y (pixels, y down) and depth (m along the view, larger farther). The view's axes: right
 * {@code r}, up {@code u} and forward {@code f} with {@code r x u = -f}, so a face counter-clockwise seen from outside
 * stays counter-clockwise on the screen as the eye sees it (the game culls it as its chunk renderer does).
 */
public final class Projection {
    /** The steepest pitch a turned view reaches, radians. */
    public static final double MAX_PITCH = 1.45;

    private final double[] right, up, forward, target;
    private final double pixelsPerMetre, centreX, centreY;

    /**
     * The camera's view, turned by {@code yawOffset} and {@code pitchOffset} (the player's drag) and zoomed, at
     * {@code pixelsPerMetre} = the camera's scale times {@code zoom} times {@code fit} (the view's pixels a design pixel),
     * centred on ({@code centreX}, {@code centreY}).
     */
    public static Projection of(Frame.CameraState camera, double yawOffset, double pitchOffset, double zoom, double fit, double centreX, double centreY) {
        return new Projection(camera.yaw() + yawOffset, Math.max(-MAX_PITCH, Math.min(MAX_PITCH, camera.pitch() + pitchOffset)), camera.scale() * zoom * fit,
                new double[]{camera.x(), camera.y(), camera.z()}, centreX, centreY);
    }

    public Projection(double yaw, double pitch, double pixelsPerMetre, double[] target, double centreX, double centreY) {
        double cp = DeterministicMath.cos(pitch);
        forward = new double[]{DeterministicMath.sin(yaw) * cp, -DeterministicMath.sin(pitch), DeterministicMath.cos(yaw) * cp};
        // right = forward x +y, normalised; up = right x forward.
        double rx = -forward[2], rz = forward[0], l = Math.sqrt(rx * rx + rz * rz);
        right = new double[]{rx / l, 0, rz / l};
        up = new double[]{right[1] * forward[2] - right[2] * forward[1], right[2] * forward[0] - right[0] * forward[2], right[0] * forward[1] - right[1] * forward[0]};
        this.target = target.clone();
        this.pixelsPerMetre = pixelsPerMetre;
        this.centreX = centreX;
        this.centreY = centreY;
    }

    /** Screen x, y (px) and depth (m, larger farther) of the scene point (x, y, z). */
    public double[] screen(double x, double y, double z) {
        double dx = x - target[0], dy = y - target[1], dz = z - target[2];
        return new double[]{centreX + pixelsPerMetre * (right[0] * dx + right[1] * dy + right[2] * dz),
                centreY - pixelsPerMetre * (up[0] * dx + up[1] * dy + up[2] * dz),
                forward[0] * dx + forward[1] * dy + forward[2] * dz};
    }

    /**
     * The projection as an affine map, rows for screen x, screen y and depth: {@code {a, b, c, d}} a row, the value
     * {@code a x + b y + c z + d}. The game's view builds its matrix from it.
     */
    public double[][] affine() {
        double s = pixelsPerMetre;
        return new double[][]{
                {s * right[0], s * right[1], s * right[2], centreX - s * dot(right, target)},
                {-s * up[0], -s * up[1], -s * up[2], centreY + s * dot(up, target)},
                {forward[0], forward[1], forward[2], -dot(forward, target)}};
    }

    public double[] right() {
        return right.clone();
    }

    public double[] up() {
        return up.clone();
    }

    /** The view's direction, from the eye into the scene. */
    public double[] forward() {
        return forward.clone();
    }

    public double pixelsPerMetre() {
        return pixelsPerMetre;
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
