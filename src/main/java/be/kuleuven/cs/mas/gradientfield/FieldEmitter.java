package be.kuleuven.cs.mas.gradientfield;

import com.github.rinde.rinsim.geom.Point;

/**
 * Thsi interface is implemented in all objects which emit a gradient field.
 */
public interface FieldEmitter {

    /**
     * Returns the strength of the field generated by this emitter. The strength is negative for an attraction field and
     * positive for a repulsion field.
     */
    double getStrength();

    /**
     * Returns the position of the emitter as a {@link Point}.
     */
    Point getPosition();

}
