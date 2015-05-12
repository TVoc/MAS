package be.kuleuven.cs.mas;

import java.util.*;

import be.kuleuven.cs.mas.gradientfield.FieldEmitter;
import com.github.rinde.rinsim.geom.ConnectionData;
import com.github.rinde.rinsim.geom.Graph;
import org.apache.commons.math3.random.RandomGenerator;

import com.github.rinde.rinsim.core.TickListener;
import com.github.rinde.rinsim.core.TimeLapse;
import com.github.rinde.rinsim.core.model.road.CollisionGraphRoadModel;
import com.github.rinde.rinsim.core.model.road.MovingRoadUser;
import com.github.rinde.rinsim.core.model.road.RoadModel;
import com.github.rinde.rinsim.geom.Point;
import com.google.common.base.Optional;

class AGVAgent implements TickListener, MovingRoadUser, FieldEmitter {

    private final RandomGenerator rng;
    private Optional<CollisionGraphRoadModel> roadModel;
    private Graph<? extends ConnectionData> graph;
    private Optional<Point> destination;
    private LinkedList<Point> path;

    AGVAgent(RandomGenerator r) {
        rng = r;
        roadModel = Optional.absent();
        destination = Optional.absent();
        path = new LinkedList<>();
    }

    @Override
    public void initRoadUser(RoadModel model) {
        roadModel = Optional.of((CollisionGraphRoadModel) model);
        graph = roadModel.get().getGraph();
        Point p;
        do {
            p = model.getRandomPosition(rng);
        } while (roadModel.get().isOccupied(p));
        roadModel.get().addObjectAt(this, p);
    }

    @Override
    public double getSpeed() {
        return 1;
    }

    void nextDestination() {
        destination = Optional.of(roadModel.get().getRandomPosition(rng));
        path = new LinkedList<>(roadModel.get().getShortestPathTo(this,
                destination.get()));
    }

    @Override
    public void tick(TimeLapse timeLapse) {
        if (!destination.isPresent()) {
            nextDestination();
        }

        roadModel.get().followPath(this, path, timeLapse);

        if (roadModel.get().getPosition(this).equals(destination.get())) {
            nextDestination();
        }
    }

    @Override
    public void afterTick(TimeLapse timeLapse) {}

    @Override
    public double getStrength() {
        return 0;
    }

    @Override
    public Point getPosition() {
        return null;
    }

}
